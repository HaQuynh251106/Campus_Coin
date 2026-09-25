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

/**
 * Recurring rules: setting up, editing, pausing, ending and removing a repeating income or expense
 * (UC-09).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>Which periods have been posted, and that each is posted once (BR-16)</b> -
 *       {@code sp_post_recurring_transactions} derives a period key per frequency and relies on
 *       {@code uk_occurrence_rule_period} to make a repeat impossible. This module never computes a
 *       period key, a due date or a catch-up range.</li>
 *   <li><b>The category must be usable, active and of the rule's type (BR-02, BR-05, BR-07)</b> -
 *       {@code sp_validate_recurring_rule}, reached through both triggers.</li>
 *   <li><b>The amount is positive, the interval is at least 1, the end date is not before the start
 *       date</b> - {@code ck_recurring_amount}, {@code ck_recurring_interval},
 *       {@code ck_recurring_dates}.</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> Deciding which requests may proceed, resolving the
 * category and the type from an id on the caller's behalf, and turning the database's refusals into
 * errors a client can act on.
 *
 * <p><b>Three checks are the application's, deliberately, and each is a stricter restatement of a
 * rule that already holds rather than a new rule.</b> They exist because the database's refusal
 * arrives as one undifferentiated signal - {@code sp_validate_recurring_rule} raises
 * {@code SQLSTATE '45000'} for four different reasons - so a client told only "the write was
 * refused" could not point at the field that needs fixing:
 *
 * <ol>
 *   <li><b>The category exists and is one the caller may use.</b> Answered as {@code 404}, so an id
 *       belonging to another student is indistinguishable from one that does not exist (section
 *       7.5).</li>
 *   <li><b>The resulting category is active</b> - BR-07. Which of the two answers this gets depends
 *       on whether the caller can fix it by changing this request, and that distinction is explained
 *       on {@link #update}.</li>
 *   <li><b>The rule's own dates agree</b> - see {@link #requireDatesAgree}. The CHECK constraints
 *       cover {@code end_date >= start_date}; the "next run must not be past the end date" rule is
 *       this class's, because no constraint expresses it and a rule that violates it would sit
 *       {@code ACTIVE} forever posting nothing.</li>
 * </ol>
 *
 * <p>None of the three is trusted in place of the database. Each is checked before the write and the
 * trigger still runs; a request that slips past a check is caught on the way out by
 * {@link #translateWriteFailure}.
 */
@Service
public class RecurringRuleService {

    private static final Logger log = LoggerFactory.getLogger(RecurringRuleService.class);

    /**
     * The schema's default for {@code interval_count}, applied when the client omits it.
     *
     * <p>Nothing in this class compares a date to "today". Which periods are due is
     * {@code sp_post_recurring_transactions}'s decision, made against the database's own clock in
     * the {@code +07:00} session the connection is pinned to (VĐ-10) - so a rule set up with a start
     * date in the past is not special-cased here, it is simply left for the scheduler to catch up
     * on. Keeping the judgement in one place is what stops the API and the scheduler disagreeing
     * about whether a period is due.
     */
    private static final int DEFAULT_INTERVAL = 1;

    private final RecurringRuleRepository recurringRuleRepository;
    private final CategoryRepository categoryRepository;
    private final RecurringRuleMapper recurringRuleMapper;

    public RecurringRuleService(RecurringRuleRepository recurringRuleRepository,
                                CategoryRepository categoryRepository,
                                RecurringRuleMapper recurringRuleMapper) {
        this.recurringRuleRepository = recurringRuleRepository;
        this.categoryRepository = categoryRepository;
        this.recurringRuleMapper = recurringRuleMapper;
    }

    /**
     * UC-09: the caller's rules, soonest to run first.
     *
     * <p>{@code readOnly = true} documents that nothing is written. The account comes from the
     * verified token and the method takes no user id, so there is no way to ask for somebody else's
     * rules.
     *
     * <p>Rules of every status are returned, including paused and ended ones. A paused rule is one
     * the student means to resume, so hiding it would leave no way to find it again; {@code status}
     * is in the response for the client to filter on. There is deliberately no status parameter - a
     * second way to ask the same question is how a duplicate endpoint starts.
     */
    @Transactional(readOnly = true)
    public List<RecurringRuleResponse> listRules(AuthenticatedUser principal) {
        return recurringRuleRepository.findForStudent(principal.userId()).stream()
                .map(recurringRuleMapper::toResponse)
                .toList();
    }

