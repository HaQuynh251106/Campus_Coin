package com.campuscoin.admin.repository;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.StoredProcedureQuery;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.entity.AnnouncementAudience;
import com.campuscoin.dashboard.entity.AnnouncementSeverity;

/**
 * Calls the two procedures that write an announcement (UC-21 B1, B2).
 *
 * <p><b>Creating and switching off are separate procedures, and there is no third one.</b>
 * {@code announcements} has no content-update procedure while {@code tip_templates} does, and the
 * asymmetry is deliberate: an announcement is a notice that was published, so correcting one means
 * posting the corrected text and deactivating the old notice rather than rewriting what students have
 * already read. Keeping it that way also means every write an administrator can make to this table
 * goes through {@code sp_require_admin} and leaves an audit row - there is no unaudited path, which is
 * what {@code docs/OVERNIGHT_BLOCKERS.md} OB-005 asserts.
 *
 * <p><b>No entity, and no {@code save}.</b> Nothing in this module updates an announcement through
 * Hibernate: {@code is_active} is written by the procedure, and content is written once at creation.
 * A mapped entity would be a second writer able to bypass both the authorisation gate and the audit
 * trail.
 */
@Repository
public class AdminAnnouncementProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Publishes a system-wide announcement (UC-21 B1).
     *
     * <p><b>Returns nothing, because the procedure has no OUT parameter and the new id cannot be
     * recovered from the session.</b> {@code LAST_INSERT_ID()} after this {@code CALL} reports the
     * id of the {@code admin_audit_log} row the procedure writes <em>after</em> the announcement, not
     * the announcement's own. The caller therefore locates the row it created through
     * {@code AdminAnnouncementViewDao.findCreatedBy}, which is why that read is written to key on
     * author, title and start time rather than on an id.
     *
     * <p><b>Every optional parameter is nullable and means "let the procedure decide".</b> The
     * procedure substitutes {@code INFO} for a null severity, {@code STUDENTS} for a null audience and
     * {@code NOW()} for a null start time, through its own {@code IFNULL} expressions. Passing null
     * through rather than choosing a default here keeps the documented default and the stored value
     * the same value, and keeps the fallback in the one place that owns it.
     *
     * <p>Declared parameter types rather than the named shorthand, for the reason
     * {@code AdminCategoryProcedureDao} records: a null cannot be bound without a declared type, and
     * four of this call's parameters are intentionally null on a typical create.
     *
     * <p>Audit row: {@code ANNOUNCEMENT_CREATED}, with the title, audience and severity in
     * {@code detail}.
     *
     * @param actorId    the administrator publishing it, re-checked by {@code sp_require_admin}
     * @param title      required - the procedure refuses a null title
     * @param body       required - the procedure refuses a null body
     * @param severity   null lets the procedure store its own default, {@code INFO}
     * @param audience   null lets the procedure store its own default, {@code STUDENTS}
     * @param startsAt   null lets the procedure use {@code NOW()}
     * @param endsAt     null means the announcement never expires; an end before the start is refused
     *                   by {@code ck_ann_window}, which the service checks first so the caller gets a
     *                   field error rather than a constraint violation
     * @param ipAddress  client address, for the audit row
     */
    @Transactional
    public void createAnnouncement(Long actorId,
                                   String title,
                                   String body,
                                   AnnouncementSeverity severity,
                                   AnnouncementAudience audience,
                                   LocalDateTime startsAt,
                                   LocalDateTime endsAt,
                                   String ipAddress) {
        StoredProcedureQuery query = entityManager.createStoredProcedureQuery(
                "sp_admin_create_announcement");

        query.registerStoredProcedureParameter(1, Long.class, ParameterMode.IN);           // p_actor_id
        query.registerStoredProcedureParameter(2, String.class, ParameterMode.IN);         // p_title
        query.registerStoredProcedureParameter(3, String.class, ParameterMode.IN);         // p_body
        query.registerStoredProcedureParameter(4, String.class, ParameterMode.IN);         // p_severity
        query.registerStoredProcedureParameter(5, String.class, ParameterMode.IN);         // p_audience
        query.registerStoredProcedureParameter(6, LocalDateTime.class, ParameterMode.IN);  // p_starts_at
        query.registerStoredProcedureParameter(7, LocalDateTime.class, ParameterMode.IN);  // p_ends_at
        query.registerStoredProcedureParameter(8, String.class, ParameterMode.IN);         // p_ip

        query.setParameter(1, actorId);
        query.setParameter(2, title);
        query.setParameter(3, body);
        query.setParameter(4, severity == null ? null : severity.name());
        query.setParameter(5, audience == null ? null : audience.name());
        query.setParameter(6, startsAt);
        query.setParameter(7, endsAt);
        query.setParameter(8, ipAddress);

        query.executeUpdate();
    }

    /**
     * Activates or deactivates an announcement (UC-21 B2).
     *
     * <p>This is the only way an announcement's content-adjacent state changes, and it is the reason
     * a published notice can be withdrawn without deleting it - deletion is not offered, because
     * {@code announcements} has no delete procedure and a withdrawn notice that still exists is
     * exactly what an audit trail is for.
     *
     * <p>Both parameters are always present, so the named shorthand can bind them; the boolean goes to
     * the procedure's {@code TINYINT} as its 0/1 value, which is what the procedure's
     * {@code IF p_is_active = 1} branch tests.
     *
     * <p>The service reads the row first, so a refusal here means the announcement was removed between
     * that read and this call - a window no request can widen. The procedure's own
     * {@code 'Announcement does not exist'} check stays as the guarantee for a hand-run {@code CALL}.
     *
     * <p>Audit row: {@code ANNOUNCEMENT_ACTIVATED} or {@code ANNOUNCEMENT_DEACTIVATED}.
     *
     * @param actorId      the administrator making the change, re-checked by {@code sp_require_admin}
     * @param announcementId the row to change, already confirmed to exist
     * @param isActive     true to publish the notice to its audience, false to withdraw it
     * @param ipAddress    client address, for the audit row
     */
    @Transactional
    public void setAnnouncementActive(Long actorId, Long announcementId, boolean isActive, String ipAddress) {
        entityManager.createNativeQuery(
                        "CALL sp_admin_set_announcement_active(:actorId, :announcementId, :isActive, :ipAddress)")
                .setParameter("actorId", actorId)
                .setParameter("announcementId", announcementId)
                .setParameter("isActive", isActive)
                .setParameter("ipAddress", ipAddress)
                .executeUpdate();
    }
}
