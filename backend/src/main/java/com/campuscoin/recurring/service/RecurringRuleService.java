package com.campuscoin.recurring.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import com.campuscoin.common.exception.CategoryRetiredException;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RecurringRuleEndedException;
import com.campuscoin.common.exception.RecurringRuleInUseException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.recurring.dto.CreateRecurringRuleRequest;
import com.campuscoin.recurring.dto.RecurringRuleResponse;
import com.campuscoin.recurring.dto.UpdateRecurringRuleRequest;
import com.campuscoin.recurring.entity.RecurringRule;
import com.campuscoin.recurring.entity.RecurringStatus;
import com.campuscoin.recurring.mapper.RecurringRuleMapper;
import com.campuscoin.recurring.repository.RecurringRuleRepository;

@Service
public class RecurringRuleService {

    private static final Logger log = LoggerFactory.getLogger(RecurringRuleService.class);

    private static final int DEFAULT_INTERVAL = 1;

    private final RecurringRuleRepository recurringRuleRepository;
    private final CategoryRepository categoryRepository;
    private final RecurringRuleMapper recurringRuleMapper;
    private final EncryptionService encryptionService;

    public RecurringRuleService(RecurringRuleRepository recurringRuleRepository,
                                CategoryRepository categoryRepository,
                                RecurringRuleMapper recurringRuleMapper,
                                EncryptionService encryptionService) {
        this.recurringRuleRepository = recurringRuleRepository;
        this.categoryRepository = categoryRepository;
        this.recurringRuleMapper = recurringRuleMapper;
        this.encryptionService = encryptionService;
    }

    @Transactional(readOnly = true)
    public List<RecurringRuleResponse> listRules(AuthenticatedUser principal) {
        return recurringRuleRepository.findForStudent(principal.userId()).stream()
                .map(recurringRuleMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecurringRuleResponse getRule(AuthenticatedUser principal, Long ruleId) {
        return recurringRuleMapper.toResponse(requireOwnRule(principal, ruleId));
    }

    @Transactional
    public RecurringRuleResponse create(AuthenticatedUser principal,
                                        CreateRecurringRuleRequest request) {
        Long userId = principal.userId();

        Category category = requireUsableCategory(userId, request.categoryId());
        requireActiveCategory(category);

        LocalDate startDate = request.startDate();
        LocalDate endDate = parseOptionalDate(request.endDate(), "endDate");
        LocalDate nextRunDate = request.nextRunDate() != null ? request.nextRunDate() : startDate;

        requireDatesAgree(startDate, endDate, nextRunDate);

        RecurringRule rule = RecurringRule.newRule(
                userId,
                category,
                request.amount(),
                encryptionService.encrypt(trimToNull(request.description())),
                request.frequency(),
                request.intervalCount() != null ? request.intervalCount() : DEFAULT_INTERVAL,
                startDate,
                endDate,
                nextRunDate);

        try {
            recurringRuleRepository.saveAndFlush(rule);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, WriteOperation.CREATE);
        }

        log.info("Recurring rule created userId={} ruleId={} categoryId={} frequency={}",
                userId, rule.getId(), category.getId(), rule.getFrequency());
        return recurringRuleMapper.toResponse(rule);
    }

    @Transactional
    public RecurringRuleResponse update(AuthenticatedUser principal, Long ruleId,
                                        UpdateRecurringRuleRequest request) {
        RecurringRule rule = requireOwnRuleForUpdate(principal, ruleId);

        boolean moving = request.categoryId() != null
                && !request.categoryId().equals(rule.getCategory().getId());

        if (moving) {
            Category category = requireUsableCategory(principal.userId(), request.categoryId());
            requireActiveCategory(category);
            rule.setCategory(category);
        } else if (Boolean.FALSE.equals(rule.getCategory().getIsActive())) {

            throw new CategoryRetiredException(
                    "This rule's category has been retired, so the rule cannot be changed. Enable "
                            + "the category, or move the rule to one that is still in use.");
        }

        if (request.amount() != null) {
            rule.setAmount(request.amount());
        }
        if (request.description() != null) {
            rule.setDescription(encryptionService.encrypt(trimToNull(request.description())));
        }
        if (request.frequency() != null) {
            rule.setFrequency(request.frequency());
        }
        if (request.intervalCount() != null) {
            rule.setIntervalCount(request.intervalCount());
        }
        if (request.nextRunDate() != null) {
            rule.setNextRunDate(request.nextRunDate());
        }
        if (request.status() != null) {
            requireEndableState(rule, request.status());
            rule.setStatus(request.status());
        }

        LocalDate endDate = rule.getEndDate();
        if (request.endDate() != null) {
            endDate = parseOptionalDate(request.endDate(), "endDate");
            rule.setEndDate(endDate);
        }
        requireDatesAgree(rule.getStartDate(), endDate, rule.getNextRunDate());

        try {
            recurringRuleRepository.saveAndFlush(rule);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, principal.userId(), WriteOperation.UPDATE);
        }

        log.info("Recurring rule updated userId={} ruleId={} status={}",
                principal.userId(), ruleId, rule.getStatus());
        return recurringRuleMapper.toResponse(rule);
    }

