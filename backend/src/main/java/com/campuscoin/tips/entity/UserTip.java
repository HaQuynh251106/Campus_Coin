package com.campuscoin.tips.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "user_tips")
@DynamicUpdate
public class UserTip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "potential_saving", nullable = false)
    private BigDecimal potentialSaving;

    @Column(name = "rank_score", nullable = false)
    private BigDecimal rankScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false,
            columnDefinition = "enum('NEW','PINNED','DISMISSED')")
    private TipState state;

    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    protected UserTip() {

    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getPeriodMonth() {
        return periodMonth;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public BigDecimal getPotentialSaving() {
        return potentialSaving;
    }

    public BigDecimal getRankScore() {
        return rankScore;
    }

    public TipState getState() {
        return state;
    }

    public LocalDateTime getPinnedAt() {
        return pinnedAt;
    }

    public LocalDateTime getDismissedAt() {
        return dismissedAt;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setState(TipState newState, LocalDateTime now) {
        this.state = newState;
        switch (newState) {
            case PINNED -> {
                this.pinnedAt = now;
                this.dismissedAt = null;
            }
            case DISMISSED -> {
                this.dismissedAt = now;
                this.pinnedAt = null;
            }
            case NEW -> {
                this.pinnedAt = null;
                this.dismissedAt = null;
            }
        }
    }
}
