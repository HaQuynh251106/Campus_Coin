package com.campuscoin.recent.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.recent.entity.RecentAction;
import com.campuscoin.recent.entity.RecentActivityRow;

/**
 * Reads UC-26's list from {@code v_user_recent_activity}.
 *
 * <p><b>Why the view answers this and Java does not.</b> The view already joins {@code categories} for
 * the type the client needs to colour a row, and already applies {@code WHERE t.is_deleted = 0} so a
 * trashed transaction is not offered. Both are UC-26's own words: the list is "the last few things I
 * looked at", and a record in the trash is not something a student can go back to. Restating either
 * here would be a second definition of when an entry is visible - the reasoning {@code TipViewDao}
 * records for {@code state <> 'DISMISSED'}.
 *
 * <p><b>"Most recent first" is the whole of UC-26's ordering, and the query asks for it.</b> SQL makes
 * no promise about the order of an unordered result, so {@code ORDER BY occurred_at DESC} is named
 * here. The two keys after it are tie-breaks rather than a second ranking: the upsert writes
 * {@code NOW()} with second precision, so two views inside the same second - and the two rows one
 * transaction has when it was both viewed and edited - would otherwise be free to swap places between
 * two calls of the same endpoint. {@code transaction_id} then {@code action} orders every case the
 * data can produce.
 *
 * <p><b>One consequence of that precision, stated rather than hidden:</b> within a single second the
 * order falls back to the transaction's id and then to the action - so an entry for the lower
 * transaction id comes first, and for one transaction the {@code VIEWED} row comes before the
 * {@code EDITED} one. Both are deterministic, and both are <em>not</em> the order the requests
 * arrived in. That is the honest limit of a {@code DATETIME} column: {@code occurred_at} has second
 * precision, so two actions taken inside one second are, as far as the stored data is concerned,
 * simultaneous. Ordering them sub-second would mean widening the column to {@code DATETIME(3)} - a
 * schema change this module does not make, because the column is not its to redefine and the visible
 * effect is confined to actions inside the same second. What the tie-break is for is repeatability,
 * which is all a caller can observe: the same data returns the same order every time.
 *
 * <p><b>{@code LIMIT} is bound rather than written into the SQL.</b> UC-26 says "the last few", not a
 * fixed number, and the size is the caller's - {@code GET /api/v1/recent-activity} defaults it. A
 * literal would make the one thing the endpoint is allowed to vary the one thing it could not.
 *
 * <p><b>Projected by alias rather than mapped as an entity.</b> {@code createNativeQuery(..., Tuple)}
 * reads each column by the name this query gave it, so a column added to or reordered in the view
 * cannot break this class - the shape {@code TipViewDao}, {@code BookmarkViewDao} and
 * {@code DashboardViewDao} all use. A view cannot be written to, and nothing here tries: the write is
 * {@link RecentActivityProcedureDao}.
 */
@Repository
public class RecentActivityViewDao {

    /**
     * UC-26: the caller's recent entries, newest first.
     *
     * <p>{@code user_id} is the first predicate and not a filter applied afterwards: BR-02 says the
     * list is the student's own, so ownership is a property of the query rather than a check at the
     * call site - the arrangement every ownership-scoped DAO here uses.
     */
    private static final String SELECT_RECENT = """
            SELECT v.transaction_id AS transactionId,
                   v.action         AS action,
                   v.occurred_at    AS occurredAt,
                   v.category_id    AS categoryId,
                   v.type           AS categoryType,
                   v.amount         AS amount,
                   v.description    AS encryptedDescription,
                   v.txn_date       AS txnDate
              FROM v_user_recent_activity v
             WHERE v.user_id = :userId
             ORDER BY v.occurred_at DESC, v.transaction_id DESC, v.action DESC
             LIMIT :limit
            """;

    /**
     * UC-26: one entry, just written, read back on the caller's own key.
     *
     * <p>Backs the {@code POST} response. The entry a client is handed after recording a view is the
     * row the list would show it, read from the same view rather than assembled from the request - so
     * the category, the amount and the date come from the database and cannot disagree with what a
     * later {@code GET} returns. It is the shape {@code BookmarkViewDao#findOne} gives the same
     * problem.
     *
     * <p>Narrowed by the whole of {@code uk_recent} - user, transaction <em>and</em> action - because
     * that triple is the row's identity: the caller may hold two entries for one transaction, and
     * naming only the transaction would fetch the wrong one. Empty means the row this request just
     * wrote is not readable, which is a fault rather than a missing resource; the service decides that,
     * not this method.
     */
    private static final String SELECT_ONE = """
            SELECT v.transaction_id AS transactionId,
                   v.action         AS action,
                   v.occurred_at    AS occurredAt,
                   v.category_id    AS categoryId,
                   v.type           AS categoryType,
                   v.amount         AS amount,
                   v.description    AS encryptedDescription,
                   v.txn_date       AS txnDate
              FROM v_user_recent_activity v
             WHERE v.user_id = :userId
               AND v.transaction_id = :transactionId
               AND v.action = :action
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-26: the caller's most recent entries.
     *
     * <p>An empty list is a real answer: a student who has just registered has looked at nothing, and
     * "you have not opened anything yet" is a fact about their own data rather than a missing
     * resource, so the service does not turn it into a {@code 404}.
     */
    @Transactional(readOnly = true)
    public List<RecentActivityRow> findRecent(Long userId, int limit) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration -
        // the result is immediately mapped through toRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_RECENT, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream().map(RecentActivityViewDao::toRow).toList();
    }

    /** UC-26: the entry a record request just made, as the list would show it. */
    @Transactional(readOnly = true)
    public java.util.Optional<RecentActivityRow> findOne(Long userId, Long transactionId,
                                                         RecentAction action) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .setParameter("action", action.name())
                .getResultList();

        return rows.stream().map(RecentActivityViewDao::toRow).findFirst();
    }

    /**
     * One row of the recent-activity view as the projection carries it.
     *
     * <p>{@code category_id} is read defensively even though the view's {@code JOIN} makes it
     * non-null: a projection that assumed a column's nullability would throw on a {@code NULL} where
     * the honest answer is a row the mapper can decide about. {@code description} is left as stored -
     * it is an AES-256-GCM envelope, and decrypting it is the mapper's job.
     */
    private static RecentActivityRow toRow(Tuple row) {
        return new RecentActivityRow(
                ((Number) row.get("transactionId")).longValue(),
                RecentAction.valueOf(row.get("action", String.class)),
                toLocalDateTime(row.get("occurredAt", java.sql.Timestamp.class)),
                row.get("categoryId") == null ? null : ((Number) row.get("categoryId")).longValue(),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("amount", java.math.BigDecimal.class),
                row.get("encryptedDescription", String.class),
                row.get("txnDate", java.sql.Date.class).toLocalDate());
    }

    private static java.time.LocalDateTime toLocalDateTime(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