    @Transactional
    public void delete(AuthenticatedUser principal, Long ruleId) {
        RecurringRule rule = requireOwnRuleForUpdate(principal, ruleId);

        long generated = recurringRuleRepository.countTransactionsGeneratedBy(ruleId);
        if (generated > 0) {
            throw new RecurringRuleInUseException(
                    "This rule has already generated " + generated + " transaction"
                            + (generated == 1 ? "" : "s") + ", so it cannot be deleted. End it "
                            + "instead: it will stop posting and the transactions it created stay "
                            + "readable and editable.");
        }

        try {
            recurringRuleRepository.delete(rule);
            recurringRuleRepository.flush();
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, principal.userId(), WriteOperation.DELETE);
        }

        log.info("Recurring rule deleted userId={} ruleId={}", principal.userId(), ruleId);
    }

    private enum WriteOperation {
        CREATE,
        UPDATE,
        DELETE
    }

    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId,
                                                  WriteOperation operation) {
        if (RecurringRuleWriteFailure.isSignalledRefusal(ex)) {
            log.info("Recurring rule write rejected by a trigger userId={} op={}", userId, operation);
            return new DataConflictException(
                    "The recurring rule could not be saved because the data it depends on changed. "
                            + "Refresh and try again.");
        }

        if (RecurringRuleWriteFailure.isConstraintViolation(ex)) {
            log.info("Recurring rule write rejected by a constraint userId={} op={}", userId, operation);
            return new DataConflictException(
                    "The recurring rule could not be saved because it conflicts with an existing "
                            + "record.");
        }

        log.error("Recurring rule write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    private RecurringRule requireOwnRule(AuthenticatedUser principal, Long ruleId) {
        return recurringRuleRepository.findByIdAndUserId(ruleId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Recurring rule not found."));
    }

    private RecurringRule requireOwnRuleForUpdate(AuthenticatedUser principal, Long ruleId) {
        return recurringRuleRepository.findByIdAndUserIdForUpdate(ruleId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Recurring rule not found."));
    }

    private Category requireUsableCategory(Long userId, Long categoryId) {
        return categoryRepository.findVisibleById(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

    private void requireEndableState(RecurringRule rule, RecurringStatus requested) {
        if (rule.getStatus() == RecurringStatus.ENDED && requested != RecurringStatus.ENDED) {
            throw new RecurringRuleEndedException(
                    "This rule has ended, and an ended rule cannot be restarted. Create a new rule "
                            + "if the schedule is needed again.");
        }
    }

    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be used for a new recurring rule.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    private void requireDatesAgree(LocalDate startDate, LocalDate endDate, LocalDate nextRunDate) {
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new RequestValidationException(
                    "The end date cannot be before the start date.",
                    List.of(new ApiError.FieldError("endDate",
                            "Choose an end date on or after the start date.")));
        }
        if (endDate != null && nextRunDate.isAfter(endDate)) {
            throw new RequestValidationException(
                    "The next occurrence cannot be after the end date, or the rule would never post "
                            + "anything.",
                    List.of(new ApiError.FieldError("nextRunDate",
                            "Choose a next occurrence on or before the end date, or remove the end "
                                    + "date.")));
        }
    }

    private LocalDate parseOptionalDate(String value, String fieldName) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ex) {
            throw new RequestValidationException(
                    "The " + fieldName + " is not a valid date.",
                    List.of(new ApiError.FieldError(fieldName,
                            "Enter a real date in yyyy-MM-dd form, or leave it empty.")));
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
