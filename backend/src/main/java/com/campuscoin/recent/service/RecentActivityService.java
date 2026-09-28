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

@Service
public class RecentActivityService {

    private static final Logger log = LoggerFactory.getLogger(RecentActivityService.class);

    static final int DEFAULT_LIMIT = 10;

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

    @Transactional(readOnly = true)
    public RecentActivityListResponse list(AuthenticatedUser principal, int limit) {
        int applied = effectiveLimit(limit);
        List<RecentActivityRow> rows = recentActivityViewDao.findRecent(principal.userId(), applied);

        return new RecentActivityListResponse(applied, recentActivityMapper.toResponses(rows));
    }

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
