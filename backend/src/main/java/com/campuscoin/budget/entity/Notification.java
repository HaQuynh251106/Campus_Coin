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

/**
 * A row of {@code notifications}: one message addressed to one student (UC-14).
 *
 * <p><b>Read-only, and mapped that way on purpose.</b> Nothing in this module inserts a
 * notification - the budget alerts are written by {@code sp_check_budget_alerts}, which the
 * transaction triggers call; the announcements, tips and insights belong to later modules, each
 * writing through its own procedure. The only column this application ever changes is the pair
 * {@code is_read}/{@code read_at}, and that is changed by {@code sp_mark_notification_read} rather
 * than by a Hibernate {@code UPDATE}. {@link Immutable} states that intent to Hibernate: the entity
 * is never dirty-checked, so an accidental {@code save} cannot compete with the procedure that
 * actually owns the transition.
 *
 * <p><b>Why read-state goes through the procedure and not through this entity.</b>
 * {@code ck_notif_read} requires {@code is_read} and {@code read_at} to agree, and the procedure
 * sets both in the one statement that also proves ownership
 * ({@code WHERE id = ? AND user_id = ? AND is_read = 0}). Setting {@code isRead} here would move the
 * row without that ownership predicate, which is the one thing the check exists to prevent - the
 * same reasoning the transaction module records for {@code isDeleted}.
 *
 * <p><b>{@code user_id} is mapped but never published.</b> It is what the ownership queries filter
 * on; the response omits it, because a client has no use for it and must never be tempted to send
 * one back.
 *
 * <p>{@code ref_entity_type} and {@code ref_entity_id} are the pointer back to whatever raised the
 * notification - for a budget alert, {@code BUDGET} and the budget's id. They are mapped because a
 * client uses them to open the right screen, and they are safe to publish: they are identifiers of
 * rows the caller can already reach, and the row that produced them is only ever created for its
 * own owner.
 */
@Entity
@Table(name = "notifications")
@Immutable
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** The addressed student. Never published; used by every ownership query. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * What kind of message this is.
     *
     * <p>{@code columnDefinition} reproduces the schema's ENUM. Hibernate validates a column by the
     * type name MySQL reports, and a {@code @Enumerated(STRING)} field would otherwise be expected
     * to be a {@code varchar}, which {@code ddl-auto=validate} would refuse.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false,
            columnDefinition = "enum('BUDGET_NEAR','BUDGET_EXCEEDED','ANNOUNCEMENT',"
                    + "'SYSTEM','INSIGHT_READY','TIP','RECURRING_POSTED')")
    private NotificationType type;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    /** Where the message points, such as {@code /budgets}. Nullable. */
    @Column(name = "link_url", length = 255)
    private String linkUrl;

    /** The kind of row the message is about, such as {@code BUDGET}. Nullable. */
    @Column(name = "ref_entity_type", length = 40)
    private String refEntityType;

    /** The id of that row. Nullable. */
    @Column(name = "ref_entity_id")
    private Long refEntityId;

    /**
     * Whether the student has read it.
     *
     * <p>Named {@code isRead} with a {@code getIsRead()} accessor rather than {@code read}/
     * {@code isRead()}, so the property name is unambiguous to both Hibernate and Spring Data: a
     * field called {@code read} would be read as the {@code is-read} prefix convention and could be
     * mistaken for a keyword in a derived query.
     *
     * <p>{@code ck_notif_read} ties it to {@link #readAt}: both null, or both set.
     */
    @Column(name = "is_read", nullable = false)
    private Boolean isRead;

    /** When it was read, or null while unread. Written only by {@code sp_mark_notification_read}. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** When it was raised. The list is ordered by this. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Notification() {
        // Required by JPA.
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

    // No setters. Read-state is the procedure's to change, and nothing else here is writable.
}
