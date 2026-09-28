package com.campuscoin.category.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

final class CategoryWriteFailure {

    private static final String SIGNAL_SQLSTATE = "45000";

    private static final String CONSTRAINT_SQLSTATE = "23000";

    private static final String UNIQUE_NAME_CONSTRAINT = "uk_categories_scope_type_name";

    private CategoryWriteFailure() {
    }

    static boolean isUniqueNameViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                && mentions(failure, UNIQUE_NAME_CONSTRAINT);
    }

    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
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

    private static boolean mentions(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
