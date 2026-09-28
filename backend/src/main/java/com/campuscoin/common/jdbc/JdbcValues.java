package com.campuscoin.common.jdbc;

import java.sql.Timestamp;
import java.time.LocalDateTime;

public final class JdbcValues {

    private JdbcValues() {
    }

    public static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return ((Number) value).intValue() != 0;
    }

    public static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
