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

@Repository
public class AdminAnnouncementProcedureDao {

    @PersistenceContext
    private EntityManager entityManager;

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

        query.registerStoredProcedureParameter(1, Long.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(2, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(3, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(4, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(5, String.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(6, LocalDateTime.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(7, LocalDateTime.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(8, String.class, ParameterMode.IN);

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
