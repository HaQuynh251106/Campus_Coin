package com.campuscoin.transaction.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.repository.CategoryRepository;
import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.common.exception.TransactionStateException;
import com.campuscoin.transaction.dto.CreateTransactionRequest;
import com.campuscoin.transaction.dto.TransactionResponse;
import com.campuscoin.transaction.dto.UpdateTransactionRequest;
import com.campuscoin.transaction.entity.Transaction;
import com.campuscoin.transaction.mapper.TransactionMapper;
import com.campuscoin.transaction.repository.TransactionProcedureDao;
import com.campuscoin.transaction.repository.TransactionRepository;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final TransactionRepository transactionRepository;
    private final TransactionProcedureDao procedureDao;
    private final CategoryRepository categoryRepository;
    private final TransactionMapper transactionMapper;
    private final EncryptionService encryptionService;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionProcedureDao procedureDao,
                              CategoryRepository categoryRepository,
                              TransactionMapper transactionMapper,
                              EncryptionService encryptionService) {
        this.transactionRepository = transactionRepository;
        this.procedureDao = procedureDao;
        this.categoryRepository = categoryRepository;
        this.transactionMapper = transactionMapper;
        this.encryptionService = encryptionService;
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> listTransactions(AuthenticatedUser principal,
                                                      LocalDate from,
                                                      LocalDate to,
                                                      boolean includeDeleted) {
        LocalDate effectiveFrom = from != null ? from : EARLIEST_DATE;
        LocalDate effectiveTo = to != null ? to : today();

        if (effectiveFrom.isAfter(effectiveTo)) {

            throw new RequestValidationException("The start of the date range must not be after "
                    + "the end of it.",
                    List.of(new ApiError.FieldError("from",
                            "The start date must not be after the end date.")));
        }

        return transactionRepository
                .findForStudent(principal.userId(), includeDeleted, effectiveFrom, effectiveTo)
                .stream()
                .map(transactionMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(AuthenticatedUser principal, Long transactionId) {
        return transactionMapper.toResponse(requireActiveTransaction(principal, transactionId));
    }

    @Transactional
    public TransactionResponse create(AuthenticatedUser principal, CreateTransactionRequest request) {
        Long userId = principal.userId();

        Category category = requireUsableCategory(userId, request.categoryId());
        requireActiveCategory(category);
        requireNotInTheFuture(request.txnDate());

        Transaction transaction = Transaction.newManual(
                userId,
                category,
                request.amount(),
                request.txnDate(),
                encryptionService.encrypt(trimToNull(request.description())));

        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, WriteOperation.CREATE);
        }

        log.info("Transaction created userId={} transactionId={} categoryId={}",
                userId, transaction.getId(), category.getId());
        return transactionMapper.toResponse(transaction);
    }

    @Transactional
    public TransactionResponse update(AuthenticatedUser principal, Long transactionId,
                                      UpdateTransactionRequest request) {
        Transaction transaction = requireActiveTransactionForUpdate(principal, transactionId);

        if (request.categoryId() != null
                && !request.categoryId().equals(transaction.getCategory().getId())) {
            Category category = requireUsableCategory(principal.userId(), request.categoryId());
            requireActiveCategory(category);
            transaction.setCategory(category);
        }
        if (request.amount() != null) {
            transaction.setAmount(request.amount());
        }
        if (request.txnDate() != null) {
            requireNotInTheFuture(request.txnDate());
            transaction.setTxnDate(request.txnDate());
        }
        if (request.description() != null) {
            transaction.setDescription(encryptionService.encrypt(trimToNull(request.description())));
        }

        try {

            transactionRepository.saveAndFlush(transaction);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, principal.userId(), WriteOperation.UPDATE);
        }

        log.info("Transaction updated userId={} transactionId={}",
                principal.userId(), transactionId);
        return transactionMapper.toResponse(transaction);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, Long transactionId) {
        Transaction transaction = requireAnyTransactionForUpdate(principal, transactionId);

        if (Boolean.TRUE.equals(transaction.getIsDeleted())) {
            throw TransactionStateException.alreadyDeleted();
        }

        procedureDao.softDelete(transactionId, principal.userId());
        log.info("Transaction soft-deleted userId={} transactionId={}",
                principal.userId(), transactionId);
    }

    @Transactional
    public TransactionResponse restore(AuthenticatedUser principal, Long transactionId) {
        Transaction transaction = requireAnyTransactionForUpdate(principal, transactionId);

        if (!Boolean.TRUE.equals(transaction.getIsDeleted())) {
            throw TransactionStateException.notDeleted();
        }

        procedureDao.restore(transaction, principal.userId());
        log.info("Transaction restored userId={} transactionId={}",
                principal.userId(), transactionId);
        return transactionMapper.toResponse(transaction);
    }

    private enum WriteOperation {
        CREATE,
        UPDATE
    }

    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId,
                                                   WriteOperation operation) {
        if (TransactionWriteFailure.isSignalledRefusal(ex)) {
            log.info("Transaction write rejected by a trigger userId={} op={}", userId, operation);
            return new DataConflictException(
                    "The transaction could not be saved because the data it depends on changed. "
                            + "Refresh and try again.");
        }

        if (TransactionWriteFailure.isConstraintViolation(ex)) {
            log.info("Transaction write rejected by a constraint userId={} op={}", userId, operation);
            return new DataConflictException(
                    "The transaction could not be saved because it conflicts with an existing "
                            + "record.");
        }

        log.error("Transaction write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    private Transaction requireActiveTransaction(AuthenticatedUser principal, Long transactionId) {
        return transactionRepository
                .findActiveByIdAndUserId(transactionId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Transaction not found."));
    }

    private Transaction requireActiveTransactionForUpdate(AuthenticatedUser principal,
                                                          Long transactionId) {
        return transactionRepository
                .findActiveByIdAndUserIdForUpdate(transactionId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Transaction not found."));
    }

    private Transaction requireAnyTransactionForUpdate(AuthenticatedUser principal,
                                                       Long transactionId) {
        return transactionRepository
                .findAnyByIdAndUserIdForUpdate(transactionId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Transaction not found."));
    }

    private Category requireUsableCategory(Long userId, Long categoryId) {
        return categoryRepository.findVisibleById(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be used for new records.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    private void requireNotInTheFuture(LocalDate txnDate) {
        if (txnDate.isAfter(today())) {
            throw new RequestValidationException(
                    "A transaction cannot be dated in the future.",
                    List.of(new ApiError.FieldError("txnDate",
                            "Choose today or an earlier date.")));
        }
    }

    private static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final LocalDate EARLIEST_DATE = LocalDate.of(1000, 1, 1);
}
