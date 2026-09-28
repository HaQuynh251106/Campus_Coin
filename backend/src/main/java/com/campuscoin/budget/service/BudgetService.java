package com.campuscoin.budget.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.budget.dto.BudgetResponse;
import com.campuscoin.budget.dto.CreateBudgetRequest;
import com.campuscoin.budget.dto.UpdateBudgetRequest;
import com.campuscoin.budget.entity.Budget;
import com.campuscoin.budget.entity.BudgetConsumption;
import com.campuscoin.budget.mapper.BudgetMapper;
import com.campuscoin.budget.repository.BudgetConsumptionDao;
import com.campuscoin.budget.repository.BudgetRepository;
import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.category.repository.CategoryRepository;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.BudgetAlreadyExistsException;
import com.campuscoin.common.exception.CategoryRetiredException;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;

@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BudgetRepository budgetRepository;
    private final BudgetConsumptionDao consumptionDao;
    private final CategoryRepository categoryRepository;
    private final BudgetMapper budgetMapper;

    public BudgetService(BudgetRepository budgetRepository,
                         BudgetConsumptionDao consumptionDao,
                         CategoryRepository categoryRepository,
                         BudgetMapper budgetMapper) {
        this.budgetRepository = budgetRepository;
        this.consumptionDao = consumptionDao;
        this.categoryRepository = categoryRepository;
        this.budgetMapper = budgetMapper;
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> listBudgets(AuthenticatedUser principal, String periodMonth) {
        LocalDate month = resolvePeriodMonth(periodMonth, "periodMonth");
        return consumptionDao.findByUserAndMonth(principal.userId(), month).stream()
                .map(budgetMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BudgetResponse getBudget(AuthenticatedUser principal, Long budgetId) {
        return budgetMapper.toResponse(requireOwnConsumption(principal.userId(), budgetId));
    }

    @Transactional
    public BudgetResponse create(AuthenticatedUser principal, CreateBudgetRequest request) {
        Long userId = principal.userId();

        Category category = requireUsableCategory(userId, request.categoryId());
        requireExpenseCategory(category);
        requireActiveCategory(category);

        LocalDate periodMonth = resolvePeriodMonth(request.periodMonth(), "periodMonth");

        budgetRepository.findExistingId(userId, category.getId(), periodMonth).ifPresent(existingId -> {

            throw new BudgetAlreadyExistsException(
                    "A spending limit already exists for this category in "
                            + budgetMapper.toMonthString(periodMonth) + " (budget " + existingId
                            + "). Change it instead of creating a second one.");
        });

        Budget budget = Budget.newBudget(userId, category, periodMonth, request.limitAmount());

        try {
            budgetRepository.saveAndFlush(budget);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, WriteOperation.CREATE);
        }

        log.info("Budget created userId={} budgetId={} categoryId={} periodMonth={}",
                userId, budget.getId(), category.getId(), periodMonth);

        return budgetMapper.toResponse(requireOwnConsumption(userId, budget.getId()));
    }

    @Transactional
    public BudgetResponse update(AuthenticatedUser principal, Long budgetId,
                                 UpdateBudgetRequest request) {
        Budget budget = requireOwnBudgetForUpdate(principal.userId(), budgetId);

        if (Boolean.FALSE.equals(budget.getCategory().getIsActive())) {
            throw new CategoryRetiredException(
                    "This budget's category has been retired, so the limit cannot be changed. Restore "
                            + "the category, or remove the budget if it is no longer needed.");
        }

        if (request.limitAmount() != null) {
            budget.setLimitAmount(request.limitAmount());
        }

        try {
            budgetRepository.saveAndFlush(budget);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, principal.userId(), WriteOperation.UPDATE);
        }

        log.info("Budget updated userId={} budgetId={}", principal.userId(), budgetId);

        return budgetMapper.toResponse(requireOwnConsumption(principal.userId(), budgetId));
    }

    @Transactional
    public void delete(AuthenticatedUser principal, Long budgetId) {
        Budget budget = requireOwnBudgetForUpdate(principal.userId(), budgetId);

        try {
            budgetRepository.delete(budget);
            budgetRepository.flush();
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, principal.userId(), WriteOperation.DELETE);
        }

        log.info("Budget deleted userId={} budgetId={}", principal.userId(), budgetId);
    }

    private enum WriteOperation {
        CREATE,
        UPDATE,
        DELETE
    }

    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId,
                                                   WriteOperation operation) {
        if (BudgetWriteFailure.mentionsDuplicateLimit(ex)) {
            log.info("Budget write rejected as a duplicate userId={} op={}", userId, operation);
            return new BudgetAlreadyExistsException(
                    "A spending limit already exists for this category and month. Change the "
                            + "existing one instead of creating a second one.");
        }

        if (BudgetWriteFailure.isSignalledRefusal(ex)) {
            log.info("Budget write rejected by a trigger userId={} op={}", userId, operation);
            return new DataConflictException(
                    "The budget could not be saved because the data it depends on changed. Refresh "
                            + "and try again.");
        }

        if (BudgetWriteFailure.isConstraintViolation(ex)) {
            log.info("Budget write rejected by a constraint userId={} op={}", userId, operation);
            return new DataConflictException(
                    "The budget could not be saved because it conflicts with an existing record.");
        }

        log.error("Budget write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    private BudgetConsumption requireOwnConsumption(Long userId, Long budgetId) {
        return consumptionDao.findOne(userId, budgetId)
                .orElseThrow(() -> new NotFoundException("Budget not found."));
    }

    private Budget requireOwnBudgetForUpdate(Long userId, Long budgetId) {
        return budgetRepository.findByIdAndUserIdForUpdate(budgetId, userId)
                .orElseThrow(() -> new NotFoundException("Budget not found."));
    }

    private Category requireUsableCategory(Long userId, Long categoryId) {
        return categoryRepository.findVisibleById(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

    private void requireExpenseCategory(Category category) {
        if (category.getType() != CategoryType.EXPENSE) {
            throw new RequestValidationException(
                    "A spending limit can only be set on an expense category.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose an expense category. Income categories cannot have a limit.")));
        }
    }

    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be given a new spending limit.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    private LocalDate resolvePeriodMonth(String month, String fieldName) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now(APPLICATION_ZONE)).atDay(1);
        }
        try {
            return YearMonth.parse(month.trim()).atDay(1);
        } catch (RuntimeException ex) {
            throw new RequestValidationException(
                    "The month is not a valid month.",
                    List.of(new ApiError.FieldError(fieldName,
                            "Enter a real month in yyyy-MM form, for example 2026-09.")));
        }
    }
}