    /**
     * UC-09: read one of the caller's own rules.
     *
     * <p>Exists so a client can refresh a single row after a change without reloading the list. A
     * rule belonging to another student is not found, and neither is one that does not exist - the
     * two are indistinguishable from the outside, on purpose.
     *
     * @throws NotFoundException if the rule does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public RecurringRuleResponse getRule(AuthenticatedUser principal, Long ruleId) {
        return recurringRuleMapper.toResponse(requireOwnRule(principal, ruleId));
    }

    /**
     * UC-09: set up a rule owned by the caller.
     *
     * <p>The type is not sent and cannot be: it is the category's (BR-05), and the entity's factory
     * takes it from there. {@code status} is fixed at {@code ACTIVE}, so a rule cannot be created
     * already stopped.
     *
     * <p>{@code nextRunDate} is optional. Omitted, the first occurrence is the start date, which is
     * what a student setting up "my rent every month" expects; sending it schedules the first run
     * later than the start, which is how a rule is prepared in advance without firing immediately.
     * Either way the rule will not post anything until the scheduler next runs, and the scheduler
     * never posts a period later than the date it is given - see the note on
     * {@link #create}'s use of {@code startDate} in the API document.
     *
     * @throws NotFoundException          if the category does not exist or is not one the caller may
     *                                    use
     * @throws RequestValidationException if the category is retired, or the dates do not agree
     */
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
                trimToNull(request.description()),
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

