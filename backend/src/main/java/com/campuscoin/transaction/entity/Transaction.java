package com.campuscoin.transaction.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

import com.campuscoin.category.entity.Category;

/**
 * A row of {@code transactions}: one recorded movement of money (UC-07, UC-10).
 *
 * <p>There is deliberately no {@code type} field. {@code transactions} has no {@code type} column,
 * because the type is {@code categories.type} and BR-05 requires the two to agree - the only way
 * to guarantee that is to keep one source of truth. A transaction is income or expense according to
 * the category it is filed under, so {@link Category#getType()} answers that question and there is
 * no second value for it to drift away from.
 *
 * <p><b>Which columns are mapped.</b> Only the ones UC-07 and UC-10 need. The AI columns
 * ({@code ai_suggested_category_id}, {@code ai_confidence}, {@code ai_overridden}), the anomaly
 * flags ({@code is_flagged}, {@code flag_type}, {@code flag_note}) and the two origin links
 * ({@code recurring_rule_id}, {@code import_batch_id}) belong to UC-08, UC-09, UC-11 and UC-24, and
 * are left unmapped until those modules are built. That is not only a matter of scope: an unmapped
 * column can never be written by this module's statements, so an edit here cannot clear an AI
 * suggestion, a flag or a recurring rule's link. Leaving them out is what makes that guarantee
 * structural rather than a promise.
 *
 * <p><b>{@code @DynamicUpdate} is a correctness requirement, not an optimisation.</b> A plain
 * Hibernate {@code UPDATE} writes every mapped column back with the values read when the request
 * began. Here that would be actively wrong: {@code sp_soft_delete_transaction} and
 * {@code sp_restore_transaction} change {@code is_deleted} by SQL, so Hibernate's in-memory copy is
 * stale the moment another request deletes the row. An edit that wrote the whole row back would
 * un-delete it, and the transaction would silently return to every report. Restricting the
 * statement to the columns that actually changed removes that class of lost update.
 *
 * <p>{@code is_deleted} and {@code deleted_at} are therefore mapped {@code updatable = false}. The
 * two procedures own them (BR-09), and the mapping is what stops a JPA write from competing with
 * them. {@code @DynamicInsert} is not applied: an INSERT names every mapped column on purpose,
 * which is why the factory sets the ones the schema gives defaults for - an explicit {@code NULL}
 * would override the column default and then fail the NOT NULL constraint.
 */
