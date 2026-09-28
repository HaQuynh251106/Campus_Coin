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

@Repository
public class AdminAnnouncementViewDao {

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

    @Transactional(readOnly = true)
    public List<AdminAnnouncementRow> findAll() {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ALL, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminAnnouncementViewDao::toAnnouncementRow).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AdminAnnouncementRow> findOne(Long id) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("id", id)
                .getResultList();

        return rows.stream().map(AdminAnnouncementViewDao::toAnnouncementRow).findFirst();
    }

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
