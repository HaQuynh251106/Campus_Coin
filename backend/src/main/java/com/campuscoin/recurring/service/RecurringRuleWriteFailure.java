package com.campuscoin.recurring.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

final class RecurringRuleWriteFailure {

    private static final String SIGNAL_SQLSTATE = "45000";

    private static final String CONSTRAINT_SQLSTATE = "23000";

    private RecurringRuleWriteFailure() {
    }

    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
    }

    static boolean mentionsRetiredCategory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains("category has been disabled")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && Objects.equals(sqlState, sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
