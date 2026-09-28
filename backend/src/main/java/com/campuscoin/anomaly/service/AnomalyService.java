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

@Service
public class AnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);

    static final int DEFAULT_LIMIT = 20;

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

    @Transactional(readOnly = true)
    public FlaggedTransactionListResponse list(AuthenticatedUser principal, int limit) {
        int applied = effectiveLimit(limit);
        List<FlaggedTransactionRow> rows = anomalyViewDao.findFlagged(principal.userId(), applied);

        return new FlaggedTransactionListResponse(applied, anomalyMapper.toResponses(rows));
    }

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
