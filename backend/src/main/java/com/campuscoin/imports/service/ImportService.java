package com.campuscoin.imports.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.auth.security.TokenHashService;
import com.campuscoin.categorisation.entity.CategoryRuleRow;
import com.campuscoin.categorisation.repository.CategoryRuleDao;
import com.campuscoin.category.entity.Category;
import com.campuscoin.common.crypto.EncryptionService;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.imports.dto.ImportBatchListResponse;
import com.campuscoin.imports.dto.ImportBatchResponse;
import com.campuscoin.imports.dto.ImportRowResponse;
import com.campuscoin.imports.dto.SetImportRowCategoryRequest;
import com.campuscoin.imports.dto.UploadCsvRequest;
import com.campuscoin.imports.entity.ImportBatchRow;
import com.campuscoin.imports.entity.ImportRow;
import com.campuscoin.imports.mapper.ImportMapper;
import com.campuscoin.imports.repository.ImportProcedureDao;
import com.campuscoin.imports.repository.ImportViewDao;
import com.campuscoin.imports.repository.ImportWriteDao;
import com.campuscoin.imports.service.ImportDuplicateDetector.Candidate;
import com.campuscoin.imports.service.ImportPreviewer.PreviewedFile;
import com.campuscoin.imports.service.ImportPreviewer.RowVerdict;
import com.campuscoin.transaction.repository.TransactionDescriptionEncryptionDao;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    static final int DEFAULT_LIST_LIMIT = 20;

    static final int MAX_LIST_LIMIT = 100;

    static final int MAX_BATCHES = 300;

    private final ImportPreviewer importPreviewer;
    private final ImportCategoryResolver importCategoryResolver;
    private final ImportViewDao importViewDao;
    private final ImportWriteDao importWriteDao;
    private final ImportProcedureDao importProcedureDao;
    private final ImportRuleLearner importRuleLearner;
    private final CategoryRuleDao categoryRuleDao;
    private final ImportMapper importMapper;
    private final TokenHashService tokenHashService;
    private final EncryptionService encryptionService;
    private final TransactionDescriptionEncryptionDao descriptionEncryptionDao;

    public ImportService(ImportPreviewer importPreviewer,
                         ImportCategoryResolver importCategoryResolver,
                         ImportViewDao importViewDao,
                         ImportWriteDao importWriteDao,
                         ImportProcedureDao importProcedureDao,
                         ImportRuleLearner importRuleLearner,
                         CategoryRuleDao categoryRuleDao,
                         ImportMapper importMapper,
                         TokenHashService tokenHashService,
                         EncryptionService encryptionService,
                         TransactionDescriptionEncryptionDao descriptionEncryptionDao) {
        this.importPreviewer = importPreviewer;
        this.importCategoryResolver = importCategoryResolver;
        this.importViewDao = importViewDao;
        this.importWriteDao = importWriteDao;
        this.importProcedureDao = importProcedureDao;
        this.importRuleLearner = importRuleLearner;
        this.categoryRuleDao = categoryRuleDao;
        this.importMapper = importMapper;
        this.tokenHashService = tokenHashService;
        this.encryptionService = encryptionService;
        this.descriptionEncryptionDao = descriptionEncryptionDao;
    }

    @Transactional
    public ImportBatchResponse upload(AuthenticatedUser principal, UploadCsvRequest request) {
        Long userId = principal.userId();

        if (importViewDao.findBatches(userId, MAX_BATCHES).size() >= MAX_BATCHES) {
            throw new RequestValidationException(
                    "You have reached the most imports this account can hold.",
                    List.of(new ApiError.FieldError("content",
                            "This account already has " + MAX_BATCHES + " imports. They cannot be "
                                    + "deleted, so please use one of the imports you already have.")));
        }

        List<Category> visible = importCategoryResolver.visibleCategories(userId);
        List<CategoryRuleRow> rules = categoryRuleDao.findRules(userId);
        List<Candidate> existing = importViewDao.findExistingRecords(userId);

        PreviewedFile preview = importPreviewer.preview(request.content(), visible, rules, existing);

        long batchId = importWriteDao.createBatch(
                userId,
                request.filename(),
                tokenHashService.sha256Hex(request.content()),
                preview.totalRows(),
                preview.validRows(),
                preview.errorRows(),
                preview.duplicateRows());

        importWriteDao.insertRows(batchId, preview.drafts());

        log.info("CSV import previewed userId={} batchId={} rows={} valid={} errors={} duplicates={}",
                userId, batchId, preview.totalRows(), preview.validRows(), preview.errorRows(),
                preview.duplicateRows());

        return readBatch(userId, batchId);
    }

    @Transactional(readOnly = true)
    public ImportBatchListResponse list(AuthenticatedUser principal, int limit) {
        int applied = effectiveLimit(limit);
        List<ImportBatchRow> batches = importViewDao.findBatches(principal.userId(), applied);

        return importMapper.toListResponse(applied, batches);
    }

    @Transactional(readOnly = true)
    public ImportBatchResponse get(AuthenticatedUser principal, Long batchId) {
        return readBatch(principal.userId(), batchId);
    }

    @Transactional
    public ImportRowResponse setRowCategory(AuthenticatedUser principal, Long batchId, Long rowId,
                                            SetImportRowCategoryRequest request) {
        Long userId = principal.userId();

        ImportBatchRow batch = requireOpenBatch(userId, batchId);

        Category category = importCategoryResolver.findVisible(userId, request.categoryId())
                .orElseThrow(() -> new NotFoundException("Category not found."));
        requireActiveCategory(category);

        if (importWriteDao.setResolvedCategory(userId, batchId, rowId, category.getId()) == 0) {

            importViewDao.findRow(userId, batchId, rowId)
                    .orElseThrow(() -> new NotFoundException("Import row not found."));
            throw new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        applyVerdicts(userId, batchId);

        log.info("CSV import row filed userId={} batchId={} rowId={} categoryId={}",
                userId, batchId, rowId, category.getId());

        ImportRow row = importViewDao.findRow(userId, batchId, rowId)
                .orElseThrow(() -> new IllegalStateException(
                        "Import row " + rowId + " was not readable back after being filed; batch "
                                + batch.batchId() + " was open when it was written."));

        return importMapper.toRowResponse(row);
    }

    @Transactional
    public ImportBatchResponse commit(AuthenticatedUser principal, Long batchId) {
        Long userId = principal.userId();

        requireOpenBatch(userId, batchId);

        try {
            importProcedureDao.applyBatch(batchId);
        } catch (RuntimeException ex) {
            throw translateCommitFailure(ex, userId, batchId);
        }

        List<Category> visible = importCategoryResolver.visibleCategories(userId);
        List<ImportRow> rows = importViewDao.findRows(userId, batchId);

        reencryptImportedDescriptions(userId, rows);

        rows = importViewDao.findRows(userId, batchId);
        int learned = importRuleLearner.learn(userId, rows, visible);

        log.info("CSV import committed userId={} batchId={} learnedRules={}", userId, batchId, learned);

        return importMapper.toBatchResponse(
                requireBatch(userId, batchId), importViewDao.findRows(userId, batchId));
    }

    @Transactional
    public ImportBatchResponse cancel(AuthenticatedUser principal, Long batchId) {
        Long userId = principal.userId();

        requireBatch(userId, batchId);

        if (importWriteDao.cancelBatch(userId, batchId) == 0) {
            throw new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        log.info("CSV import cancelled userId={} batchId={}", userId, batchId);

        return readBatch(userId, batchId);
    }

    private ImportBatchResponse readBatch(Long userId, Long batchId) {
        return importMapper.toBatchResponse(
                requireBatch(userId, batchId), importViewDao.findRows(userId, batchId));
    }

    private ImportBatchRow requireBatch(Long userId, Long batchId) {
        return importViewDao.findBatch(userId, batchId)
                .orElseThrow(() -> new NotFoundException("Import not found."));
    }

    private ImportBatchRow requireOpenBatch(Long userId, Long batchId) {
        ImportBatchRow batch = requireBatch(userId, batchId);

        if (!batch.status().isOpen()) {
            throw new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        return batch;
    }

    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be used for new records.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    private void applyVerdicts(Long userId, Long batchId) {
        List<Category> visible = importCategoryResolver.visibleCategories(userId);
        List<Candidate> existing = importViewDao.findExistingRecords(userId);
        List<ImportRow> rows = importViewDao.findRows(userId, batchId);

        List<RowVerdict> verdicts = importPreviewer.verdicts(rows, visible, existing);
        for (RowVerdict verdict : verdicts) {
            importWriteDao.setRowVerdict(userId, batchId, verdict.rowId(), verdict.rowStatus(),
                    verdict.errorMessage());
        }

        importWriteDao.refreshBatchCounters(userId, batchId);
    }

    private void reencryptImportedDescriptions(Long userId, List<ImportRow> rows) {
        List<Long> transactionIds = new ArrayList<>();
        List<String> envelopes = new ArrayList<>();

        for (ImportRow row : rows) {
            if (row.transactionId() == null) {
                continue;
            }
            transactionIds.add(row.transactionId());
            envelopes.add(encryptionService.encrypt(row.parsedDescription()));
        }

        int rewritten = descriptionEncryptionDao.reencryptImportedDescriptions(
                userId, transactionIds, envelopes);

        if (rewritten != transactionIds.size()) {

            throw new IllegalStateException("CSV import commit rewrote " + rewritten + " of "
                    + transactionIds.size() + " imported descriptions; the batch and the table disagree.");
        }

        log.debug("CSV import descriptions re-encoded userId={} rows={}", userId, rewritten);
    }

    private int effectiveLimit(int limit) {
        if (limit > 0 && limit <= MAX_LIST_LIMIT) {
            return limit;
        }

        throw new RequestValidationException(
                "The requested number of imports is out of range.",
                List.of(new ApiError.FieldError("limit",
                        "Ask for between 1 and " + MAX_LIST_LIMIT + " imports. Omit the parameter to "
                                + "get " + DEFAULT_LIST_LIMIT + ".")));
    }

    private RuntimeException translateCommitFailure(RuntimeException ex, Long userId, Long batchId) {
        if (ImportWriteFailure.isSignalledRefusal(ex)) {
            log.info("CSV import commit refused userId={} batchId={}", userId, batchId);
            return new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        if (ImportWriteFailure.isConstraintViolation(ex)) {
            log.warn("CSV import commit rejected by a constraint userId={} batchId={}", userId, batchId);
            return new DataConflictException(
                    "This import is no longer open. Reload it to see its current state.");
        }

        log.error("CSV import commit failed unexpectedly userId={} batchId={}", userId, batchId, ex);
        return ex;
    }
}
