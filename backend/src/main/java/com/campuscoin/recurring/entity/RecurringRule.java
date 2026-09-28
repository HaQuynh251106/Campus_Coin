package com.campuscoin.recurring.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

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
import com.campuscoin.category.entity.CategoryType;

@Entity
@Table(name = "recurring_rules")
@DynamicUpdate
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "enum('INCOME','EXPENSE')")
    private CategoryType type;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false,
            columnDefinition = "enum('DAILY','WEEKLY','MONTHLY','QUARTERLY','YEARLY')")
    private RecurringFrequency frequency;

    @Column(name = "interval_count", nullable = false, columnDefinition = "smallint unsigned")
    private Integer intervalCount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;

    @Column(name = "last_run_date", updatable = false)
    private LocalDate lastRunDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "enum('ACTIVE','PAUSED','ENDED')")
    private RecurringStatus status;

    protected RecurringRule() {

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

    public CategoryType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public RecurringFrequency getFrequency() {
        return frequency;
    }

    public Integer getIntervalCount() {
        return intervalCount;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getNextRunDate() {
        return nextRunDate;
    }

    public LocalDate getLastRunDate() {
        return lastRunDate;
    }

    public RecurringStatus getStatus() {
        return status;
    }

    public void setCategory(Category category) {
        this.category = category;
        this.type = category.getType();
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setFrequency(RecurringFrequency frequency) {
        this.frequency = frequency;
    }

    public void setIntervalCount(Integer intervalCount) {
        this.intervalCount = intervalCount;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public void setNextRunDate(LocalDate nextRunDate) {
        this.nextRunDate = nextRunDate;
    }

    public void setStatus(RecurringStatus status) {
        this.status = status;
    }

    public static RecurringRule newRule(Long userId,
                                        Category category,
                                        BigDecimal amount,
                                        String description,
                                        RecurringFrequency frequency,
                                        Integer intervalCount,
                                        LocalDate startDate,
                                        LocalDate endDate,
                                        LocalDate nextRunDate) {
        RecurringRule rule = new RecurringRule();
        rule.userId = userId;
        rule.category = category;
        rule.type = category.getType();
        rule.amount = amount;
        rule.description = description;
        rule.frequency = frequency;
        rule.intervalCount = intervalCount;
        rule.startDate = startDate;
        rule.endDate = endDate;
        rule.nextRunDate = nextRunDate;
        rule.status = RecurringStatus.ACTIVE;
        return rule;
    }
}
