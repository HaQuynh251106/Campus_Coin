package com.campuscoin.admin.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.entity.AdminAnnouncementRow;
import com.campuscoin.admin.entity.AnnouncementAudience;
import com.campuscoin.dashboard.entity.AnnouncementSeverity;

/**
 * Reads announcements for the administration screen (UC-21).
 *
 * <p><b>This reads the table, not {@code v_active_announcements}.</b> That view applies the time
 * window and the {@code is_active} flag, which is exactly what a student's dashboard wants and
 * exactly what an administrator must not have: the notice an administrator needs to act on is the
 * one that has expired, has not started yet, or has been switched off - and UC-21 B2's toggle exists
 * to switch it back on. Reading through the view would leave a deactivated notice unreachable and so
 * unrecoverable, and would make the list disagree with the toggle that acts on it. The dashboard's
 * own DAO keeps using the view; the two readers want different rows and neither is a duplicate of
 * the other.
 *
 * <p><b>{@code created_by} is not selected.</b> The module does not publish who wrote an
 * announcement - the audit trail records it, and {@code DashboardViewDao} takes the same position for
 * the student-facing read - so the column is absent from the SQL and from {@link AdminAnnouncementRow}
 * alike.
 *
 * <p>Projected by alias into a record rather than mapped as an entity. {@code announcements} has no
 * entity in this module by design: nothing here changes an announcement's content, and the one column
 * that moves is written by a procedure. A projection keeps it that way structurally, since there is
 * no managed instance anyone could set a field on and flush.
 */
@Repository
public class AdminAnnouncementViewDao {

    /** UC-21: every announcement, live or not, newest first. */
    private static final String SELECT_ALL = """
            SELECT a.id         AS id,
                   a.title      AS title,
                   a.body       AS body,
                   a.severity   AS severity,
                   a.audience   AS audience,
                   a.starts_at  AS startsAt,
                   a.ends_at    AS endsAt,
                   a.is_active  AS isActive,
                   a.created_at AS createdAt
              FROM announcements a
             ORDER BY a.created_at DESC, a.id DESC
            """;

    /** One announcement, for a write's response and for the toggle's pre-read. */
    private static final String SELECT_ONE = """
            SELECT a.id         AS id,
                   a.title      AS title,
                   a.body       AS body,
                   a.severity   AS severity,
                   a.audience   AS audience,
                   a.starts_at  AS startsAt,
                   a.ends_at    AS endsAt,
                   a.is_active  AS isActive,
                   a.created_at AS createdAt
              FROM announcements a
             WHERE a.id = :id
            """;

    /**
     * The newest announcement with this author, title and start time.
     *
     * <p>This is how a created announcement is located, and the reason is worth stating.
     * {@code sp_admin_create_announcement} declares no OUT parameter, so the new id does not come
     * back from the call - and it cannot be recovered from {@code LAST_INSERT_ID()} either, because
     * the procedure inserts its {@code admin_audit_log} row <em>after</em> the announcement and
     * {@code admin_audit_log.id} is {@code AUTO_INCREMENT}, so the session's last insert id at the
     * moment the call returns belongs to the audit row, not to the announcement.
     *
     * <p>Unlike a category or a tip template, {@code announcements} has no unique key to read back
     * by. The triple here is the closest available identity: the same administrator, publishing the
     * same title, starting at the same moment, twice, is not a case the use case produces, and
     * {@code ORDER BY id DESC LIMIT 1} takes the newest if it ever did. The read runs inside the same
     * transaction as the write, so no competing insert can slip between them.
     *
     * <p>{@code starts_at} is compared rather than {@code created_at} because the caller supplies the
     * former: a procedure defaulted start time of {@code NOW()} would match to within a second, but
     * an explicit one is exact, and this keeps the lookup correct for both.
     */
    private static final String SELECT_CREATED = """
            SELECT a.id         AS id,
                   a.title      AS title,
                   a.body       AS body,
                   a.severity   AS severity,
                   a.audience   AS audience,
                   a.starts_at  AS startsAt,
                   a.ends_at    AS endsAt,
                   a.is_active  AS isActive,
                   a.created_at AS createdAt
              FROM announcements a
             WHERE a.created_by = :createdBy
               AND a.title = :title
               AND a.starts_at = :startsAt
             ORDER BY a.id DESC
             LIMIT 1
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /** UC-21: every announcement, newest first - including retired and out-of-window rows. */
    @Transactional(readOnly = true)
    public List<AdminAnnouncementRow> findAll() {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload returns a raw `Query`, so the element type is
        // known here and nowhere else. Confined to this local declaration - every row is mapped
        // through toAnnouncementRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ALL, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminAnnouncementViewDao::toAnnouncementRow).toList();
    }

    /** One announcement, or empty when the id does not exist. */
    @Transactional(readOnly = true)
    public Optional<AdminAnnouncementRow> findOne(Long id) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("id", id)
                .getResultList();

        return rows.stream().map(AdminAnnouncementViewDao::toAnnouncementRow).findFirst();
    }

    /**
     * The announcement just created by this administrator, to build the {@code 201} response from.
     *
     * <p>Called in the same transaction as the insert - see the query's note on why this is read
     * back by identity rather than by {@code LAST_INSERT_ID()}.
     */
    @Transactional(readOnly = true)
    public Optional<AdminAnnouncementRow> findCreatedBy(Long createdBy, String title, LocalDateTime startsAt) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CREATED, Tuple.class)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("startsAt", startsAt)
                .getResultList();

        return rows.stream().map(AdminAnnouncementViewDao::toAnnouncementRow).findFirst();
    }

    /**
     * One row as the projection carries it.
     *
     * <p>{@code severity} and {@code audience} are resolved by name, so a value the enum does not
     * know about fails here rather than being read as some other member. {@code is_active} is read
     * as a {@code Number} and tested against zero rather than as a boolean, because MySQL's
     * {@code TINYINT(1)} is not a JDBC boolean on every path - the same treatment
     * {@code findCreatedBy}'s siblings in other modules use for the column.
     */
    private static AdminAnnouncementRow toAnnouncementRow(Tuple row) {
        return new AdminAnnouncementRow(
                ((Number) row.get("id")).longValue(),
                row.get("title", String.class),
                row.get("body", String.class),
                AnnouncementSeverity.valueOf(row.get("severity", String.class)),
                AnnouncementAudience.valueOf(row.get("audience", String.class)),
                AdminTupleValues.toLocalDateTime(row.get("startsAt", java.sql.Timestamp.class)),
                AdminTupleValues.toLocalDateTime(row.get("endsAt", java.sql.Timestamp.class)),
                AdminTupleValues.toBoolean(row.get("isActive")),
                AdminTupleValues.toLocalDateTime(row.get("createdAt", java.sql.Timestamp.class)));
    }
}
