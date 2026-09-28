package com.campuscoin.bookmark.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.bookmark.entity.BookmarkItemType;
import com.campuscoin.bookmark.entity.BookmarkRow;
import com.campuscoin.tips.entity.TipState;

@Repository
public class BookmarkViewDao {

    private static final String SELECT_BOOKMARKS = """
            SELECT b.id             AS bookmarkId,
                   b.item_type      AS itemType,
                   b.tip_id         AS tipId,
                   b.note           AS note,
                   b.created_at     AS createdAt,
                   t.title          AS tipTitle,
                   t.body           AS tipBody,
                   t.potential_saving AS tipPotentialSaving,
                   t.state          AS tipState,
                   t.period_month   AS tipMonth
              FROM bookmarks b
              LEFT JOIN user_tips t ON t.id = b.tip_id
             WHERE b.user_id = :userId
             ORDER BY b.created_at DESC, b.id DESC
            """;

    private static final String SELECT_ONE = """
            SELECT b.id             AS bookmarkId,
                   b.item_type      AS itemType,
                   b.tip_id         AS tipId,
                   b.note           AS note,
                   b.created_at     AS createdAt,
                   t.title          AS tipTitle,
                   t.body           AS tipBody,
                   t.potential_saving AS tipPotentialSaving,
                   t.state          AS tipState,
                   t.period_month   AS tipMonth
              FROM bookmarks b
              LEFT JOIN user_tips t ON t.id = b.tip_id
             WHERE b.user_id = :userId
               AND b.id = :bookmarkId
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<BookmarkRow> findByUserId(Long userId) {

        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BOOKMARKS, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(BookmarkViewDao::toBookmarkRow).toList();
    }

    @Transactional(readOnly = true)
    public Optional<BookmarkRow> findOne(Long userId, Long bookmarkId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("bookmarkId", bookmarkId)
                .getResultList();

        return rows.stream().map(BookmarkViewDao::toBookmarkRow).findFirst();
    }

    private static BookmarkRow toBookmarkRow(Tuple row) {
        return new BookmarkRow(
                ((Number) row.get("bookmarkId")).longValue(),
                BookmarkItemType.valueOf(row.get("itemType", String.class)),
                row.get("tipId") == null ? null : ((Number) row.get("tipId")).longValue(),
                row.get("note", String.class),
                toLocalDateTime(row.get("createdAt", java.sql.Timestamp.class)),
                row.get("tipTitle", String.class),
                row.get("tipBody", String.class),
                row.get("tipPotentialSaving", BigDecimal.class),
                row.get("tipState") == null ? null : TipState.valueOf(row.get("tipState", String.class)),
                row.get("tipMonth") == null
                        ? null
                        : row.get("tipMonth", java.sql.Date.class).toLocalDate());
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
