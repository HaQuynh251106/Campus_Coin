package com.campuscoin.recent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;
import com.campuscoin.recent.dto.RecordRecentActivityRequest;
import com.campuscoin.recent.dto.RecentActivityListResponse;
import com.campuscoin.recent.dto.RecentActivityResponse;
import com.campuscoin.recent.entity.RecentActivityRow;
import com.campuscoin.recent.mapper.RecentActivityMapper;
import com.campuscoin.recent.repository.RecentActivityProcedureDao;
import com.campuscoin.recent.repository.RecentActivityViewDao;

/**
 * Recording what a student recently looked at, and reading the list back (UC-26).
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>That the transaction is the caller's own</b> - {@code sp_touch_recent_activity} compares the
 *       row's owner with the id it was given and {@code SIGNAL}s when they differ. That is BR-02. The
 *       foreign key alone would prove only that the row exists, not whose it is, so without that check
 *       a student could create an activity row pointing at another student's transaction, and this very
 *       endpoint would then publish that transaction's amount, date and description. The check lives in
 *       the procedure rather than here because it is the one place every caller goes through, including
 *       a hand-run {@code CALL}, and because a service-side pre-check would have to read the
 *       transaction through another module's repository - making this module depend on module 4 to
 *       answer a question module 4 has already answered in SQL.</li>
 *   <li><b>That an action is viewed or edited</b> - the column's {@code ENUM} and the procedure's own
 *       check. Java narrows it a step earlier with the {@code RecentAction} enum, which makes a third
 *       value a field error rather than a {@code 45000}.</li>
 *   <li><b>Recording the same view twice</b> - {@code uk_recent} plus
 *       {@code ON DUPLICATE KEY UPDATE occurred_at = NOW()}: re-viewing moves the entry rather than
 *       duplicating it.</li>
 *   <li><b>Which entries are visible</b> - {@code v_user_recent_activity} joins {@code transactions}
 *       with {@code WHERE t.is_deleted = 0}, so a transaction in the trash is off the list. Because the
 *       activity row itself is untouched, restoring the transaction puts it back with no repair
 *       step.</li>
 * </ul>
 *
 * <p><b>The record endpoint is explicit and the read endpoint is not implicit.</b> UC-26 B1 is "the
 * student views or edits a transaction", and this build records that as its own request rather than
 * making {@code GET /api/v1/transactions/{id}} write a row as a side effect. Reading is meant to be
 * safe and repeatable, and an endpoint that mutates on read makes every retry, prefetch and cache
 * revalidation a write - as well as silently making module 4 depend on module 12. The cost of the
 * explicit request is that a client has to send two calls to open a record; that is the client's
 * choice to make, and the record is a statement about what the student did, which the client knows and
 * the server does not.
 *
 * <p><b>A refusal from the procedure is answered as "no such transaction", in one form.</b> The
 * procedure raises {@code 45000} both for an id that matches nothing and for one that belongs to
 * somebody else, and this class answers both identically: telling a caller which of the two happened
 * would let them enumerate other students' transaction identifiers one request at a time
 * (section 7.5). This is the same indistinguishability {@code BookmarkService} records for its trigger,
 * and the same reason {@code TransactionRepository#findActiveByIdAndUserId} returns empty for both.
 */
@Service
public class RecentActivityService {

    private static final Logger log = LoggerFactory.getLogger(RecentActivityService.class);

    /**
     * The size of the list when the caller does not name one.
     *
     * <p>Ten is a screenful, which is what the list is for. It is the default and not the maximum, so a
     * client that wants more asks for more - see the bound below.
     */
    static final int DEFAULT_LIMIT = 10;

    /**
     * The largest list this endpoint will return.
     *
     * <p>A bound is needed because the list is unbounded in principle: a student who has used the
     * application for a year has thousands of activity rows, and "recently viewed" is not a request for
     * all of them. Refused rather than silently reduced, because a client that sent 500 and received 50
     * has no way to tell a capped answer from a short one - and this endpoint's response says which
     * limit was applied, so an adjustment the caller did not ask for would be a lie in its own body.
     */
    static final int MAX_LIMIT = 50;

    private final RecentActivityViewDao recentActivityViewDao;
    private final RecentActivityProcedureDao recentActivityProcedureDao;
    private final RecentActivityMapper recentActivityMapper;

