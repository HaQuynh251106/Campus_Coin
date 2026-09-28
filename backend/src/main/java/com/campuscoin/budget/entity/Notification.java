package com.campuscoin.budget.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "notifications")
@Immutable
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false,
            columnDefinition = "enum('BUDGET_NEAR','BUDGET_EXCEEDED','ANNOUNCEMENT',"
                    + "'SYSTEM','INSIGHT_READY','TIP','RECURRING_POSTED')")
    private NotificationType type;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "link_url", length = 255)
    private String linkUrl;

    @Column(name = "ref_entity_type", length = 40)
    private String refEntityType;

    @Column(name = "ref_entity_id")
    private Long refEntityId;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Notification() {

    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    public String getRefEntityType() {
        return refEntityType;
    }

    public Long getRefEntityId() {
        return refEntityId;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

}
