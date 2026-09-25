package com.campuscoin.admin.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.entity.AdminUserRow;
import com.campuscoin.auth.entity.AccountStatus;
import com.campuscoin.auth.entity.UserRole;

/**
 * Reads the account list an administrator manages (UC-22 B1).
 *
 * <p><b>What this query does not select is the point of it.</b> {@code password_hash} and
 * {@code token_version} are absent from the SQL, absent from {@link AdminUserRow}, and absent from
 * every response the module can build - three independent places, so no single later change can
 * publish either. {@code token_version} is the sharper of the two: it is the whole of a JWT's
 * {@code tv} claim, so a caller who knew it could tell whether a stolen token was still live. The
 * full justification of the exclusion list is on the projection; this class is where it is enforced.
 *
 * <p><b>Native and projected by alias rather than mapped as an entity.</b> {@code User} exists and is
 * mapped, but it carries {@code passwordHash} and {@code tokenVersion} as fields, and a DAO that
 * returned it would put the exclusion list at the mercy of whichever mapper ran next. Selecting
 * aliases into a record makes the disclosure decision structural - the same reason
 * {@code DashboardViewDao} and {@code TipViewDao} are written this way.
 *
 * <p><b>Both roles are listed, not only students.</b> {@code role} is published so the client can
 * tell them apart, and UC-22's user management covers every account - an administrator's own
 * colleagues are accounts too. Filtering to students would also make the module's own test target
 * (a second administrator) invisible in the list that acts on it.
 *
 * <p>Ordered by {@code id}, which is the primary key and therefore already unique and already
 * ordered: the read is an index scan with no sort, and it is stable between two identical calls.
 * {@code created_at} would say nearly the same thing - it defaults to insert time - while needing a
 * sort the table has no index for.
 *
 * <p>A DAO rather than a Spring Data repository because the result is a projection rather than a
 * managed entity, and because the column list is the security boundary this class exists to draw.
 */
@Repository
public class AdminUserViewDao {

    /** UC-22 B1: every account, with the columns UC-22 publishes and no others. */
    private static final String SELECT_USERS = """
            SELECT u.id            AS id,
                   u.email         AS email,
                   u.full_name     AS fullName,
                   u.role          AS role,
                   u.status        AS status,
                   u.academic_year AS academicYear,
                   u.last_login_at AS lastLoginAt,
                   u.created_at    AS createdAt
              FROM users u
             ORDER BY u.id ASC
            """;

    /** The same projection for one account, so a write's response shows what the list shows. */
    private static final String SELECT_ONE = """
            SELECT u.id            AS id,
                   u.email         AS email,
                   u.full_name     AS fullName,
                   u.role          AS role,
                   u.status        AS status,
                   u.academic_year AS academicYear,
                   u.last_login_at AS lastLoginAt,
                   u.created_at    AS createdAt
              FROM users u
             WHERE u.id = :id
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Every account on the system, oldest first.
     *
     * <p>An empty list cannot happen against the real schema - the seeded administrator is always
     * present, and {@code sp_set_user_status} has no way to remove a row (VĐ-06) - but it is still a
     * list rather than a missing resource, so the service does not turn it into a {@code 404}.
     */
    @Transactional(readOnly = true)
    public List<AdminUserRow> findAll() {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to one local declaration -
        // every row is mapped through toAdminUserRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_USERS, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminUserViewDao::toAdminUserRow).toList();
    }

    /**
     * One account, for the response to a write that changed it.
     *
     * <p>Used after {@code sp_set_user_status} so the caller is shown the account in its new state
     * rather than only an acknowledgement. The service has already established that the id exists -
     * by reading it before the write - so an empty result here means the row was removed in between,
     * which the service reports as a server-side fault rather than as a {@code 404}, exactly as
     * {@code BookmarkService.loadForResponse} does for the same reason.
     */
    @Transactional(readOnly = true)
    public Optional<AdminUserRow> findOne(Long id) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("id", id)
                .getResultList();

        return rows.stream().map(AdminUserViewDao::toAdminUserRow).findFirst();
    }

    /**
     * One row as the projection carries it.
     *
     * <p>The two ENUM columns are resolved by name, so a value the enum does not know about fails
     * here rather than being read as some other member or as null.
     */
    private static AdminUserRow toAdminUserRow(Tuple row) {
        return new AdminUserRow(
                ((Number) row.get("id")).longValue(),
                row.get("email", String.class),
                row.get("fullName", String.class),
                UserRole.valueOf(row.get("role", String.class)),
                AccountStatus.valueOf(row.get("status", String.class)),
                row.get("academicYear", String.class),
                toLocalDateTime(row.get("lastLoginAt", java.sql.Timestamp.class)),
                toLocalDateTime(row.get("createdAt", java.sql.Timestamp.class)));
    }

    /**
     * A MySQL {@code DATETIME} as a {@link LocalDateTime}.
     *
     * <p>The driver hands a {@code DATETIME} over as a {@link java.sql.Timestamp} whose epoch value
     * carries the JVM's default zone; {@code toLocalDateTime()} reads the wall-clock fields back,
     * which are the ones the column holds. The database session is pinned to the same zone the
     * application judges days in, so the value is never reinterpreted on the way out - the
     * arrangement {@code DashboardViewDao} uses.
     */
    private static LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