    /**
     * UC-09: change some fields of one of the caller's own rules.
     *
     * <p>Only the fields actually present are touched, so a request that sends one field leaves the
     * others alone, and a request that changes nothing issues no {@code UPDATE} at all. Sending an
     * empty string for {@code description} clears it, and for {@code endDate} removes the rule's end
     * so it becomes open-ended. Leaving a field out, or sending null, leaves it as it is.
     *
     * <p><b>Pausing, resuming and ending are all this method, through {@code status}.</b> They are
     * not separate endpoints because they are not separate operations - each one sets the same
     * column - and a {@code /pause} route would be a second way to write it that could disagree with
     * this one. What makes them safe is that the row is locked for the duration, so a status decided
     * here is decided against a cursor the scheduler cannot move underneath the request.
     *
     * <p><b>The lifecycle is {@code ACTIVE} &#8596; {@code PAUSED}, either to {@code ENDED}, and
     * {@code ENDED} terminal.</b> A rule cannot be moved out of {@code ENDED}; that is refused as
     * {@code 409 RECURRING_RULE_ENDED}. Sending the status a rule already has is not refused - it
     * changes nothing.
     *
     * <p><b>An edit never rewrites the past, and this is UC-09 A2's boundary (BR-16).</b> The
     * amounts, descriptions, frequency and interval in this request apply to the occurrences the
     * scheduler has <em>not</em> posted yet. The transactions already generated keep the values that
     * were in force when they were posted, and so do the rows in
     * {@code recurring_occurrences} that tie them to their periods - the procedure's
     * {@code INSERT IGNORE} against {@code uk_occurrence_rule_period} makes a covered period
     * unrepeatable, so editing a rule cannot regenerate a period it already covered. That is the
     * whole reason {@code startDate} is not writable here: the covered periods are a function of it,
     * and moving it would put the rule and its own history into disagreement.
     *
     * <p>Consequently a change takes effect from the occurrence sitting at {@code nextRunDate}
     * onwards: that is the first period the scheduler has not covered yet, and every later one is
     * posted with the new values. There is deliberately no "apply to existing transactions" option -
     * those rows belong to the student, are editable one by one through module 4, and are part of
     * their recorded history rather than a projection of the rule.
     *
     * <p><b>Moving a rule between categories is how its type changes</b> (BR-05), and the type is
     * re-derived from the target category rather than accepted from the client. That is not merely
     * tidiness here: {@code trg_recurring_rules_before_update} re-runs the BR-05 comparison on
     * <em>every</em> update, so a move that left the old type in place would be refused by the
     * database. {@link RecurringRule#setCategory} sets both together, which makes that mistake
     * unrepresentable.
     *
     * <p><b>The retired-category rule is applied on every update, not only on a move, and that is a
     * database behaviour rather than a choice.</b> Unlike the transaction triggers, this table's
     * update trigger has no {@code require_active = 0} escape: it calls
     * {@code sp_validate_recurring_rule} with the new values unconditionally, so a rule whose
     * category has been retired cannot be updated <em>at all</em> - not its amount, not its
     * {@code status}, not even to end it. That is surprising enough to be worth stating plainly:
     * retiring a category in module 3 freezes the rules filed under it until it is enabled again.
     *
     * <p>Because the caller cannot fix that by editing a field of <em>this</em> request, it is
     * answered as a conflict rather than a field error - unless they are moving the rule to another
     * category, in which case they can fix it, and then the target category is what is checked and
     * the answer is a field error naming {@code categoryId}.
     *
     * @throws NotFoundException          if the rule or the target category is not the caller's
     * @throws RequestValidationException if the target category is retired, or the dates do not
     *                                    agree
     * @throws CategoryRetiredException   if the rule's own category is retired and the request does
     *                                    not move it
     */
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
            // The rule stays where it is, and its category has been retired. The database refuses
            // every write to this row in that state, so there is nothing this request could change -
            // see the note above. Reported as a conflict because no field the caller sent is wrong.
            throw new CategoryRetiredException(
                    "This rule's category has been retired, so the rule cannot be changed. Enable "
                            + "the category, or move the rule to one that is still in use.");
        }

        if (request.amount() != null) {
            rule.setAmount(request.amount());
        }
        if (request.description() != null) {
            rule.setDescription(trimToNull(request.description()));
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

        // The end date is the one field whose absence and whose clearing differ, so it is applied
        // last and the date rules are judged on the resulting triple rather than on what was sent.
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

    /**
     * UC-09: remove one of the caller's own rules.
     *
     * <p><b>Permitted only while the rule has posted nothing, and refused otherwise - which is the
     * most important behaviour in this module.</b> The database would allow the delete:
     * {@code transactions.recurring_rule_id} deliberately carries no foreign key (see
     * {@code docs/DB_DESIGN.md} section 4.8), so nothing cascades and nothing restricts. What it
     * would leave behind is not a harmless dangling reference but a broken record, because
     * {@code sp_validate_transaction} re-checks that reference on every write: a transaction whose
     * rule no longer exists cannot be edited, soft-deleted or restored again, each raising
     * {@code BR-02: recurring rule does not exist}. That would reach outside this module and break
     * the transaction endpoints for rows that were working before.
     *
     * <p>So the check is made here before the delete, and the answer is {@code 409} with the remedy
     * in the message: end the rule instead. {@code status = ENDED} stops it posting for good and
     * leaves everything it generated intact and editable, which is what "I don't want this any more"
     * actually needs. A rule that has never posted - one set up by mistake, or set up for a date
     * that has not arrived - is genuinely removable, and that is what this endpoint is for.
     *
     * <p>The count includes soft-deleted transactions on purpose. They still carry the pointer and
     * are still restored through the same trigger, so counting only live rows would report a rule as
     * removable and leave the restored record uneditable.
     *
     * @throws NotFoundException            if the rule does not exist or is not the caller's
     * @throws RecurringRuleInUseException  if the rule has already generated transactions
     */
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
     * <p>Which rule fired is decided by {@link RecurringRuleWriteFailure}, which asks by SQLSTATE
     * rather than by matching the driver's message. Anything it does not recognise is rethrown
     * unchanged, so {@code GlobalExceptionHandler} answers it as an internal error rather than this
     * method mislabelling it.
     *
     * <p><b>Why a signalled refusal becomes {@code 409} rather than a field error.</b> Every rule
     * {@code sp_validate_recurring_rule} can signal about is checked by the caller before the write -
     * including the retired-category rule, which {@link #update} answers precisely - so reaching
     * this branch means one of those checks no longer held by the time the row was written: the
     * category was retired or deleted by a concurrent request. Naming a field here would be guessing
     * which of them it was; {@code 409} with a message that says the data changed underneath the
     * request is the accurate answer. The individual rules are verified against the real exception
     * shapes in {@code RecurringRuleWriteFailureTest} instead.
     *
     * <p>The exception is deliberately not logged. A trigger's {@code SIGNAL} text names the rule
     * and the table it guards, and MySQL's constraint messages name the table, the column and the
     * value that collided - all internal identifiers the response already withholds.
     */
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

        // Not a recognised refusal, so it is a genuine fault rather than a rule doing its job.
        // Logged in full and answered as an internal error by the handler.
        log.error("Recurring rule write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    /**
     * Loads one of the caller's own rules, without locking it.
     *
     * <p>The read path's lookup. {@code GET} only reports what is there, so it takes no lock.
     *
     * @throws NotFoundException if the row does not exist or belongs to another student
     */
    private RecurringRule requireOwnRule(AuthenticatedUser principal, Long ruleId) {
        return recurringRuleRepository.findByIdAndUserId(ruleId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Recurring rule not found."));
    }

    /**
     * As {@link #requireOwnRule}, with the row locked for the rest of the transaction.
     *
     * <p>Used by update and delete. The lock matters because two state decisions made from
     * lock-free reads can both be made: pausing a rule and ending it could both see it
     * {@code ACTIVE} and both report success, with the second silently overwriting the first. It
     * matters more here than on most tables because the scheduler also writes this row - it advances
     * {@code next_run_date} and {@code last_run_date} - so the lock also keeps a status decision
     * from being made against a cursor that moves mid-request.
     */
    private RecurringRule requireOwnRuleForUpdate(AuthenticatedUser principal, Long ruleId) {
        return recurringRuleRepository.findByIdAndUserIdForUpdate(ruleId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Recurring rule not found."));
    }

    /**
     * Loads a category the caller may file rules under: their own, or a shared default.
     *
     * <p>{@code findVisibleById} is the query that expresses this - the same set
     * {@code GET /categories} returns, by id - so a category belonging to another student is not
     * found rather than found-and-refused. The database reaches the same conclusion through
     * {@code sp_validate_recurring_rule}; the check is here so the caller receives a {@code 404}
     * naming the field instead of an undifferentiated refusal.
     *
     * @throws NotFoundException if the category does not exist or belongs to another student
     */
    private Category requireUsableCategory(Long userId, Long categoryId) {
        return categoryRepository.findVisibleById(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

    /**
     * UC-09: the lifecycle is {@code ACTIVE} &#8596; {@code PAUSED}, either of those to
     * {@code ENDED}, and {@code ENDED} terminal.
     *
     * <p>Only the terminal half is enforced, because only the terminal half is a rule: the other
     * three transitions are all of the ways between the two live states, so nothing can violate
     * them. A request that sends the status the rule already has is allowed and changes nothing -
     * that is how a "save" that resends the unchanged value behaves, and refusing it would turn a
     * harmless no-op into an error.
     *
     * <p>Ending is idempotent for the same reason: an ended rule sent {@code ENDED} again is left
     * as it is. What is refused is moving <em>out</em> of {@code ENDED}, which is what the state
     * machine calls final. See {@link RecurringRuleEndedException} for why it is worth refusing:
     * reviving a rule whose cursor is in the past would post transactions for the period it was
     * ended for, which contradicts what ending it said.
     *
     * @throws RecurringRuleEndedException if the rule has ended and the request would change that
     */
    private void requireEndableState(RecurringRule rule, RecurringStatus requested) {
        if (rule.getStatus() == RecurringStatus.ENDED && requested != RecurringStatus.ENDED) {
            throw new RecurringRuleEndedException(
                    "This rule has ended, and an ended rule cannot be restarted. Create a new rule "
                            + "if the schedule is needed again.");
        }
    }

    /**
     * BR-07: a retired category is not offered for a new rule, and not for a rule being moved onto
     * it.
     *
     * <p>This is the answer for a category the caller <em>chose</em> - on create, or on a move -
     * because there they can fix it by picking another one, so the error names the field. A retired
     * category the rule is already filed under is a different situation and gets a different answer;
     * see {@link #update}.
     */
    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be used for a new recurring rule.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    /**
     * The rule's three dates must agree with each other.
     *
     * <p>Two rules, and they have different owners:
     *
     * <ul>
     *   <li><b>The end date is not before the start date.</b> {@code ck_recurring_dates} enforces
     *       this, and the service checks it too so the caller gets a field error rather than a
     *       refused write.</li>
     *   <li><b>The next run is not after the end date.</b> Nothing in the schema expresses this, and
     *       it is worth refusing: the scheduler's cursor condition is
     *       {@code next_run_date <= end_date}, so a rule in that state would match no run, never
     *       advance, and never be marked {@code ENDED} - it would sit {@code ACTIVE} for ever,
     *       visible to the student, posting nothing and giving no hint why. Refusing it here is
     *       cheaper than explaining it later.</li>
     * </ul>
     *
     * <p>A <em>past</em> end date is deliberately allowed. It is not a mistake: the scheduler
     * catches up, so a rule created with a start and end in the past posts every period it covered,
     * which is the documented A1 behaviour after downtime.
     *
     * @throws RequestValidationException if either rule is broken
     */
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

    /**
     * Reads an optional {@code yyyy-MM-dd} date from a request field.
     *
     * <p>The two DTOs send {@code endDate} as a string rather than as a date because it is the one
     * field in this module that can be <em>removed</em> - an empty string clears it - and a record of
     * nullable fields cannot tell "absent" from "explicit null". The {@code @Pattern} on the field
     * guarantees the shape, so the only values that reach here are well-formed strings that may name
     * a day that does not exist.
     *
     * <p>That last case is why this method exists rather than a bare {@code LocalDate.parse}: a
     * value such as {@code 2026-02-30} matches the pattern and would otherwise throw
     * {@code DateTimeParseException} out of the service and be answered as an internal error. It is
     * a validation failure, and it is reported as one, naming the field.
     *
     * @param value     the raw field value, or null when absent
     * @param fieldName the field to name in the error, so the client can point at the input
     * @return the parsed date, or null when the value is absent or empty
     * @throws RequestValidationException if the value is well-formed but not a real date
     */
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

    /** Trims, and turns a value that is empty after trimming into null. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
