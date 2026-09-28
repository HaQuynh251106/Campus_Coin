package com.campuscoin.admin.service;

import java.sql.SQLException;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;

final class AdminWriteFailure {

    private static final String SIGNAL_SQLSTATE = "45000";

    private static final String CONSTRAINT_SQLSTATE = "23000";

    private static final String TIP_TEMPLATE_CODE_CONSTRAINT = "uk_tip_template_code";

    private static final String CATEGORY_SCOPE_NAME_CONSTRAINT = "uk_categories_scope_type_name";

    private AdminWriteFailure() {
    }

    static boolean isSignalledRefusal(Throwable failure) {
        return hasSqlState(failure, SIGNAL_SQLSTATE);
    }

    static boolean isDuplicateTipTemplateCode(Throwable failure) {
        return hasSqlState(failure, CONSTRAINT_SQLSTATE)
                && mentions(failure, TIP_TEMPLATE_CODE_CONSTRAINT);
    }

    static boolean isDuplicateDefaultCategory(Throwable failure) {
        return hasSqlState(failure, CONSTRAINT_SQLSTATE)
                && mentions(failure, CATEGORY_SCOPE_NAME_CONSTRAINT);
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
