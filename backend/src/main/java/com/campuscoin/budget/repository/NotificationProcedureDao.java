package com.campuscoin.budget.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calls the stored procedure that marks a notification read (UC-14 B4).
 *
 * <p><b>Why the database does this and not a Hibernate {@code UPDATE}.</b>
 * {@code sp_mark_notification_read} is a single statement -
 * {@code UPDATE notifications SET is_read = 1, read_at = NOW() WHERE id = ? AND user_id = ? AND
 * is_read = 0} - and every part of it is load-bearing:
 *
 * <ul>
 *   <li>The {@code user_id} predicate is the ownership check, in the same statement that writes.
 *       Setting the field through the entity would move the row without it, which is the one thing
 *       the design exists to prevent - the same reasoning the transaction module records for
 *       {@code isDeleted}.</li>
 *   <li>{@code is_read = 0} makes the transition one-way and idempotent-at-the-database. Marking an
 *       already-read notification changes nothing, so {@code read_at} keeps the time it was first
 *       read rather than being pushed forward by every later call.</li>
 *   <li>{@code is_read} and {@code read_at} are set together, which is what {@code ck_notif_read}
 *       requires: the pair is read with a timestamp or not at all.</li>
 * </ul>
 *
 * <p>Marking read is not a destructive action and needs no confirmation, so this DAO returns the
 * number of rows the statement changed rather than throwing on zero. The service uses that number to
 * tell "it was unread and is now read" from "it was already read": the first is the operation the
 * caller asked for, and the second is a request that was already satisfied. Both are answered with
 * the notification, because the end state the caller wants - this message reads as read - is true
 * either way.
 */
@Repository
public class NotificationProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Marks one of the caller's own notifications as read.
     *
     * <p>The {@code userId} is passed to the procedure rather than checked beforehand, so the
     * ownership decision and the write are one atomic statement. A notification belonging to another
     * student updates nothing, and the count returned is zero.
     *
     * @return the number of rows changed: {@code 1} if the notification was unread and is now read,
     *         {@code 0} if it was already read or is not the caller's
     */
    @Transactional
    public int markRead(Long notificationId, Long userId) {
        return entityManager.createNativeQuery("CALL sp_mark_notification_read(:id, :userId)")
                .setParameter("id", notificationId)
                .setParameter("userId", userId)
                .executeUpdate();
    }
}