    public RecentActivityService(RecentActivityViewDao recentActivityViewDao,
                                 RecentActivityProcedureDao recentActivityProcedureDao,
                                 RecentActivityMapper recentActivityMapper) {
        this.recentActivityViewDao = recentActivityViewDao;
        this.recentActivityProcedureDao = recentActivityProcedureDao;
        this.recentActivityMapper = recentActivityMapper;
    }

    /**
     * UC-26: the caller's most recent entries.
     *
     * <p>No {@code 404} for an empty list. A student who has just registered has viewed nothing, and
     * that is a fact about their own data rather than a missing resource - the distinction
     * {@code TipService} draws for a month with no tips.
     */
    @Transactional(readOnly = true)
    public RecentActivityListResponse list(AuthenticatedUser principal, int limit) {
        int applied = effectiveLimit(limit);
        List<RecentActivityRow> rows = recentActivityViewDao.findRecent(principal.userId(), applied);

        return new RecentActivityListResponse(applied, recentActivityMapper.toResponses(rows));
    }

    /**
     * UC-26 B1: records that the caller viewed or edited one of their own transactions, and returns it.
     *
     * <p>The response is read back through the view rather than assembled from the request, so the
     * entry a client receives is the same row the list would show it - the category, the amount and the
     * date come from the database, not from what the client sent. It is the reasoning
     * {@code BookmarkService} records for reading a bookmark back after writing it.
     *
     * <p>The read-back runs inside this transaction, so it sees the row the procedure just wrote. Not
     * finding it is a fault rather than a missing resource - the caller's own write vanished between
     * the `CALL` and the read - so it is answered as a server error rather than as a `404` that would
     * blame the request.
     */
    @Transactional
    public RecentActivityResponse record(AuthenticatedUser principal,
                                         RecordRecentActivityRequest request) {
        Long userId = principal.userId();

        try {
            recentActivityProcedureDao.touch(userId, request.transactionId(), request.action());
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, userId, request.transactionId());
        }

        RecentActivityRow row = recentActivityViewDao
                .findOne(userId, request.transactionId(), request.action())
                .orElseThrow(() -> new IllegalStateException(
                        "Recent activity for transaction " + request.transactionId()
                                + " was not readable back after being written."));

        log.info("Recent activity recorded userId={} transactionId={} action={}",
                userId, request.transactionId(), request.action());

        return recentActivityMapper.toResponse(row);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * The limit the caller asked for, or the default.
     *
     * <p>A value outside {@code 1}..{@link #MAX_LIMIT} is refused rather than clamped. That is the same
     * decision the response's {@code limit} field depends on: the endpoint reports the limit it
     * applied, so quietly changing it would make its own body untrue, and a client that sent 500 and
     * received 50 could not tell a capped answer from a short one.
     */
    private int effectiveLimit(int limit) {
        if (limit > 0 && limit <= MAX_LIMIT) {
            return limit;
        }

        throw new RequestValidationException(
                "The requested number of entries is out of range.",
                List.of(new ApiError.FieldError("limit",
                        "Ask for between 1 and " + MAX_LIMIT + " entries. Omit the parameter to get "
                                + DEFAULT_LIMIT + ".")));
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>Which rule fired is decided by {@link RecentActivityWriteFailure}, which asks the database by
     * SQLSTATE and by constraint name rather than by matching the procedure's prose. Anything it does
     * not recognise is rethrown unchanged, so {@code GlobalExceptionHandler} answers it as an internal
     * error rather than this method mislabelling it.
     *
     * <p>Both refusals collapse into one answer, and that is the point - see the class note. The
     * exception is deliberately not logged: MySQL's message carries the procedure's text and the
     * offending id. The user id and transaction id describe the refusal well enough to investigate it.
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, Long userId, Long transactionId) {
        if (RecentActivityWriteFailure.isSignalledRefusal(ex)) {
            log.info("Recent activity refused userId={} transactionId={}", userId, transactionId);
            return new NotFoundException("Transaction not found.");
        }

        if (RecentActivityWriteFailure.isConstraintViolation(ex)) {
            log.info("Recent activity rejected by a constraint userId={} transactionId={}",
                    userId, transactionId);
            return new NotFoundException("Transaction not found.");
        }

        log.error("Recent activity write failed unexpectedly userId={} transactionId={}",
                userId, transactionId, ex);
        return ex;
    }
}
