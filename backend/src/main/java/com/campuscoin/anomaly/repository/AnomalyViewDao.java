package com.campuscoin.anomaly.repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.anomaly.entity.AnomalyFlagType;
import com.campuscoin.anomaly.entity.CategoryAmountStats;
import com.campuscoin.anomaly.entity.FlaggedTransactionRow;
import com.campuscoin.category.entity.CategoryType;

/**
 * Reads the transactions UC-24 works on (UC-24).
 *
 * <p><b>There is no view here, and the reads name {@code transactions} directly.</b> That is unusual
 * for this project and it is deliberate. {@code db/02_views.sql} holds no view over the three flag
 * columns, and the reason is that they have never been read: UC-24 is module 12 and the columns were
 * written into the schema when it was designed. Adding a view now would be adding one for a single
 * feature rather than because two readers share it - the test every other view in this project
 * passes. Three queries against the table, all narrowed by {@code user_id} first, are what this
 * module actually needs.
 *
 * <p><b>The three reads and why they are three.</b> One is the list a student is shown, one is the
 * set of records a scan examines, and one gives each category's own totals. The last exists so the
 * detector never has to ask "what is this student's average in this category" once per record: the
 * scan is bounded by the student's whole history rather than by a page, so a per-row average would be
 * a query per row. The grouped read answers it for every category in one round trip.
 *
 * <p><b>All three narrow by {@code user_id} first.</b> BR-02 is a property of the query here rather
 * than a check at the call site, which is what makes it impossible for a scan to see - and therefore
 * to flag, or to compare against - anybody else's record. {@code ix_txn_user_cat_date} is the index
 * the grouped read was built for; {@code ix_txn_flagged} serves the list.
 *
 * <p><b>A trashed record is invisible to all three.</b> {@code is_deleted = 0} is stated in each
 * query, and the consequence is worth naming: a record in the trash is not a baseline the student
 * spent against, and is not a duplicate of anything either. So trashing one of a pair clears the
 * other on the next scan, with no repair step, because the scan simply stops seeing the trashed one.
 *
 * <p>Projected by alias into records rather than mapped as entities. The transaction module
 * deliberately leaves the three flag columns unmapped ({@code Transaction}'s class note records why),
 * and this module does not change that: a projection cannot be flushed, so no JPA write can compete
 * with {@code sp_flag_transaction} over the columns the procedure owns.
 */
@Repository
public class AnomalyViewDao {

    /**
     * UC-24: the caller's flagged records, most recent first.
     *
     * <p>Ordered by {@code txn_date} and then {@code id}, unlike the transaction list, which orders by
     * date and then id in the descending direction as well. What a student is being shown is "which of
     * my recent records look wrong", so the newest are the ones worth looking at first.
     *
     * <p>{@code is_flagged = 1} rather than a join or a sub-select: the column exists precisely to
     * answer this question, and {@code ix_txn_flagged (user_id, is_flagged)} is the index it was added
     * for. The {@code flag_type} column is read beside it rather than used as a predicate - the flag
     * is the fact, the type is what kind.
     */
    private static final String SELECT_FLAGGED = """
            SELECT t.id           AS transactionId,
                   t.category_id  AS categoryId,
                   c.name         AS categoryName,
                   c.type         AS categoryType,
                   t.amount       AS amount,
                   t.txn_date     AS txnDate,
                   t.description  AS encryptedDescription,
                   t.is_flagged   AS isFlagged,
                   t.flag_type    AS flagType,
                   t.flag_note    AS flagNote
              FROM transactions t
              JOIN categories c ON c.id = t.category_id
             WHERE t.user_id = :userId
               AND t.is_deleted = 0
               AND t.is_flagged = 1
             ORDER BY t.txn_date DESC, t.id DESC
             LIMIT :limit
            """;

    /**
     * UC-24: every live record of the caller's, for the detector to examine.
     *
     * <p>No date bound, and that is a decision rather than an omission. The detector answers two
     * questions - "is this a duplicate of another" and "is this large for its own category" - and both
     * are relative to the student's own history: the average a record is measured against is drawn from
     * every record the student has, so a scan that looked only at a window would measure each record
     * against a different, arbitrarily chosen average. It would also leave the flags outside the window
     * untouched, so the same record could be flagged or cleared depending on which window the client
     * happened to send. One bound - the student's own account - is the only bound this feature has.
     *
     * <p>Ordered by {@code id}, which is the order the rows were created in. That is not for display:
     * the duplicate rule reports the <em>earlier</em> record of a pair, so it needs a stable, total
     * order to pick one, and {@code id} gives it one that a re-run reproduces. Ordering by
     * {@code txn_date} would not, because two records can share a date.
     *
     * <p>The category's name and type come along for two reasons: the detector names the category in
     * the note it writes, and the response renders a row without a second query.
     */
    private static final String SELECT_SCAN_CANDIDATES = """
            SELECT t.id           AS transactionId,
                   t.category_id  AS categoryId,
                   c.name         AS categoryName,
                   c.type         AS categoryType,
                   t.amount       AS amount,
                   t.txn_date     AS txnDate,
                   t.description  AS encryptedDescription,
                   t.is_flagged   AS isFlagged,
                   t.flag_type    AS flagType,
                   t.flag_note    AS flagNote
              FROM transactions t
              JOIN categories c ON c.id = t.category_id
             WHERE t.user_id = :userId
               AND t.is_deleted = 0
             ORDER BY t.id
            """;