@Entity
@Table(name = "transactions")
@DynamicUpdate
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The owning student.
     *
     * <p>A plain identifier rather than an association, as on {@code Category}: nothing here needs
     * the owner's row, and a {@code @ManyToOne} would load a full {@code User} - including its
     * password hash and token version - to answer a question about ownership. Ownership is enforced
     * in the query instead: every single-row lookup in {@code TransactionRepository} takes the
     * caller's id alongside the record's.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * The category the record is filed under.
     *
     * <p>Mapped as an association because the category's own values are part of what the client is
     * shown: the type (BR-05) and the name, icon and colour every list row renders. One query with
     * {@code JOIN FETCH} supplies them, where a plain id would cost either a second query or a
     * lookup table rebuilt on the client.
     *
     * <p>{@code FetchType.LAZY} is set explicitly - {@code @ManyToOne} defaults to EAGER - so that
     * reading a transaction for a purpose that does not need the category does not drag it in. The
     * queries that do need it say so with {@code JOIN FETCH}. Because {@code spring.jpa.open-in-view}
     * is false, a mapping that forgot to fetch would fail loudly rather than silently issuing an
     * extra query per row.
     *
     * <p>There is deliberately no cascade. A transaction never creates or deletes a category, and
     * {@code fk_txn_category} restricts a category that still has transactions from being deleted
     * (BR-07).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** BR-08: strictly positive. {@code ck_txn_amount} enforces it in the database as well. */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /**
     * The student's free-text note. As stored, and therefore as held on the entity, this is the
     * <em>ciphertext</em> - a Base64 AES-256-GCM envelope, not the words the student typed.
     *
     * <p>Encryption happens at the service boundary, not here: {@code TransactionService} encrypts
     * before the row is written and {@code TransactionMapper} decrypts on the way out, so nothing
     * outside those two sees an entity holding ciphertext for longer than a single request. A JPA
     * {@code AttributeConverter} would have put the key inside the entity lifecycle and quietly
     * affected every query, which is why there is none (section 10 of the encryption brief).
     *
     * <p>{@code length = 255} describes the plaintext the column accepts; the column itself is
     * {@code VARCHAR(2048)} because the envelope is larger than the text it protects.
     */
    @Column(name = "description", length = 255)
    private String description;

    /**
     * BR-08: the date the money moved. Never in the future - except for a {@code RECURRING} row,
     * which the scheduler creates ahead of time; {@code sp_validate_transaction} is what decides
     * that, so the rule is not restated here.
     */
    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    /**
     * How the row came to exist. Fixed at creation, so there is no setter.
     *
     * <p>Never taken from a request: a client able to claim {@code RECURRING} would be able to
     * record a future-dated transaction, because that is the one source {@code
     * sp_validate_transaction} exempts from the BR-08 date check.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false,
            columnDefinition = "enum('MANUAL','CSV','RECURRING')")
    private TransactionSource source;

    /**
     * BR-09: soft delete. {@code updatable = false} because {@code sp_soft_delete_transaction} and
     * {@code sp_restore_transaction} own this column - see the note on the class.
     */
    @Column(name = "is_deleted", nullable = false, columnDefinition = "tinyint(1)",
            updatable = false)
    private Boolean isDeleted;

    /** When the row was soft-deleted. Written only by the soft-delete procedure (BR-09). */
    @Column(name = "deleted_at", updatable = false)
    private LocalDateTime deletedAt;

    protected Transaction() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Category getCategory() {
        return category;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getTxnDate() {
        return txnDate;
    }

    public TransactionSource getSource() {
        return source;
    }

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    // --- Writable by UC-07 and UC-10 ------------------------------------------------------------
    // Setters exist only for the fields a student may change on their own record. There is no
    // setter for user_id (a record cannot be handed to another student), for source (provenance is
    // fixed at creation) or for the soft-delete pair (the procedures own them).

    /** UC-10: move the record to another category, which also decides whether it is income or
     * expense (BR-05). The service checks the new category is one the student may use. */
    public void setCategory(Category category) {
        this.category = category;
    }

    /** UC-07, UC-10. */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /** UC-07, UC-10. */
    public void setDescription(String description) {
        this.description = description;
    }

    /** UC-07, UC-10. */
    public void setTxnDate(LocalDate txnDate) {
        this.txnDate = txnDate;
    }

    /**
     * Builds a manually recorded transaction owned by one student - the only kind UC-07 creates.
     *
     * <p>{@code user_id} and {@code source} are parameters of the factory rather than fields a
     * caller sets, so a row cannot be built without an owner and every row this module creates is
     * {@code MANUAL}. {@code is_deleted} is written explicitly as false because Hibernate names
     * every mapped column in the INSERT it builds and the column is NOT NULL; {@code deleted_at}
     * is left to the schema's NULL default, since it is only ever set by the delete procedure.
     *
     * <p>Nothing here decides whether the record is acceptable. The date rule (BR-08), the
     * category's ownership and its active state are {@code sp_validate_transaction}'s
     * ({@code trg_transactions_before_insert}); the service asks the same questions first only so
     * the caller receives an error naming the field.
     *
     * @param userId      the owning student, never null
     * @param category    a category the student may use, validated by the service
     * @param amount      strictly positive
     * @param txnDate     the date the money moved
     * @param description trimmed by the service, or null
     */
    public static Transaction newManual(Long userId,
                                        Category category,
                                        BigDecimal amount,
                                        LocalDate txnDate,
                                        String description) {
        Transaction transaction = new Transaction();
        transaction.userId = userId;
        transaction.category = category;
        transaction.amount = amount;
        transaction.txnDate = txnDate;
        transaction.description = description;
        transaction.source = TransactionSource.MANUAL;
        transaction.isDeleted = Boolean.FALSE;
        return transaction;
    }
}
