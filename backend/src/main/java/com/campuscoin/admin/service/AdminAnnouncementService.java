package com.campuscoin.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campuscoin.admin.dto.AnnouncementResponse;
import com.campuscoin.admin.dto.CreateAnnouncementRequest;
import com.campuscoin.admin.dto.UpdateAnnouncementRequest;
import com.campuscoin.admin.entity.AdminAnnouncementRow;
import com.campuscoin.admin.mapper.AdminAnnouncementMapper;
import com.campuscoin.admin.repository.AdminAnnouncementProcedureDao;
import com.campuscoin.admin.repository.AdminAnnouncementViewDao;
import com.campuscoin.common.exception.ApiError;
import com.campuscoin.common.exception.DataConflictException;
import com.campuscoin.common.exception.NotFoundException;
import com.campuscoin.common.exception.RequestValidationException;

/**
 * The system announcements students and administrators see (UC-21 B1, B2).
 *
 * <p><b>Content is create-once.</b> There is no update path for an announcement's text, because
 * {@code announcements} has no content-update procedure while {@code tip_templates} does - the
 * asymmetry is deliberate. A notice is a thing that was published; correcting it means publishing the
 * corrected text and deactivating the old one, which keeps the record of what students actually read.
 * The only field this service moves is {@code is_active}, and it moves it through
 * {@code sp_admin_set_announcement_active}. The practical consequence is that every write an
 * administrator can make to this table goes through {@code sp_require_admin} and leaves an audit row -
 * there is no unaudited path, which is the invariant {@code docs/OVERNIGHT_BLOCKERS.md} OB-005 states.
 *
 * <p><b>The list is the table, not {@code v_active_announcements}.</b> The view applies the time
 * window and the active flag, which is what a student's dashboard wants and what an administration
 * screen must not have: the notice an administrator needs to act on is the expired, the not-yet-started
 * or the switched-off one, and the toggle exists to switch it back on. See
 * {@code AdminAnnouncementViewDao}.
 *
 * <p><b>The response publishes no author.</b> Who wrote a notice is recorded in
 * {@code admin_audit_log}, which is the record of who did what; a per-row author field would be a
 * second, weaker answer to the same question. The student-facing dashboard takes the same position.
 */
