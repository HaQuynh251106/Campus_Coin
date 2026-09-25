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

/**
 * Monthly spending limits: setting them, changing them and removing them (UC-13).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>One limit per student, category and month (BR-11)</b> - {@code uk_budget_user_cat_month}
 *       is {@code (user_id, category_id, period_month)} exactly. This class looks the collision up
 *       first so the caller gets a message naming the remedy, but the key is what enforces it.</li>
 *   <li><b>A budget is only ever on an expense category the caller may use and that is still active
 *       (BR-11, BR-02, BR-07)</b> - {@code sp_validate_budget}, reached through both triggers.</li>
 *   <li><b>The limit is positive and the month is the first of a month</b> -
 *       {@code ck_budget_limit}, {@code ck_budget_month}.</li>
 *   <li><b>How much has been spent, and what that is as a percentage</b> -
 *       {@code v_budget_consumption}, which is also the comparison the alert procedure makes. This
 *       class never sums a transaction.</li>
 *   <li><b>When an alert is raised</b> - {@code sp_check_budget_alerts}, called from the transaction
 *       triggers. See the note below.</li>
 * </ul>
 *
 * <p><b>Where alerts actually come from, and why this module has no "check" endpoint.</b> Writing a
 * budget does not raise an alert and must not: a limit is a target, and a target is not a thing that
 * gets exceeded. The alert is raised when a transaction pushes the month's spending past a threshold,
 * which is why {@code trg_transactions_after_insert} and {@code trg_transactions_after_update} call
 * {@code sp_check_budget_alerts} on every live transaction they write - including the ones the
 * recurring scheduler posts on the caller's behalf. So UC-14's alerts already fire today through
 * module 4 and module 5, and this module's job is to read them back and let the student mark them
 * read. There is deliberately no endpoint that recomputes alerts on demand: it would be a second
 * trigger for the same procedure, one that could write the {@code budget_alert_log} row that
 * {@code BR-12}'s uniqueness rule has already correctly decided against.
 *
 * <p><b>What is genuinely this class's.</b> Deciding which requests may proceed, resolving the
 * category from an id on the caller's behalf, turning a client's {@code yyyy-MM} into the
 * first-of-month date the column stores, and turning the database's four indistinct refusals into
 * errors a client can act on.
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    /**
     * The zone the application judges "the current month" in.
     *
     * <p>Deliberately the same {@code Asia/Ho_Chi_Minh} offset the database session is pinned to by
     * Hikari's {@code connection-init-sql} (VĐ-10) and that {@code TransactionService} uses for
     * BR-08. A budget defaults to the current month, so the two must agree: using the JVM's default
     * zone would make the API and {@code CURDATE()} name different months for seven hours in every
     * twenty-four, and a limit created at 06:00 on the first of the month could land in the previous
     * one.
     */
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

    /**
     * UC-13: the caller's limits for one month, with that month's spending.
     *
     * <p>{@code readOnly = true} documents that nothing is written - reading a budget never moves a
     * threshold, which is the whole point of the alert being triggered by transactions instead. The
     * account comes from the verified token and the method takes no user id, so there is no way to
     * ask for somebody else's limits.
     *
     * <p>{@code periodMonth} defaults to the current month. That is the month a student means when
     * they open a budgets screen, and it is also the month whose alerts are live, so an omitted value
     * answers the question the caller actually has.
     *
     * @throws RequestValidationException if the month is well-formed but names no real month
     */
    @Transactional(readOnly = true)
    public List<BudgetResponse> listBudgets(AuthenticatedUser principal, String periodMonth) {
        LocalDate month = resolvePeriodMonth(periodMonth, "periodMonth");
        return consumptionDao.findByUserAndMonth(principal.userId(), month).stream()
                .map(budgetMapper::toResponse)
                .toList();
    }

    /**
     * UC-13: read one of the caller's own limits.
     *
     * <p>Exists so a client can refresh a single row after a change without reloading the list, and
     * so the create and update responses have one shape to match. A budget belonging to another
     * student is not found, and neither is one that does not exist - the two are indistinguishable
     * from the outside, on purpose.
     *
     * @throws NotFoundException if the budget does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public BudgetResponse getBudget(AuthenticatedUser principal, Long budgetId) {
        return budgetMapper.toResponse(requireOwnConsumption(principal.userId(), budgetId));
    }

    /**
     * UC-13: set a limit for a category and month.
     *
     * <p>The category must be one of the caller's own or a shared default, must be an
     * <em>expense</em> category (BR-11), and must not have been retired (BR-07). The owner is the
     * account in the token - there is no field for one.
     *
     * <p>{@code periodMonth} is optional and defaults to the current month. Whatever is sent is
     * turned into the first day of that month, which is what {@code ck_budget_month} requires and
     * what the consumption view joins on.
     *
     * <p>Setting a limit raises no alert, and that is correct rather than an omission: see the note
     * on this class. What the response carries is the consumption <em>so far</em>, computed by the
     * database, so a student who sets a 300 limit in a month where they have already spent 250 sees
     * {@code NEAR} immediately - without an alert being written, because the alert belongs to the
     * transaction that crossed the threshold, not to the limit's creation.
     *
     * @throws NotFoundException            if the category does not exist or is not one the caller
     *                                      may use
     * @throws RequestValidationException   if the category is an income category, is retired, or the
     *                                      month names no real month
     * @throws BudgetAlreadyExistsException if a limit already exists for that category and month
     */
    @Transactional
    public BudgetResponse create(AuthenticatedUser principal, CreateBudgetRequest request) {
        Long userId = principal.userId();

        Category category = requireUsableCategory(userId, request.categoryId());
        requireExpenseCategory(category);
        requireActiveCategory(category);

        LocalDate periodMonth = resolvePeriodMonth(request.periodMonth(), "periodMonth");

        budgetRepository.findExistingId(userId, category.getId(), periodMonth).ifPresent(existingId -> {
            // BR-11 and uk_budget_user_cat_month: at most one. Reported with the id of the row that
            // already holds the limit, so the client can offer to change it rather than guess. The
            // existing limit is deliberately not quoted back: this endpoint was not asked for it, and
            // reading it would be a second query to make a message longer.
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

        // Read back from the view so the response carries the month's consumption, which the entity
        // cannot: it holds the limit and nothing else.
        return budgetMapper.toResponse(requireOwnConsumption(userId, budget.getId()));
    }

    /**
     * UC-13: change a limit.
     *
     * <p>{@code limitAmount} is the only field the request carries, because it is the only part of a
     * budget a student can meaningfully change - see {@link UpdateBudgetRequest} for why the
     * category and the month are not editable. A request that sends nothing changes nothing and
     * returns the budget as it stands.
     *
     * <p>The row is locked for the duration, so the value written is the one decided against the row
     * as it stands rather than as it stood when the request began. Two concurrent edits - "set it to
     * 300" and "set it to 350" - therefore settle on one of the two rather than both reporting
     * success and leaving whichever wrote last.
     *
     * <p><b>Changing a limit downwards does not retroactively raise or clear an alert, and that is
     * the database's design rather than a gap.</b> {@code sp_check_budget_alerts} runs when a
     * transaction is written, and {@code budget_alert_log}'s unique key means a threshold that has
     * fired will not fire again this month. Lowering a limit below what has already been spent makes
     * the budget read {@code EXCEEDED} immediately, because the status is recomputed from the view on
     * every read - but no second {@code BUDGET_EXCEEDED} notification appears, which is exactly what
     * BR-12 requires ("alert once per threshold per month"). The student sees the new state on the
     * screen; they do not get a duplicate message.
     *
     * @throws NotFoundException if the budget does not exist or is not the caller's
     */
    @Transactional
    public BudgetResponse update(AuthenticatedUser principal, Long budgetId,
                                 UpdateBudgetRequest request) {
        Budget budget = requireOwnBudgetForUpdate(principal.userId(), budgetId);

        // BR-07's freeze, checked before the write so the caller is told which rule refused them and
        // what to do about it. trg_budgets_before_update calls sp_validate_budget on every UPDATE, so
        // a budget whose category was retired after the limit was set cannot be changed at all -
        // the trigger would refuse it and translateWriteFailure would report the same 409 with a
        // generic "the data changed" message that names no remedy. Module 5 pre-checks the identical
        // constraint on recurring rules for that reason; this is the same check, for the same rule.
        //
        // Only update needs it. A delete fires no BEFORE UPDATE trigger, so a limit on a retired
        // category can still be removed - which is right, because removing it is the student's way
        // out of the state without touching the category.
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

    /**
     * UC-13: remove a limit.
     *
     * <p>Unlike a recurring rule, a budget can always be removed. Nothing depends on it: the spend it
     * measured belongs to the transactions, not to the limit, and the alert history that referenced
     * it goes with it - {@code budget_alert_log} cascades on the budget's delete - which is the
     * honest outcome, since the notifications those alerts produced are the student's own record and
     * are left alone.
     *
     * <p>Removing a limit does not remove the notifications that were already sent about it. That is
     * deliberate: a notification is a message the student received, and deleting the limit it was
     * about should not erase the fact that they were told.
     *
     * @throws NotFoundException if the budget does not exist or is not the caller's
     */
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

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** Which call site is translating a failure, since the same refusal means different things. */
    private enum WriteOperation {
        CREATE,
        UPDATE,
        DELETE
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>Which rule fired is decided by {@link BudgetWriteFailure}, which asks by SQLSTATE rather
     * than by matching the driver's message. Anything it does not recognise is rethrown unchanged,
     * so {@code GlobalExceptionHandler} answers it as an internal error rather than this method
     * mislabelling it.
     *
     * <p><b>Why a signalled refusal becomes {@code 409} rather than a field error.</b> The three
     * rules {@code sp_validate_budget} can signal about that the caller can see - the category
     * exists and is theirs, it is an expense category, it is active - are all checked before the
     * write, so reaching this branch means one of them stopped holding by the time the row was
     * written: the category was retired or deleted by a concurrent request. Naming a field would be
     * guessing which it was; {@code 409} with a message saying the data changed underneath the
     * request is the accurate answer.
     *
     * <p>The one exception is the BR-11 unique key, which is checked before the write on every path
     * this class can see. Reaching it means two requests for the same new limit raced, which the
     * unique key correctly refused - so that case is still reported as
     * {@link BudgetAlreadyExistsException}, with the same remedy as the pre-checked collision.
     *
     * <p>The exception is deliberately not logged. A trigger's {@code SIGNAL} text names the rule and
     * the table it guards, and MySQL's constraint messages name the table, the column and the value
     * that collided - all internal identifiers the response already withholds.
     */
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

        // Not a recognised refusal, so it is a genuine fault rather than a rule doing its job.
        // Logged in full and answered as an internal error by the handler.
        log.error("Budget write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    /**
     * Loads one of the caller's own budgets with its consumption, without locking it.
     *
     * <p>The read path's lookup, and the shape every response is built from. Reading the view rather
     * than the entity is what lets the create and update responses carry the month's spending without
     * a second query for it.
     *
     * @throws NotFoundException if the row does not exist or belongs to another student
     */
    private BudgetConsumption requireOwnConsumption(Long userId, Long budgetId) {
        return consumptionDao.findOne(userId, budgetId)
                .orElseThrow(() -> new NotFoundException("Budget not found."));
    }

    /**
     * Loads one of the caller's own budgets, with the row locked for the rest of the transaction.
     *
     * <p>Used by update and delete. The lock is what makes two concurrent edits settle on one value
     * instead of both reporting success from a lock-free read and leaving whichever wrote last - see
     * {@link #update}.
     *
     * @throws NotFoundException if the row does not exist or belongs to another student
     */
    private Budget requireOwnBudgetForUpdate(Long userId, Long budgetId) {
        return budgetRepository.findByIdAndUserIdForUpdate(budgetId, userId)
                .orElseThrow(() -> new NotFoundException("Budget not found."));
    }

    /**
     * Loads a category the caller may limit: their own, or a shared default.
     *
     * <p>{@code findVisibleById} is the query that expresses this - the same set {@code GET
     * /categories} returns, by id - so a category belonging to another student is not found rather
     * than found-and-refused. The database reaches the same conclusion through
     * {@code sp_validate_budget}; the check is here so the caller receives a {@code 404} naming the
     * field instead of an undifferentiated refusal.
     *
     * @throws NotFoundException if the category does not exist or belongs to another student
     */
    private Category requireUsableCategory(Long userId, Long categoryId) {
        return categoryRepository.findVisibleById(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

    /**
     * BR-11: a budget may only be set on an expense category.
     *
     * <p>Answered as a field error naming {@code categoryId}, because the caller can fix it by
     * choosing another category - the same reasoning the recurring-rule module applies to a retired
     * category it was asked to use. {@code sp_validate_budget} enforces the same rule; checking it
     * here is what turns its undifferentiated {@code SQLSTATE '45000'} into a message pointing at the
     * field.
     */
    private void requireExpenseCategory(Category category) {
        if (category.getType() != CategoryType.EXPENSE) {
            throw new RequestValidationException(
                    "A spending limit can only be set on an expense category.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose an expense category. Income categories cannot have a limit.")));
        }
    }

    /**
     * BR-07: a retired category is not offered as the target of a new limit.
     *
     * <p>Only the create path can reach this: the update path has no way to change a budget's
     * category, so there is no way for an update to move a limit onto a retired one. A budget whose
     * category is retired later stays editable, because editing its limit does not choose a category
     * again - the same distinction module 4 draws for a transaction filed under a category retired
     * afterwards.
     */
    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be given a new spending limit.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    /**
     * Reads an optional {@code yyyy-MM} month, defaulting to the current one.
     *
     * <p>The {@code @Pattern} on the DTOs guarantees the shape, so the only values reaching here are
     * well-formed strings that may still name a month that does not exist - {@code 2026-13} matches
     * the pattern. That case is why this method exists rather than a bare
     * {@code YearMonth.parse}: it is a validation failure, and it is reported as one, naming the
     * field.
     *
     * @param month     the raw field value, or null when absent
     * @param fieldName the field to name in the error, so the client can point at the input
     * @return the first day of the resolved month
     * @throws RequestValidationException if the value is well-formed but names no real month
     */
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
