package com.campuscoin.admin.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * The two column readings the administration projections share.
 *
 * <p>Both were private copies in {@code AdminAnnouncementViewDao} and
 * {@code AdminTipTemplateViewDao} until a suite run showed one of them was wrong: a private helper
 * duplicated is a private helper whose fix has to be applied twice, and the second copy is exactly
 * where the bug survived. They are package-private here so both DAOs read a column the same way.
 *
 * <p>Confined to {@code com.campuscoin.admin.repository} - the types it converts are this package's
 * business, and no other module's projections need them.
 */
final class AdminTupleValues {

    private AdminTupleValues() {
    }

    /**
     * {@code TINYINT(1)} as a {@link Boolean}.
     *
     * <p><b>Both a {@code Boolean} and a {@code Number} are accepted, and that is not defensive
     * coding.</b> MySQL Connector/J's {@code tinyInt1isBit} connection property is on by default, so
     * a {@code TINYINT(1)} column arrives as a {@link Boolean} - and a cast to {@link Number}, which
     * reads naturally from the single digit the column holds, throws {@code ClassCastException} and
     * turns every read into a {@code 500}. The numeric branch stays because the property can be
     * turned off in a deployment's JDBC URL, at which point the same column is an {@code Integer};
     * handling only one shape would make these DAOs depend on a connection string.
     *
     * <p>Null is passed through as null. The columns this reads are {@code NOT NULL}, so null here
     * means the projection is wrong, which the response's required field then exposes rather than
     * hides.
     */
    static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return ((Number) value).intValue() != 0;
    }

    /** A MySQL {@code DATETIME} as a {@link LocalDateTime}. */
    static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
