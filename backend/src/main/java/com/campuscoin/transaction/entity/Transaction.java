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

@Entity
@Table(name = "transactions")
@DynamicUpdate
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false,
            columnDefinition = "enum('MANUAL','CSV','RECURRING')")
    private TransactionSource source;

    @Column(name = "is_deleted", nullable = false, columnDefinition = "tinyint(1)",
            updatable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_at", updatable = false)
    private LocalDateTime deletedAt;

    protected Transaction() {

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

    public void setCategory(Category category) {
        this.category = category;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setTxnDate(LocalDate txnDate) {
        this.txnDate = txnDate;
    }

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
