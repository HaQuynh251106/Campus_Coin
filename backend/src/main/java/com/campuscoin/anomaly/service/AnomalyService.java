package com.campuscoin.anomaly.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.anomaly.dto.AnomalyScanResponse;
import com.campuscoin.anomaly.dto.FlaggedTransactionListResponse;
import com.campuscoin.anomaly.dto.FlaggedTransactionResponse;
import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.anomaly.entity.CategoryAmountStats;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.anomaly.mapper.AnomalyMapper;
import com.campuscoin.anomaly.repository.AnomalyFlagProcedureDao;
import com.campuscoin.anomaly.repository.AnomalyViewDao;
import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.RequestValidationException;

/**
 * The two things UC-24 does: say which of the student's records look wrong, and look again (UC-24).
 *
 * <p><b>The student never states a flag.</b> {@link AnomalyDetector} computes what each record's flag
 * should be and this class writes the difference; there is no request field anywhere in the module
 * that carries a flag type, and {@code docs/api/transactions.md} records why - a client able to set
 * {@code flagType} could mark its own record as reviewed, which is exactly the signal the feature
 * exists to raise. The only input either endpoint takes is the caller's own identity, read from the
 * verified bearer token.
 *
 * <p><b>What the database owns, and this class therefore does not restate.</b>
 *
 * <ul>
 *   <li><b>That the record is the caller's own</b> - {@code sp_flag_transaction} compares the row's
 *       owner with the id it was given and {@code SIGNAL}s when they differ. That is BR-02, and it
 *       holds for every caller including a hand-run {@code CALL}. This class narrows every read by
 *       {@code user_id} as well, so the two agree by construction; the procedure's check is what makes
 *       the guarantee hold even if a later query forgot to narrow.</li>
 *   <li><b>That a flag type is one of the three the column stores</b> - the column's {@code ENUM} and
 *       the procedure's own check. Java narrows it a step earlier, since the detector only ever
 *       produces an {@link AnomalyFlagType} member.</li>
 *   <li><b>What a cleared flag looks like</b> - {@code p_flag_type = 'NONE'} writes
 *       {@code is_flagged = 0} and nulls the note, so "not flagged" has one stored representation and a
 *       stale explanation cannot survive a clearing.</li>
 *   <li><b>What a change is recorded as</b> - the {@code UPDATE} fires
 *       {@code trg_transactions_after_update}, which appends the history row BR-09 requires when - and
 *       only when - one of the three flag columns actually moved.</li>
 * </ul>
 *
 * <p><b>Reads are bounded by the caller's own account and nothing else.</b> Both rules are relative to
 * the student's own history: a duplicate is another of <em>their</em> records, and "unusual" is
 * measured against <em>their</em> average. So the scan reads every live record the student has rather
 * than a window - see {@code AnomalyViewDao#findScanCandidates} for why a window would be wrong rather
 * than merely slower.
 *
 * <p><b>Both endpoints are read-only in effect when nothing has changed, and that is observable.</b> A
 * repeated scan over unchanged data finds the same verdict for every record, sees that each matches
 * what is stored, and issues no {@code CALL} at all - so no history row is appended and
 * {@code examined} accounts for every record under {@code unchanged}. A scan is therefore safe to
 * repeat, which is what a client that refreshes on every screen visit relies on.
 *
 * <p><b>A refusal from the procedure is a fault here, not a {@code 404}.</b> The scan names no
 * transaction, so answering "no such transaction" would blame the client for something it did not ask
 * for. Every record the scan writes about was read from the caller's own rows moments earlier in the
 * same transaction, so a refusal means one of those facts changed underneath it - a record hard-deleted
 * mid-scan, say. It is logged and answered as a server error. {@code RecentActivityService} draws the
 * same line from the other direction: there the caller <em>did</em> name a transaction, so its refusal
 * is a {@code 404}.
 */
