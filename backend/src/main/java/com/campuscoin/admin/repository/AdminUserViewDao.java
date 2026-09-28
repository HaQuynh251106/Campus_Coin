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

@Repository
public class AdminUserViewDao {

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

    @Transactional(readOnly = true)
    public List<AdminUserRow> findAll() {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_USERS, Tuple.class)
                .getResultList();

        return rows.stream().map(AdminUserViewDao::toAdminUserRow).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AdminUserRow> findOne(Long id) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("id", id)
                .getResultList();

        return rows.stream().map(AdminUserViewDao::toAdminUserRow).findFirst();
    }

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

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
