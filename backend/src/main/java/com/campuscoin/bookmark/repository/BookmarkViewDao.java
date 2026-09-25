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

/**
 * Reads a bookmark together with the tip it saves (UC-19 B4).
 *
 * <p><b>Why this is a native query and not a repository method.</b> The list endpoint's contract is
 * that the saved advice travels with the bookmark, so the read spans {@code bookmarks} and
 * {@code user_tips}. There is no view for that join, and this module does not add one: UC-19's anchor
 * row in {@code docs/ERD.md} names {@code bookmarks} and its two triggers, and a view would be a
 * second definition of the list's shape sitting in the database beside the one the API publishes. The
 * join is therefore written here, where the projection and the SQL are read together.
 *
 * <p><b>The query is not a second expression of a rule.</b> It filters by the caller and orders by
 * {@code created_at DESC, id DESC}. The first is BR-02's ownership, which this API applies in every
 * query rather than post-checking. The second is UC-19's own presentation requirement - a list of
 * things saved, so the most recent first - and it is a tie-break rather than a ranking: {@code id} is
 * the second key so two bookmarks saved in the same second cannot swap places between two calls of the
 * same endpoint, which is the same reason {@code TipViewDao} adds {@code tip_id} to its order.
 *
 * <p><b>{@code ix_bookmark_user (user_id, created_at)} is the index this reads on.</b> The filter and
 * the ordering key together are exactly that index's two columns, so the list is a range scan rather
 * than a sort of the whole table.
 *
 * <p><b>Projected by alias rather than mapped as an entity.</b> {@code createNativeQuery(..., Tuple)}
 * lets each column be read by the name the query gave it, so a column added to either table cannot
 * break this class - the same reason {@code TipViewDao} and {@code DashboardViewDao} are written this
 * way. {@code b.insight_id} is not selected: the insight branch is UC-17, inside the locked module 12,
 * and nothing in this build can create or read one.
 *
 * <p>A DAO rather than a Spring Data repository because every query is native and every result is a
 * projection rather than a managed entity.
 */
@Repository
public class BookmarkViewDao {

    /**
     * UC-19 B4: the caller's saved items, with the tip each one points at, newest first.
     *
     * <p>{@code LEFT JOIN} rather than {@code JOIN}, even though every row this module writes has a
     * {@code tip_id}. An inner join would silently drop a row whose target could not be joined - and a
     * bookmark whose tip cannot be read is not a bookmark that does not exist. Turning it into a
     * missing row would hide the problem; the projection reads the tip's columns as nullable, so such a
     * row still appears (its tip fields null) and remains a row the student can act on. No such row can
     * exist today: {@code fk_bookmark_tip} is a foreign key with {@code ON DELETE CASCADE}, so a
     * deleted tip takes its bookmark with it.
     */
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

    /** The same projection for one bookmark, so a write's response shows what the list shows. */
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

    /**
     * UC-19 B4: every item the caller has saved, newest first.
     *
     * <p>An empty list is a real answer: a student who has saved nothing has an empty reference list,
     * which is a fact about their own data rather than a missing resource. The service does not turn
     * that into a {@code 404}.
     *
     * <p>No filter on the tip's state. A tip the student later dismisses on the tips screen stays in
     * this list, because keeping an item and displaying it are different acts (VĐ-03) and B4's remedy
     * for an item no longer wanted is un-marking it. Filtering here would remove a row the student did
     * not remove, and the count they see would disagree with the number of deletions it takes to empty
     * the list.
     */
    @Transactional(readOnly = true)
    public List<BookmarkRow> findByUserId(Long userId) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration -
        // every row is mapped through toBookmarkRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_BOOKMARKS, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(BookmarkViewDao::toBookmarkRow).toList();
    }

    /**
     * UC-19: one of the caller's saved items with its tip, for a write's response.
     *
     * <p>The bookmark has just been created or its note just changed, and the caller is shown what the
     * list would show rather than only the field they touched - so a client can render the card without
     * a follow-up request, and the note it displays is the one the database stored rather than the one
     * it sent.
     *
     * <p>The {@code user_id} predicate is not redundant with the service's ownership check. It is what
     * keeps this read subject to the same rule as the list, so there is no query in this module that
     * could return another student's row.
     */
    @Transactional(readOnly = true)
    public Optional<BookmarkRow> findOne(Long userId, Long bookmarkId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("bookmarkId", bookmarkId)
                .getResultList();

        return rows.stream().map(BookmarkViewDao::toBookmarkRow).findFirst();
    }

    /**
     * One row of the join as the projection carries it.
     *
     * <p>The tip's columns are read as nullable because the join is a {@code LEFT JOIN} - see the
     * query's note. {@code tipState} is resolved by name, so a state this enum does not know about
     * fails here rather than being read as some other member.
     */
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

    /**
     * A MySQL {@code DATETIME} as a {@link LocalDateTime}.
     *
     * <p>The JDBC driver hands a {@code DATETIME} over as a {@link java.sql.Timestamp}, which carries
     * the JVM's default zone through its epoch value; {@link java.sql.Timestamp#toLocalDateTime()}
     * reads the wall-clock fields back, which are the ones the column holds. The database session is
     * pinned to {@code +07:00} and the application judges "today" in the same zone, so the value is
     * never reinterpreted on the way out - the arrangement {@code DashboardViewDao} uses for the same
     * reason.
     */
    private static LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
