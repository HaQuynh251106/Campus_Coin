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

@Service
public class AdminAnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AdminAnnouncementService.class);

    private static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> list() {
        return announcementMapper.toResponses(announcementViewDao.findAll());
    }

    @Transactional
    public AnnouncementResponse create(CreateAnnouncementRequest request, Long actorId, String ipAddress) {

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

    @Transactional
    public AnnouncementResponse setActive(Long announcementId, UpdateAnnouncementRequest request,
                                          Long actorId, String ipAddress) {

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

    private AdminAnnouncementRow requireAnnouncement(Long announcementId) {
        return announcementViewDao.findOne(announcementId)
                .orElseThrow(() -> new NotFoundException("Announcement not found."));
    }

    private static LocalDateTime truncateToSecond(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.SECONDS);
    }

    private void requireValidWindow(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new RequestValidationException(
                    "Request validation failed.",
                    List.of(new ApiError.FieldError("endsAt",
                            "The end time must be after the start time.")));
        }
    }

    private RuntimeException translateWriteFailure(RuntimeException ex, String reference) {
        if (AdminWriteFailure.isSignalledRefusal(ex) || AdminWriteFailure.isConstraintViolation(ex)) {
            log.info("Announcement write refused reference={}", reference);
            return new DataConflictException(RELOAD_AND_RETRY);
        }

        log.error("Announcement write failed unexpectedly reference={}", reference, ex);
        return ex;
    }
}
