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

/**
 * Recording, reading, editing, deleting and restoring a student's transactions (UC-07, UC-10).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b> Every rule UC-07 and
 * UC-10 depend on is already enforced by an object this module calls:
 *
 * <ul>
 *   <li><b>The date may not be in the future (BR-08)</b> - {@code sp_validate_transaction}, reached
 *       through {@code trg_transactions_before_insert} and {@code ..._before_update}.</li>
 *   <li><b>The category must be usable by this student (BR-02) and active (BR-07)</b> - the same
 *       procedure. It reads {@code categories.user_id} and refuses a category belonging to another
 *       student, which a foreign key could not: {@code fk_txn_category} proves the row exists, not
 *       who owns it.</li>
 *   <li><b>The amount must be positive</b> - {@code ck_txn_amount}.</li>
 *   <li><b>A transaction may not be hard-deleted (BR-09)</b> - {@code trg_transactions_before_delete}
 *       refuses every {@code DELETE} outright.</li>
 *   <li><b>The change history must be kept (BR-09)</b> - {@code trg_transactions_after_insert} and
 *       {@code trg_transactions_after_update} write {@code transaction_history} with the full
 *       before-and-after payload. Java never writes that table, so there is one writer.</li>
 *   <li><b>The delete and restore transitions</b> - {@code sp_soft_delete_transaction} and
 *       {@code sp_restore_transaction} own {@code is_deleted} and {@code deleted_at}, re-check
 *       ownership themselves, and refuse a state change that does not apply.</li>
 *   <li><b>Budget alerts (UC-14)</b> - raised by the same triggers. This module does not know that
 *       budgets exist.</li>
 * </ul>
 *
 * <p><b>What is genuinely this class's.</b> Deciding which requests may proceed, resolving the
 * category from an id on the caller's behalf, and turning the database's refusals into errors a
 * client can act on.
 *
 * <p><b>Three checks are the application's, deliberately, and each is a stricter restatement of a
 * rule that already holds rather than a new rule.</b> They exist because the database's refusal
 * arrives as one undifferentiated signal - {@code sp_validate_transaction} raises
 * {@code SQLSTATE '45000'} for six different reasons - so a client told only "the write was refused"
 * could not point at the field that needs fixing:
 *
 * <ol>
 *   <li><b>The category exists and is one the caller may use.</b> Answered as {@code 404}, so an id
 *       belonging to another student is indistinguishable from one that does not exist (section
 *       7.5).</li>
 *   <li><b>The category is active, on create.</b> BR-07 retires a category instead of deleting it;
 *       filing a new record under a retired one is exactly what retirement is meant to prevent. On
 *       update the same rule is applied only when the record is <em>moved</em> - see
 *       {@link #update} - because a record already filed under a category that was retired later
 *       must stay editable, which is what the trigger's {@code require_active = 0} allows.</li>
 *   <li><b>The date is not in the future.</b> BR-08. Judged in the application's zone, the same
 *       {@code +07:00} the database session runs in, so the two cannot disagree about which day it
 *       is.</li>
 * </ol>
 *
 * <p>None of the three is trusted in place of the database. Each is checked before the write and the
 * trigger still runs; a request that slips past a check is caught on the way out by
 * {@link #translateWriteFailure}.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /**
     * The zone the application judges "today" in.
     *
     * <p>Deliberately the same {@code +07:00} the database session is pinned to by Hikari's
     * {@code connection-init-sql} (VĐ-10). Using the server's default zone would make the API and
     * {@code CURDATE()} disagree about the current day for seven hours in every twenty-four, and
     * BR-08 is a comparison against the current day.
     */
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final TransactionRepository transactionRepository;
    private final TransactionProcedureDao procedureDao;
    private final CategoryRepository categoryRepository;
    private final TransactionMapper transactionMapper;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionProcedureDao procedureDao,
                              CategoryRepository categoryRepository,
                              TransactionMapper transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.procedureDao = procedureDao;
        this.categoryRepository = categoryRepository;
        this.transactionMapper = transactionMapper;
    }

    /**
     * UC-10: the caller's transactions, newest first, optionally within a date range.
     *
     * <p>{@code readOnly = true} documents that nothing is written. The account comes from the
     * verified token and the method takes no user id, so there is no way to ask for somebody else's
     * records.
     *
     * <p><b>Why the range has two concrete bounds rather than nullable ones.</b> An omitted bound is
     * substituted here - the earliest date the {@code DATE} column can hold, or today - so the
     * statement is a plain indexed range on {@code ix_txn_user_date (user_id, txn_date, is_deleted)}.
     * A predicate of the form {@code :from IS NULL OR t.txnDate >= :from} reads more directly but
     * cannot use the index and forces the driver to infer a type for a null it cannot see.
     *
     * <p><b>The default upper bound is today, and that is a real limit rather than a formality.</b>
     * A client that omits {@code to} does not see a record dated later than today. That is correct for
     * everything this module creates, since BR-08 refuses a future date here - but BR-08 exempts
     * {@code RECURRING}, and module 5's scheduler writes those rows ahead of their date. A future
     * recurring occurrence will therefore be absent from an unbounded list until its date arrives,
     * which is a defensible reading of "my transactions" but is not the only one. It is recorded here
     * and in the module report so module 5 makes that choice deliberately, with {@code to} as the
     * parameter that overrides it, rather than inheriting it by accident.
     *
     * <p><b>No pagination.</b> Neither UC-07 nor UC-10 asks for it, and the frontend loads the list
     * whole to build its month view and its running totals. A page size would be a parameter with no
     * caller. The date range is the mechanism for keeping a response bounded, and it is the one the
     * use case describes.
     *
     * @param from           earliest date to include, or null for no lower bound
     * @param to             latest date to include, or null for today
     * @param includeDeleted whether to include records in the trash. UC-10 A1 needs this: a student
     *                       can only restore a record they can see.
     * @throws RequestValidationException if {@code from} is later than {@code to}
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> listTransactions(AuthenticatedUser principal,
                                                      LocalDate from,
                                                      LocalDate to,
                                                      boolean includeDeleted) {
        LocalDate effectiveFrom = from != null ? from : EARLIEST_DATE;
        LocalDate effectiveTo = to != null ? to : today();

        if (effectiveFrom.isAfter(effectiveTo)) {
            // A range that cannot contain anything is a mistaken request, not an empty result: the
            // client would otherwise show "no transactions" for a filter it wrote backwards.
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

    /**
     * UC-10: read one of the caller's own transactions.
     *
     * <p>Only a record that is not in the trash. A deleted one answers {@code 404} here, because
     * that is what "deleted" has to mean for a read to be honest: the row is still in the database
     * (BR-09) but it is not part of the caller's records any more. The list endpoint returns it when
     * asked with {@code includeDeleted=true}, which is how a client finds it again to restore it.
     *
     * @throws NotFoundException if the record does not exist, is not the caller's, or is deleted
     */
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(AuthenticatedUser principal, Long transactionId) {
        return transactionMapper.toResponse(requireActiveTransaction(principal, transactionId));
    }

    /**
     * UC-07: record one transaction owned by the caller.
     *
     * <p>There is no {@code type} to set: the record is income or expense according to the category
     * it is filed under (BR-05), and {@code source} is fixed at {@code MANUAL} by the entity's
     * factory. A row created here cannot claim to have come from the recurring scheduler, which
     * matters because that is the one source {@code sp_validate_transaction} exempts from the BR-08
     * date check.
     *
     * @throws NotFoundException          if the category does not exist or is not one the caller may
     *                                    use
     * @throws RequestValidationException if the category is retired, or the date is in the future
     */
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
                trimToNull(request.description()));

        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, WriteOperation.CREATE);
        }

        log.info("Transaction created userId={} transactionId={} categoryId={}",
                userId, transaction.getId(), category.getId());
        return transactionMapper.toResponse(transaction);
    }

    /**
     * UC-10: change some fields of one of the caller's own transactions.
     *
     * <p>Only the fields actually present are touched, so a request that sends one field leaves the
     * others alone, and a request that changes nothing issues no {@code UPDATE} at all. Sending an
     * empty string for {@code description} clears it; leaving it out, or sending null, leaves it as
     * it is.
     *
     * <p><b>Moving a record between categories is how its type changes</b> (BR-05). That is a
     * legitimate correction of a misfiled record, so it is allowed - and the database's
     * {@code trg_categories_before_update} is what stops the <em>category's</em> type being changed
     * under records that already exist. The two are different operations and only the second is
     * refused.
     *
     * <p><b>The active check is applied only to a move.</b> BR-07 retires a category so it is not
     * offered for new records; a record already filed under it must stay editable, or retiring a
     * category would freeze the records inside it. The insert trigger enforces presence in the
     * active set with {@code require_active = 1} and the update trigger deliberately skips it, since
     * it cannot tell an unchanged category from a newly chosen one. This method can tell, so it
     * applies the rule exactly when the student is making a new filing decision.
     *
     * @throws NotFoundException          if the record or the target category is not the caller's
     * @throws RequestValidationException if the target category is retired, or the date is in the
     *                                    future
     */
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
            transaction.setDescription(trimToNull(request.description()));
        }

        try {
            // The history row is written by trg_transactions_after_update, and only for columns
            // that actually changed - so a request that changed nothing adds no noise to the log
            // BR-09 keeps. @DynamicUpdate on the entity is what limits the statement to the
            // columns this method touched; without it a plain Hibernate UPDATE would also write
            // back is_deleted as it was read, silently un-deleting a record that a concurrent
            // request had just deleted.
            transactionRepository.saveAndFlush(transaction);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, principal.userId(), WriteOperation.UPDATE);
        }

        log.info("Transaction updated userId={} transactionId={}",
                principal.userId(), transactionId);
        return transactionMapper.toResponse(transaction);
    }

    /**
     * UC-10 / BR-09: move one of the caller's own transactions to the trash.
     *
     * <p>The row is not removed. {@code is_deleted} is set and {@code deleted_at} stamped by
     * {@code sp_soft_delete_transaction}, every report view filters on {@code is_deleted = 0}, and
     * the change is appended to {@code transaction_history} as a {@code DELETE} row. The record stays
     * recoverable, which is the whole of BR-09.
     *
     * <p>Deleting twice is answered {@code 409}, not {@code 404} and not a silent success: the row is
     * still there, the caller's copy of its state is simply out of date, and the procedure refuses
     * the transition. Reporting "not found" would be false, and reporting success would tell the
     * client something happened that did not.
     *
     * @throws NotFoundException         if the record does not exist or is not the caller's
     * @throws TransactionStateException if the record is already deleted
     */
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

    /**
     * UC-10 A1: bring a soft-deleted transaction back.
     *
     * <p>BR-09's log is appended to, never rewritten: {@code trg_transactions_after_update} writes a
     * {@code RESTORE} row beside the earlier {@code CREATE} and {@code DELETE} ones, so the record's
     * history shows that it was removed and brought back rather than pretending it never was.
     *
     * <p>Restoring a record that is not deleted is answered {@code 409}. The database would accept
     * the call and change nothing, since it only sets {@code is_deleted = 0} on a row that is already
     * {@code 0}; responding "restored" to a request that did nothing would misreport the outcome for
     * a client whose list is out of date.
     *
     * @throws NotFoundException         if the record does not exist or is not the caller's
     * @throws TransactionStateException if the record is not deleted
     */
    @Transactional
    public TransactionResponse restore(AuthenticatedUser principal, Long transactionId) {
        Transaction transaction = requireAnyTransactionForUpdate(principal, transactionId);

        if (!Boolean.TRUE.equals(transaction.getIsDeleted())) {
            throw TransactionStateException.notDeleted();
        }

        // The procedure sets is_deleted and deleted_at by SQL, so the copy in memory is stale. The
        // DAO reloads the row before returning, which is what makes the response below describe the
        // record as it now is rather than as it was when the request began.
        procedureDao.restore(transaction, principal.userId());
        log.info("Transaction restored userId={} transactionId={}",
                principal.userId(), transactionId);
        return transactionMapper.toResponse(transaction);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * Which call site is translating a failure, since the same refusal means different things.
     *
     * <p>Only the two paths that write a row through JPA are here. Delete and restore call the
     * stored procedures instead, and the refusals those procedures signal are already excluded by
     * the time they are called: the service holds the row lock and has read the state, so "does not
     * exist", "is not yours" and "already in that state" have each been answered - with {@code 404}
     * or {@code 409} - before the call is made. There is no fourth call site to distinguish.
     */
    private enum WriteOperation {
        CREATE,
        UPDATE
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>Which rule fired is decided by {@link TransactionWriteFailure}, which asks by SQLSTATE
     * rather than by matching the driver's message. Anything it does not recognise is rethrown
     * unchanged, so {@code GlobalExceptionHandler} answers it as an internal error rather than this
     * method mislabelling it.
     *
     * <p><b>Why a signalled refusal becomes {@code 409} rather than a field error.</b> Every rule
     * {@code sp_validate_transaction} can signal about is checked by the caller before the write, so
     * reaching this branch means one of those checks no longer held by the time the row was
     * inserted - the category was deleted or retired by a concurrent request, or the day rolled over
     * mid-request. Naming a field here would be guessing which of the two it was; {@code 409} with a
     * message that says the data changed underneath the request is the accurate answer, and it tells
     * the client what to do, which is reload. The individual rules are verified against the real
     * exception shapes in {@code TransactionWriteFailureTest} instead.
     *
     * <p>The exception is deliberately not logged. A trigger's {@code SIGNAL} text names the rule and
     * the table it guards, and MySQL's constraint messages name the table, the column and the value
     * that collided - all internal identifiers the response already withholds. The operation and the
     * account id describe the refusal well enough to investigate it.
     */
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

        // Not a recognised refusal, so it is a genuine fault rather than a rule doing its job.
        // Logged in full and answered as an internal error by the handler.
        log.error("Transaction write failed unexpectedly userId={} op={}", userId, operation, ex);
        return ex;
    }

    /**
     * Loads one of the caller's own transactions that is not in the trash, without locking it.
     *
     * <p>The read path's lookup. {@code GET} only reports what is there, so it has no state to
     * protect and takes no lock - see the locking pair below for why the write paths do.
     *
     * @throws NotFoundException if the row does not exist, belongs to another student, or is deleted
     */
    private Transaction requireActiveTransaction(AuthenticatedUser principal, Long transactionId) {
        return transactionRepository
                .findActiveByIdAndUserId(transactionId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Transaction not found."));
    }

    /**
     * As {@link #requireActiveTransaction}, with the row locked for the rest of the transaction.
     *
     * <p>Used by the edit path. Without the lock, an edit that read the record and then a
     * concurrent delete could both commit: the edit would be applied to a row already in the trash,
     * and {@code @DynamicUpdate} would write back the {@code is_deleted} it read, bringing the
     * record out of the trash with no {@code RESTORE} row to show for it.
     */
    private Transaction requireActiveTransactionForUpdate(AuthenticatedUser principal,
                                                          Long transactionId) {
        return transactionRepository
                .findActiveByIdAndUserIdForUpdate(transactionId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Transaction not found."));
    }

    /** As {@link #requireAnyTransaction}, with the row locked for the rest of the transaction. */
    private Transaction requireAnyTransactionForUpdate(AuthenticatedUser principal,
                                                       Long transactionId) {
        return transactionRepository
                .findAnyByIdAndUserIdForUpdate(transactionId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Transaction not found."));
    }

    /**
     * Loads a category the caller may file records under: their own, or a shared default.
     *
     * <p>{@code findVisibleById} is the query that expresses this - the same set
     * {@code GET /categories} returns, by id - so a category belonging to another student is not
     * found rather than found-and-refused. The database reaches the same conclusion through
     * {@code sp_validate_transaction}; the check is here so the caller receives a {@code 404} naming
     * the field instead of an undifferentiated refusal.
     *
     * @throws NotFoundException if the category does not exist or belongs to another student
     */
    private Category requireUsableCategory(Long userId, Long categoryId) {
        return categoryRepository.findVisibleById(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Category not found."));
    }

    /**
     * BR-07: a retired category is not offered for new records.
     *
     * <p>The message names the field so the client can point at the picker. The database refuses the
     * same insert through {@code sp_validate_transaction}; this is the version that says why.
     */
    private void requireActiveCategory(Category category) {
        if (Boolean.FALSE.equals(category.getIsActive())) {
            throw new RequestValidationException(
                    "The category is retired and cannot be used for new records.",
                    List.of(new ApiError.FieldError("categoryId",
                            "Choose a category that is still in use, or restore this one first.")));
        }
    }

    /**
     * BR-08: a record cannot be dated in the future.
     *
     * <p>{@code sp_validate_transaction} refuses the same value, with one exemption this module
     * cannot reach: a {@code RECURRING} row may be dated ahead, because the scheduler writes those
     * before they happen. Every row this API creates is {@code MANUAL} and {@code source} is fixed by
     * the entity's factory, so the exemption never applies here - which is exactly why accepting
     * {@code source} from a request body would have been a defect rather than a convenience.
     */
    private void requireNotInTheFuture(LocalDate txnDate) {
        if (txnDate.isAfter(today())) {
            throw new RequestValidationException(
                    "A transaction cannot be dated in the future.",
                    List.of(new ApiError.FieldError("txnDate",
                            "Choose today or an earlier date.")));
        }
    }

    /** Today in the application's zone, which is the zone the database session runs in (VĐ-10). */
    private static LocalDate today() {
        return LocalDate.now(APPLICATION_ZONE);
    }

    /** Trims, and turns a value that is empty after trimming into null. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The earliest date a {@code DATE} column can hold.
     *
     * <p>Not {@code LocalDate.MIN}: MySQL's {@code DATE} range starts in year 1000, and a value
     * outside it would turn an ordinary "no lower bound" request into a driver error instead of a
     * full list.
     */
    private static final LocalDate EARLIEST_DATE = LocalDate.of(1000, 1, 1);
}