@Service
public class AdminAnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AdminAnnouncementService.class);

    /**
     * The zone the API resolves "now" in.
     *
     * <p>The same {@code Asia/Ho_Chi_Minh} the JDBC connection is pinned to
     * ({@code serverTimezone} in the datasource URL), so a time this service computes and a time
     * {@code NOW()} would have produced inside MySQL are the same instant and comparable as
     * {@code DATETIME} literals. That is not cosmetic: the create path locates the row it just wrote by
     * its {@code starts_at}, and a start time resolved in a different zone from the one the database
     * stored would make that lookup miss.
     */
    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** What the toggle's refusal says when the announcement stopped existing mid-request. */
    private static final String RELOAD_AND_RETRY =
            "The announcement could not be changed. Reload it and try again.";

    private final AdminAnnouncementViewDao announcementViewDao;
    private final AdminAnnouncementProcedureDao announcementProcedureDao;
    private final AdminAnnouncementMapper announcementMapper;

    public AdminAnnouncementService(AdminAnnouncementViewDao announcementViewDao,
                                    AdminAnnouncementProcedureDao announcementProcedureDao,
                                    AdminAnnouncementMapper announcementMapper) {
        this.announcementViewDao = announcementViewDao;
        this.announcementProcedureDao = announcementProcedureDao;
        this.announcementMapper = announcementMapper;
    }

    /** UC-21: every announcement, live, expired, not yet started and withdrawn alike. */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> list() {
        return announcementMapper.toResponses(announcementViewDao.findAll());
    }

    /**
     * UC-21 B1: publish an announcement.
     *
     * <p><b>The window is checked here so the caller gets a field error rather than a constraint
     * violation.</b> {@code ck_ann_window} refuses an end at or before the start with SQLSTATE 23000
     * and a message naming the constraint - an identifier that is not part of the API contract. The
     * check below answers {@code VALIDATION_ERROR} on the {@code endsAt} field instead, which is the
     * answer Angular can attach to the input the user typed. The CHECK constraint remains the
     * authority; this is a better-worded report of the same rule, not a replacement for it.
     *
     * <p><b>A missing start time is resolved here rather than left to the procedure's
     * {@code NOW()} fallback.</b> Both would produce the same instant - see {@link #APPLICATION_ZONE} -
     * but the row that was created has to be located afterwards, and the only identity
     * {@code announcements} offers is the triple {@code (created_by, title, starts_at)}: the table has
     * no unique key. Passing the value explicitly means the lookup uses exactly what was written,
     * rather than a timestamp the service would have to guess at to the second.
     *
     * <p>The same reasoning covers the read-back itself: {@code sp_admin_create_announcement} declares
     * no OUT parameter, and because it inserts its {@code admin_audit_log} row after the announcement,
     * {@code LAST_INSERT_ID()} at the moment the call returns reports the audit row's id.
     */
    @Transactional
    public AnnouncementResponse create(CreateAnnouncementRequest request, Long actorId, String ipAddress) {
        // Both times are truncated to the second before anything else happens, and this is
        // load-bearing rather than tidying. starts_at and ends_at are DATETIME, which MySQL stores
        // with no fractional part: a value carrying nanoseconds is rounded on the way in, so the
        // timestamp held here would not be the timestamp the row holds. The read-back below matches
        // on starts_at, so without this it matches nothing and every create answers 500. Truncating
        // before the window check also keeps that check and ck_ann_window agreeing - a start of
        // 10:00:00.4 and an end of 10:00:00.9 are ordered in Java but are one instant once stored,
        // and the constraint would refuse a pair this method had just approved.
        LocalDateTime startsAt = truncateToSecond(request.startsAt() != null
                ? request.startsAt()
                : LocalDateTime.now(APPLICATION_ZONE));
        LocalDateTime endsAt = truncateToSecond(request.endsAt());

        requireValidWindow(startsAt, endsAt);

        try {
            announcementProcedureDao.createAnnouncement(
                    actorId,
                    request.title(),
                    request.body(),
                    request.severity(),
                    request.audience(),
                    startsAt,
                    endsAt,
                    ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, request.title());
        }

        log.info("Announcement created actorId={} audience={} startsAt={}",
                actorId, request.audience(), startsAt);

        return announcementViewDao.findCreatedBy(actorId, request.title(), startsAt)
                .map(announcementMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "The announcement just published was not readable back."));
    }

    /**
     * UC-21 B2: show or withdraw an announcement.
     *
     * <p><b>This writes one column.</b> It is deliberately the whole of the update surface: content is
     * create-once, so "change this notice" is expressed as withdraw and publish a corrected one. The
     * procedure's {@code 'Announcement does not exist'} check is preceded by a read here, which is what
     * makes the refusal classifiable - both {@code sp_admin_set_announcement_active} and
     * {@code sp_require_admin} signal SQLSTATE 45000, so a signal reaching this method could otherwise
     * not be told apart from an authorisation failure without matching the procedure's prose.
     *
     * <p>Re-sending the state an announcement is already in is accepted and leaves an audit row saying
     * so. It is not refused: the caller asked for the row to be in that state, and it is - a truthful
     * record of a request that needed no change is better than an error the client has to special-case.
     *
     * @throws NotFoundException no announcement has this id
     */
    @Transactional
    public AnnouncementResponse setActive(Long announcementId, UpdateAnnouncementRequest request,
                                          Long actorId, String ipAddress) {
        // Pre-read, so the surviving 45000 means the row disappeared between here and the write.
        requireAnnouncement(announcementId);

        try {
            announcementProcedureDao.setAnnouncementActive(
                    actorId, announcementId, request.isActive(), ipAddress);
        } catch (RuntimeException ex) {
            throw translateWriteFailure(ex, announcementId.toString());
        }

        log.info("Announcement active flag changed actorId={} announcementId={} isActive={}",
                actorId, announcementId, request.isActive());

        return announcementViewDao.findOne(announcementId)
                .map(announcementMapper::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Announcement " + announcementId + " was not readable back after update."));
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------

    /** One announcement, or the {@code 404} that says there is none. */
    private AdminAnnouncementRow requireAnnouncement(Long announcementId) {
        return announcementViewDao.findOne(announcementId)
                .orElseThrow(() -> new NotFoundException("Announcement not found."));
    }

    /**
     * A time as {@code DATETIME} will store it.
     *
     * <p>The column has no fractional part, so a value with one is truncated here rather than left
     * to MySQL's rounding. Doing it in Java means the value the caller is answered with, the value
     * the window is checked against and the value the read-back matches on are the same one.
     * Truncation rather than rounding, because {@code DATETIME} does not carry the information
     * either way and truncation is what the comparison in {@link #requireValidWindow} then agrees
     * with.
     */
    private static LocalDateTime truncateToSecond(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * The announcement window, as {@code ck_ann_window} defines it.
     *
     * <p>{@code ends_at} null means the notice is open-ended - it stays up until it is deactivated -
     * so only a start and an end that are both present are compared. The comparison is strict, matching
     * the constraint: an end equal to the start is refused, because a notice whose window is one
     * instant long would never be seen.
     *
     * <p>Both arguments have already been truncated to what {@code DATETIME} stores, so this check
     * and the constraint see the same pair and cannot disagree.
     */
    private void requireValidWindow(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new RequestValidationException(
                    "Request validation failed.",
                    List.of(new ApiError.FieldError("endsAt",
                            "The end time must be after the start time.")));
        }
    }

    /**
     * Turns a database refusal into the API error that explains it.
     *
     * <p>On both paths the pre-read has already answered "no such announcement", so a surviving
     * {@code 45000} can only be {@code sp_require_admin} refusing the actor or the row disappearing;
     * the window was checked above, so {@code ck_ann_window} cannot be the 23000 here. Both are
     * answered as a conflict rather than a {@code 404}, because the caller's id was correct a moment
     * ago and telling them otherwise would send them to change it.
     */
    private RuntimeException translateWriteFailure(RuntimeException ex, String reference) {
        if (AdminWriteFailure.isSignalledRefusal(ex) || AdminWriteFailure.isConstraintViolation(ex)) {
            log.info("Announcement write refused reference={}", reference);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Announcement write failed unexpectedly reference={}", reference, ex);
        return ex;
    }
}