    /**
     * UC-24: how many live records the caller has in each category, and what they add up to.
     *
     * <p><b>The count and the sum, not the average.</b> The detector has to exclude the record under
     * examination from the baseline it is compared against, and it can only do that if it has the raw
     * totals: one 900.00 lunch would otherwise raise its own category's average far enough to excuse
     * itself. The division therefore happens once, in {@code AnomalyDetector}, where a test can pin it.
     *
     * <p>{@code SUM(amount)} is over plaintext amounts, which is OB-013's deliberate deferral and not
     * something this query could change. What matters here is that the sum never leaves the server: it
     * is a baseline, and the response publishes the record's own amount and never the category's.
     *
     * <p>Order is not specified and not needed - the result is read into a map keyed by
     * {@code category_id}, and every key is unique because the group by says so.
     */
    private static final String SELECT_CATEGORY_STATS = """
            SELECT t.category_id AS categoryId,
                   COUNT(*)      AS recordCount,
                   SUM(t.amount) AS totalAmount
              FROM transactions t
             WHERE t.user_id = :userId
               AND t.is_deleted = 0
             GROUP BY t.category_id
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-24: the caller's flagged records, most recent first.
     *
     * <p>An empty list is a real answer - most students will have nothing flagged, and "nothing looks
     * wrong" is a fact about their own data rather than a missing resource, so the service does not
     * turn it into a {@code 404}.
     */
    @Transactional(readOnly = true)
    public List<FlaggedTransactionRow> findFlagged(Long userId, int limit) {
        // The cast is a stated limitation of the JPA signature rather than a guess: the
        // `createNativeQuery(String, Class)` overload is declared to return a raw `Query`, so the
        // element type is known here and nowhere else. It is confined to this one local declaration -
        // every row is mapped through toRow, so no Tuple escapes this method.
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_FLAGGED, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream().map(AnomalyViewDao::toRow).toList();
    }

    /** UC-24: every live record of the caller's, in creation order, for the detector to examine. */
    @Transactional(readOnly = true)
    public List<FlaggedTransactionRow> findScanCandidates(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_SCAN_CANDIDATES, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        return rows.stream().map(AnomalyViewDao::toRow).toList();
    }

    /** UC-24: each of the caller's categories' record count and total, keyed by category id. */
    @Transactional(readOnly = true)
    public Map<Long, CategoryAmountStats> findCategoryStats(Long userId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = entityManager.createNativeQuery(SELECT_CATEGORY_STATS, Tuple.class)
                .setParameter("userId", userId)
                .getResultList();

        Map<Long, CategoryAmountStats> stats = new HashMap<>();
        for (Tuple row : rows) {
            Long categoryId = ((Number) row.get("categoryId")).longValue();
            stats.put(categoryId, new CategoryAmountStats(
                    categoryId,
                    ((Number) row.get("recordCount")).longValue(),
                    row.get("totalAmount", BigDecimal.class)));
        }
        return stats;
    }

    /**
     * One row as the projection carries it.
     *
     * <p>{@code flag_type} is resolved by name, so a member the enum does not know about fails here
     * rather than being read as some other value. The column is {@code NOT NULL} with a default, so a
     * null would mean a hand-run {@code UPDATE} had written one - and treating it as {@code NONE}
     * would silently claim a record is unflagged when its own column says otherwise.
     */
    private static FlaggedTransactionRow toRow(Tuple row) {
        return new FlaggedTransactionRow(
                ((Number) row.get("transactionId")).longValue(),
                ((Number) row.get("categoryId")).longValue(),
                row.get("categoryName", String.class),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("amount", BigDecimal.class),
                row.get("txnDate", java.sql.Date.class).toLocalDate(),
                row.get("encryptedDescription", String.class),
                isFlagged(row.get("isFlagged")),
                AnomalyFlagType.valueOf(row.get("flagType", String.class)),
                row.get("flagNote", String.class));
    }

    /**
     * {@code transactions.is_flagged} as a {@code boolean}.
     *
     * <p><b>Both a {@link Boolean} and a {@link Number} are accepted, and the first is the one that
     * actually arrives.</b> MySQL Connector/J turns {@code tinyInt1isBit} on by default, which makes a
     * {@code TINYINT(1)} column a JDBC {@link Boolean} - so casting to {@link Number}, which reads
     * naturally from the single digit the column holds, throws {@code ClassCastException} and turns
     * every read here into a {@code 500}. That is the failure this method was written to fix, and the
     * module's first integration run is what surfaced it: the column type is not a boolean on every
     * path, and it is not a number on the default one either. The numeric branch stays because a
     * deployment can turn the property off in its JDBC URL, at which point the same column is an
     * {@code Integer}; accepting only one shape would make this DAO depend on a connection string.
     *
     * <p>The behaviour is the same as {@code admin}'s {@code AdminTupleValues.toBoolean}, which cannot
     * be reused because it is package-private - and the duplication is recorded rather than hidden: two
     * copies of a column reading is the shape that let the administration bug survive its own fix. If
     * a third module needs it, the helper moves to {@code com.campuscoin.common}.
     */
    private static boolean isFlagged(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && ((Number) value).intValue() != 0;
    }
}
