package com.campuscoin.budget.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.auth.security.AuthenticatedUser;
import com.campuscoin.budget.dto.NotificationResponse;
import com.campuscoin.budget.entity.Notification;
import com.campuscoin.budget.mapper.NotificationMapper;
import com.campuscoin.budget.repository.NotificationProcedureDao;
import com.campuscoin.budget.repository.NotificationRepository;
import com.campuscoin.common.exception.NotFoundException;

/**
 * Reading budget alerts and announcements, and marking them read (UC-14).
 *
 * <p><b>What the database owns.</b> Every notification row is written elsewhere, by a procedure that
 * owns it: {@code sp_check_budget_alerts} raises the two budget alerts when a transaction crosses a
 * threshold, and the announcement, tip and insight procedures of later modules write their own. This
 * class inserts nothing. The one field it changes - {@code is_read}, with {@code read_at} - is
 * changed by {@code sp_mark_notification_read}, never by a Hibernate {@code UPDATE}, because that
 * procedure's {@code WHERE id = ? AND user_id = ?} is the ownership check and setting the field here
 * would move the row without it.
 *
 * <p><b>What is genuinely this class's.</b> Deciding who may read and mark what, and giving a caller
 * that names a notification they do not own the same answer as one that names nothing.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationProcedureDao notificationProcedureDao;
    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationProcedureDao notificationProcedureDao,
                               NotificationMapper notificationMapper) {
        this.notificationRepository = notificationRepository;
        this.notificationProcedureDao = notificationProcedureDao;
        this.notificationMapper = notificationMapper;
    }

    /**
     * UC-14: the caller's notifications, newest first.
     *
     * <p>{@code readOnly = true} documents that nothing is written - reading a list never marks
     * anything read. The account comes from the verified token and the method takes no user id, so
     * there is no way to ask for somebody else's messages.
     *
     * <p>Read and unread are returned together. A "what have I missed" list needs the read ones for
     * context, and {@code isRead} is in the response so the client decides how to style each row -
     * the same arrangement {@code status} has on a recurring rule. There is deliberately no filter
     * parameter: it would be a second way to ask the same question.
     */
    @Transactional(readOnly = true)
    public List<NotificationResponse> listNotifications(AuthenticatedUser principal) {
        return notificationRepository.findForStudent(principal.userId()).stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    /**
     * UC-14 B4: read one of the caller's own notifications.
     *
     * <p>Exists so a client can open a single message, and so the mark-read response has the same
     * shape as the one a list row already has. A notification belonging to another student is not
     * found, and neither is one that does not exist - the two are indistinguishable from the
     * outside, on purpose.
     *
     * @throws NotFoundException if the notification does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public NotificationResponse getNotification(AuthenticatedUser principal, Long notificationId) {
        return notificationMapper.toResponse(requireOwn(principal.userId(), notificationId));
    }

    /**
     * UC-14 B4 and B5: mark one of the caller's notifications as read.
     *
     * <p>The transition belongs to the student, so there is no administrator route to it and no way to
     * mark a notification for somebody else - the procedure takes the caller's id and only the
     * caller's own row can change.
     *
     * <p><b>Marking an already-read notification is answered with the notification, not refused.</b>
     * The end state the caller wants - this message reads as read - is true, and the timestamp is the
     * one from when it was actually read, because {@code sp_mark_notification_read}'s
     * {@code AND is_read = 0} leaves an already-read row untouched. So a retry, or two devices
     * marking the same message, settle on the first time it was read rather than the most recent
     * attempt. This mirrors the recurring-rule module's handling of a status already set: a request
     * that asks for the state the row is already in changes nothing and is not an error.
     *
     * <p>Ownership is decided before the procedure runs, with a count rather than a loaded entity.
     * Loading the entity first would leave a copy in the persistence context and the later read would
     * be served from it, still showing the notification as unread - the procedure changes the row
     * with SQL Hibernate never sees. A count populates nothing, so the row is loaded once, after the
     * write, and shows its new state.
     *
     * @throws NotFoundException if the notification does not exist or is not the caller's
     */
    @Transactional
    public NotificationResponse markRead(AuthenticatedUser principal, Long notificationId) {
        Long userId = principal.userId();

        if (!notificationRepository.existsByIdAndUserId(notificationId, userId)) {
            throw new NotFoundException("Notification not found.");
        }

        int changed = notificationProcedureDao.markRead(notificationId, userId);

        log.info("Notification marked read userId={} notificationId={} changed={}",
                userId, notificationId, changed);

        // Read after the procedure so the response carries the read_at the database just wrote.
        return notificationMapper.toResponse(requireOwn(userId, notificationId));
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /**
     * Loads one of the caller's own notifications.
     *
     * <p>The lookup every read path uses. It takes the caller's id alongside the notification's, so a
     * row belonging to another student is not found rather than found-and-refused (section 7.5).
     *
     * @throws NotFoundException if the row does not exist or belongs to another student
     */
    private Notification requireOwn(Long userId, Long notificationId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NotFoundException("Notification not found."));
    }
}
