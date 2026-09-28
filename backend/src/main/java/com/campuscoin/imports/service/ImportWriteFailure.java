package com.campuscoin.imports.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

final class ImportWriteFailure {

    private static final String SIGNAL_SQLSTATE = "45000";

    private static final String CONSTRAINT_SQLSTATE = "23000";

    private static final String IMPORT_ROW_UNIQUE_KEY = "uk_import_row";

    private ImportWriteFailure() {
    }

    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    static boolean isConstraintViolation(Throwable failure) {
        return failure instanceof DataIntegrityViolationException
                || hasSqlState(failure, CONSTRAINT_SQLSTATE);
    }

    static boolean isImportRowCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(IMPORT_ROW_UNIQUE_KEY)) {
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