@Service
public class AnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);

    /**
     * The size of the flagged list when the caller does not name one.
     *
     * <p>Twenty, and the number is larger than the recent-activity list's ten on purpose: that list is
     * a history a student scrolls, while this one is a to-do list of things to check. A student whose
     * flagged list runs past twenty has a data-entry problem the endpoint's size cannot fix.
     */
    static final int DEFAULT_LIMIT = 20;

    /**
     * The largest list this endpoint will return.
     *
     * <p>A bound is needed because the list is unbounded in principle - a scan over a year of records
     * could mark many of them - and refused rather than clamped, because the response reports the limit
     * it applied and a silently reduced answer would make that field untrue. The reasoning
     * {@code RecentActivityService#MAX_LIMIT} records for the same decision.
     */
    static final int MAX_LIMIT = 100;

    private final AnomalyViewDao anomalyViewDao;
    private final AnomalyFlagProcedureDao anomalyFlagProcedureDao;
    private final AnomalyDetector anomalyDetector;
    private final AnomalyMapper anomalyMapper;

    public AnomalyService(AnomalyViewDao anomalyViewDao,
                          AnomalyFlagProcedureDao anomalyFlagProcedureDao,
                          AnomalyDetector anomalyDetector,
                          AnomalyMapper anomalyMapper) {
        this.anomalyViewDao = anomalyViewDao;
        this.anomalyFlagProcedureDao = anomalyFlagProcedureDao;
        this.anomalyDetector = anomalyDetector;
        this.anomalyMapper = anomalyMapper;
    }

    /**
     * UC-24: the caller's flagged records.
     *
     * <p>No {@code 404} for an empty list. Most students have nothing flagged, and "nothing of yours
     * looks wrong" is a fact about their own data rather than a missing resource - the distinction
     * {@code TipService} draws for a month with no tips and {@code RecentActivityService} for a student
     * who has opened nothing.
     *
     * <p>This endpoint examines nothing. It reports the flags as they stand, so opening the screen
     * cannot change what is on it; the scan is a separate request the student sends.
     */
    @Transactional(readOnly = true)
    public FlaggedTransactionListResponse list(AuthenticatedUser principal, int limit) {
        int applied = effectiveLimit(limit);
        List<FlaggedTransactionRow> rows = anomalyViewDao.findFlagged(principal.userId(), applied);

        return new FlaggedTransactionListResponse(applied, anomalyMapper.toResponses(rows));
    }

    /**
     * UC-24: examines the caller's own records and writes the flags that differ from what is stored.
     *
     * <p><b>Reading and writing in one transaction.</b> The verdicts are computed from rows read inside
     * this transaction, and the writes go back inside it, so the answer a client receives describes the
     * data the scan actually saw. Splitting the two would let a record change between the read that
     * judged it and the write that marked it.
     *
     * <p><b>Only differences are written</b> - {@link AnomalyDetector#differsFromStored} decides - which
     * is what makes the endpoint idempotent in the strong sense: not merely "the same flags after a
     * second call", but no write at all, and so no history row.
     *
     * <p>The list returned is read back through the same query the read endpoint uses, rather than
     * assembled from the verdicts, so the two endpoints cannot disagree about what is flagged. It is
     * the shape {@code TipService#generateTips} gives the same problem.
     */
    @Transactional
    public AnomalyScanResponse scan(AuthenticatedUser principal) {
        Long userId = principal.userId();

        List<FlaggedTransactionRow> candidates = anomalyViewDao.findScanCandidates(userId);
        Map<Long, CategoryAmountStats> categoryStats = anomalyViewDao.findCategoryStats(userId);

        int windowDays = anomalyDetector.duplicateWindowDays();
        BigDecimal multiplier = anomalyDetector.unusualMultiplier();

        List<AnomalyDetector.Verdict> verdicts =
                anomalyDetector.detect(candidates, categoryStats, windowDays, multiplier);

        int flagged = 0;
        int cleared = 0;
        int unchanged = 0;

        for (int index = 0; index < candidates.size(); index++) {
            FlaggedTransactionRow row = candidates.get(index);
            AnomalyDetector.Verdict verdict = verdicts.get(index);

            if (!AnomalyDetector.differsFromStored(row, verdict)) {
                unchanged++;
                continue;
            }

            writeFlag(userId, verdict);

            if (verdict.flagType() == AnomalyFlagType.NONE) {
                cleared++;
            } else {
                flagged++;
            }
        }

        List<FlaggedTransactionRow> flaggedRows =
                anomalyViewDao.findFlagged(userId, MAX_LIMIT);

        log.info("Anomaly scan userId={} examined={} flagged={} cleared={} unchanged={}",
                userId, candidates.size(), flagged, cleared, unchanged);

        List<FlaggedTransactionResponse> entries = anomalyMapper.toResponses(flaggedRows);

        return new AnomalyScanResponse(candidates.size(), flagged, cleared, unchanged, entries);
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * Sends one record's verdict to the database.
     *
     * <p>The note is sent as {@code null} when the flag is being cleared, matching the stored shape the
     * procedure writes - a cleared flag has no explanation, so a stale one cannot survive it. The
     * procedure would null it anyway; sending it as null states the intent rather than relying on that.
     *
     * <p>A refusal is answered as a server fault rather than as a missing resource, and the exception
     * is not logged with its message: MySQL's text carries the procedure's prose and the offending
     * identifier, while the transaction id and the verdict describe the refusal well enough to
     * investigate. See the class note for why a {@code 404} would be the wrong answer here.
     */
    private void writeFlag(Long userId, AnomalyDetector.Verdict verdict) {
        try {
            anomalyFlagProcedureDao.flag(userId, verdict.transactionId(), verdict.flagType(),
                    verdict.flagNote());
        } catch (RuntimeException ex) {
            if (AnomalyWriteFailure.isSignalledRefusal(ex)
                    || AnomalyWriteFailure.isConstraintViolation(ex)) {
                log.error("Anomaly flag refused userId={} transactionId={} flagType={}",
                        userId, verdict.transactionId(), verdict.flagType());
                throw new IllegalStateException(
                        "The scan examined transaction " + verdict.transactionId()
                                + " and the database no longer accepted a flag on it.", ex);
            }

            log.error("Anomaly flag write failed unexpectedly userId={} transactionId={}",
                    userId, verdict.transactionId(), ex);
            throw ex;
        }
    }

    /**
     * The limit the caller asked for, or the default.
     *
     * <p>A value outside {@code 1}..{@link #MAX_LIMIT} is refused rather than clamped: the response
     * reports the limit it applied, so quietly changing it would make its own body untrue, and a client
     * that sent 500 and received 100 could not tell a capped answer from a short one.
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
}
