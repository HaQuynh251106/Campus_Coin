package com.campuscoin.categorisation.repository;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.categorisation.entity.TransactionCategorisationRow;
import com.campuscoin.category.entity.CategoryType;
import com.campuscoin.common.jdbc.JdbcValues;

/**
 * Reads a record's category and writes what the system suggested about it (UC-08).
 *
 * <p><b>Why the write is a plain statement and not a procedure.</b> As with {@code CategoryRuleDao},
 * no procedure exists for the three {@code ai_} columns - they were written into the schema when it was
 * designed and nothing has ever read or written them. What is worth stating is that the rules they
 * carry are not therefore abandoned, because {@code trg_transactions_before_update} already enforces
 * them: it calls {@code sp_validate_transaction} with {@code NEW.ai_suggested_category_id}, and that
 * procedure raises BR-13 when the suggested category belongs to another student. So the update below
 * cannot point a record at somebody else's category even if this class were rewritten to try -
 * {@code fk_txn_ai_category} makes the category exist, {@code ck_txn_ai_conf} bounds the confidence,
 * and the trigger makes the owner match. The three statements that guard the columns are the database's,
 * and this class is only the writer.
 *
 * <p><b>BR-13 also holds a step earlier, and by construction.</b> The category written here is never
 * anything an external provider named: {@code CategorySuggester} resolves a proposal against
 * {@code CategoryRepository#findVisibleToUser}, so a name the student may not file under is discarded
 * before it becomes anything. The trigger is the backstop, not the only check.
 *
 * <p><b>Reads narrow on {@code user_id} and on {@code is_deleted = 0}.</b> A record in the trash is not
 * categorised and cannot be: it is not a fact about the student's spending any more, and writing an
 * advisory column on it would mean a suggestion could outlive the record it describes. The narrowing
 * also means another student's id is indistinguishable from one that does not exist, which is what
 * {@code CategorisationService} answers as {@code 404} - the same policy
 * {@code RecentActivityService} records for the same reason.
 *
 * <p><b>Native queries rather than module 4's entity, and deliberately.</b> {@code Transaction}
 * leaves these three columns unmapped, and {@code TransactionApiIT} pins that a create or update body
 * cannot set them - so module 4's own write path is incapable of touching them. Mapping them here would
 * be a second, competing way to write the same columns, and the point of leaving them unmapped is that
 * there is exactly one.
 */
@Repository
public class TransactionCategorisationDao {

    /**
     * UC-08: one of the caller's own live records, with the category it is filed under.
     *
     * <p>The category's type is joined in rather than looked up separately because a learned rule stores
     * it, and a second round trip to answer a question this one already answers would be a second place
     * to get it wrong. The join is on the record's own mandatory category, so it can never drop the row.
     *
     * <p>The description comes back as stored - an envelope, not plaintext. Naming the projection
     * component {@code encryptedDescription} is what keeps that visible to a caller that would otherwise
     * publish ciphertext.
     */
    private static final String SELECT_ONE = """
            SELECT t.category_id             AS categoryId,
                   c.type                    AS categoryType,
                   t.description             AS encryptedDescription,
                   t.ai_suggested_category_id AS aiSuggestedCategoryId,
                   t.ai_confidence           AS aiConfidence,
                   t.ai_overridden           AS aiOverridden
              FROM transactions t
              JOIN categories c ON c.id = t.category_id
             WHERE t.id = :transactionId
               AND t.user_id = :userId
               AND t.is_deleted = 0
            """;

    /**
     * UC-08: writes what the system proposed for one record, and whether the student kept it.
     *
     * <p><b>Three columns, and the record's own category is not one of them.</b> BR-13 is the reason:
     * a suggestion is stored beside the record and never as the record's category, so the student's own
     * filing is what decides where the money is counted and a system proposal is only ever a note on it.
     * This statement cannot move a student's spending between categories, which is also why it can be
     * written at all: the worst a fault here can do is leave a stale advisory note.
     *
     * <p>{@code ai_suggested_category_id} and {@code ai_confidence} are set to null when nothing was
     * proposed, which is a real state and not a failure - a record with no description has nothing to
     * categorise. Writing the nulls rather than leaving the previous values is what stops a suggestion
     * from surviving the text it was made about.
     *
     * <p>The predicate repeats the read's narrowing rather than trusting it: {@code user_id} and
     * {@code is_deleted = 0} are both checked here too, so a record trashed between the read and this
     * write is not touched. The caller compares the affected-row count with the one it expected, because
     * a write that matched nothing means the record moved underneath the request rather than that the
     * write was unnecessary - the unnecessary cases never reach here.
     *
     * @return the number of rows changed; {@code 1} is the only acceptable answer
     */
    private static final String UPDATE_SUGGESTION = """
            UPDATE transactions
               SET ai_suggested_category_id = :suggestedCategoryId,
                   ai_confidence            = :confidence,
                   ai_overridden            = :overridden
             WHERE id = :transactionId
               AND user_id = :userId
               AND is_deleted = 0
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * UC-08: one of the caller's own live records.
     *
     * <p>Empty means one of two things, and they are answered identically: no such record, or it belongs
     * to somebody else. Telling them apart would let a caller enumerate other students' record
     * identifiers one request at a time, so the caller turns both into the same {@code 404} - the policy
     * {@code TransactionRepository#findActiveByIdAndUserId} records for the same table.
     */
    @Transactional(readOnly = true)
    public Optional<TransactionCategorisationRow> find(Long userId, Long transactionId) {
        @SuppressWarnings("unchecked")
        java.util.List<Tuple> rows = entityManager.createNativeQuery(SELECT_ONE, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .getResultList();

        return rows.stream().map(TransactionCategorisationDao::toRow).findFirst();
    }

    /** UC-08: writes the proposal and the verdict, and reports whether the row was there to write. */
    @Transactional
    public int updateSuggestion(Long userId, Long transactionId, Long suggestedCategoryId,
                                BigDecimal confidence, boolean overridden) {
        return entityManager.createNativeQuery(UPDATE_SUGGESTION)
                .setParameter("userId", userId)
                .setParameter("transactionId", transactionId)
                .setParameter("suggestedCategoryId", suggestedCategoryId)
                .setParameter("confidence", confidence)
                .setParameter("overridden", overridden)
                .executeUpdate();
    }

    /**
     * One record as the projection carries it.
     *
     * <p>{@code ai_overridden} is read through {@link JdbcValues#toBoolean} rather than cast directly.
     * The column is {@code TINYINT(1)}, which the MySQL driver reports as a JDBC {@code Boolean} -
     * casting it to {@link Number}, which is what the single digit suggests, throws
     * {@code ClassCastException} and turns the whole endpoint into a {@code 500}. That bug has already
     * been fixed twice in this project, once per module that read such a column, so the helper now lives
     * in {@code com.campuscoin.common} and this is the third reader to use it.
     *
     * <p>{@code ai_suggested_category_id} and {@code ai_confidence} are legitimately null on a record
     * nobody has proposed anything for, so they are read as nullable rather than coerced.
     */
    private static TransactionCategorisationRow toRow(Tuple row) {
        Boolean overridden = JdbcValues.toBoolean(row.get("aiOverridden"));

        return new TransactionCategorisationRow(
                ((Number) row.get("categoryId")).longValue(),
                CategoryType.valueOf(row.get("categoryType", String.class)),
                row.get("encryptedDescription", String.class),
                toLong(row.get("aiSuggestedCategoryId")),
                row.get("aiConfidence", BigDecimal.class),
                overridden != null && overridden);
    }

    /** A nullable big integer column as a {@link Long}, since {@code null} is a value here. */
    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
