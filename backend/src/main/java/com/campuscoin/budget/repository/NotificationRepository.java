package com.campuscoin.budget.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.budget.entity.Notification;

/**
 * MySQL access for {@code notifications} (UC-14).
 *
 * <p><b>Read-only.</b> Nothing here writes. The rows are created by the procedures that own them -
 * {@code sp_check_budget_alerts} for a budget alert, the announcement procedure for UC-21, the tip
 * and insight generators for later modules - and the one field this application changes,
 * {@code is_read}, is changed by {@code sp_mark_notification_read} through
 * {@code NotificationProcedureDao}. {@code Notification} is {@code @Immutable} to match, so an
 * accidental {@code save} cannot interleave with the procedure.
 *
 * <p>Every query takes the owning student's id. There is no method that can fetch a notification by
 * id alone, so no service method can read or mark another student's message by mistake. That matters
 * more here than on most tables: a notification's title and body are readable prose about the
 * student's own spending, so a missing filter leaks a sentence rather than a number. BR-02 requires
 * ownership server-side, and making it a property of the queries is the version that cannot be
 * forgotten.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * UC-14: the caller's notifications, newest first.
     *
     * <p>The list is deliberately not filtered by read state and not paginated. A student has a
     * bounded number of messages - one per alert per category per month (BR-12), plus announcements -
     * and the screen that shows them is a "what have I missed" list, where hiding the read ones would
     * remove the context for the unread ones. {@code isRead} is in the response so the client
     * decides how to present each row, exactly as {@code status} is on a recurring rule and
     * {@code isActive} is on a category. There is no {@code ?unread=true} parameter: it would be a
     * second way to ask the same question, and the client already has the flag.
     *
     * <p>Ordered by {@code created_at} descending, then id descending. Newest first is the order a
     * notification list is read in, and the id makes it total: several notifications can share a
     * timestamp - {@code sp_check_budget_alerts} uses {@code NOW()}, and two alerts in one database
     * transaction genuinely can - and without the tie-break MySQL could return equal rows in either
     * order, so the list would appear to shuffle between two identical calls.
     */
    @Query("""
            SELECT n FROM Notification n
             WHERE n.userId = :userId
             ORDER BY n.createdAt DESC, n.id DESC
            """)
    List<Notification> findForStudent(@Param("userId") Long userId);

    /**
     * One of the caller's own notifications.
     *
     * <p>Returns empty both when the notification does not exist and when it belongs to someone
     * else. The two are indistinguishable from the outside, which is the point (section 7.5). Used
     * by the mark-read path to answer with the updated row, and to give a caller that names a
     * notification they do not own the same {@code 404} as one that names nothing.
     */
    @Query("""
            SELECT n FROM Notification n
             WHERE n.id = :id AND n.userId = :userId
            """)
    Optional<Notification> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Whether this notification exists and belongs to the caller - answered without loading it.
     *
     * <p>Exists for the mark-read path, and the distinction from {@link #findByIdAndUserId} matters
     * there. Marking read runs {@code sp_mark_notification_read}, which changes the row with SQL
     * Hibernate never sees; if the ownership check had loaded the entity first, a later read of the
     * same id would be served from the persistence context's copy and would report the row as still
     * unread. A {@code COUNT} populates no entity, so the notification can be loaded once, after the
     * procedure has run, and show its new {@code read_at}.
     *
     * <p>It answers the ownership question too - the id is checked together with the caller's - so a
     * notification belonging to another student is refused before the procedure is ever called.
     */
    boolean existsByIdAndUserId(Long id, Long userId);
}
