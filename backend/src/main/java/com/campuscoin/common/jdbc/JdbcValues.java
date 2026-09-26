package com.campuscoin.common.jdbc;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * The two column readings every native projection in this project shares.
 *
 * <p><b>Why this class exists rather than a third private copy.</b> {@code AdminTupleValues} and
 * {@code AnomalyViewDao} each grew their own {@code toBoolean}, and the second was written after the
 * first had already been corrected for the same bug - which is the shape in which a fix is applied
 * once and the defect survives in the other copy. {@code AnomalyViewDao} recorded the rule when it
 * added the second: <em>"If a third module needs it, the helper moves to
 * {@code com.campuscoin.common}."</em> UC-08 is the third module - it reads
 * {@code transactions.ai_overridden}, which is {@code TINYINT(1)} and therefore arrives as the same
 * JDBC type - so the move is made here and all three readers now share one implementation.
 *
 * <p>Public and in {@code common} because it is genuinely shared across packages. The readings are
 * pure conversions of a JDBC value and carry no module's business rule.
 */
public final class JdbcValues {

    private JdbcValues() {
    }

    /**
     * {@code TINYINT(1)} as a {@link Boolean}, or null when the column is null.
     *
     * <p><b>Both a {@code Boolean} and a {@code Number} are accepted, and the first is the one that
     * actually arrives.</b> MySQL Connector/J turns {@code tinyInt1isBit} on by default, which makes a
     * {@code TINYINT(1)} column a JDBC {@link Boolean} - so casting to {@link Number}, which reads
     * naturally from the single digit the column holds, throws {@code ClassCastException} and turns
     * every read into a {@code 500}. The numeric branch stays because a deployment can turn the
     * property off in its JDBC URL, at which point the same column is an {@code Integer}; accepting
     * only one shape would make these readers depend on a connection string.
     *
     * <p>Null is passed through as null rather than being read as {@code false}. Every column this
     * reads is {@code NOT NULL}, so null here means the projection is wrong - and a caller that wants
     * a primitive decides for itself whether that is {@code false} or a fault.
     */
    public static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return ((Number) value).intValue() != 0;
    }

    /** A MySQL {@code DATETIME} as a {@link LocalDateTime}, or null when the column is null. */
    public static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
