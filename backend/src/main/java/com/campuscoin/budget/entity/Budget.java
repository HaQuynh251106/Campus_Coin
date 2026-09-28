package com.campuscoin.budget.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "budgets")
@DynamicUpdate
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "limit_amount", nullable = false)
    private BigDecimal limitAmount;

    protected Budget() {

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

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public BigDecimal getLimitAmount() {
        return limitAmount;
    }

    public void setLimitAmount(BigDecimal limitAmount) {
        this.limitAmount = limitAmount;
    }

    public static Budget newBudget(Long userId,
                                   Category category,
                                   LocalDate periodMonth,
                                   BigDecimal limitAmount) {
        Budget budget = new Budget();
        budget.userId = userId;
        budget.category = category;
        budget.periodMonth = periodMonth;
        budget.limitAmount = limitAmount;
        return budget;
    }
}
