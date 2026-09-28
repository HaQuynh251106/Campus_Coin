package com.campuscoin.admin.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

final class AdminTupleValues {

    private AdminTupleValues() {
    }

    static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return ((Number) value).intValue() != 0;
    }

    static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
