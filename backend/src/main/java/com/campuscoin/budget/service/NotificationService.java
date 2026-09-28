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

    @Transactional(readOnly = true)
    public List<NotificationResponse> listNotifications(AuthenticatedUser principal) {
        return notificationRepository.findForStudent(principal.userId()).stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public NotificationResponse getNotification(AuthenticatedUser principal, Long notificationId) {
        return notificationMapper.toResponse(requireOwn(principal.userId(), notificationId));
    }

    @Transactional
    public NotificationResponse markRead(AuthenticatedUser principal, Long notificationId) {
        Long userId = principal.userId();

        if (!notificationRepository.existsByIdAndUserId(notificationId, userId)) {
            throw new NotFoundException("Notification not found.");
        }

        int changed = notificationProcedureDao.markRead(notificationId, userId);

        log.info("Notification marked read userId={} notificationId={} changed={}",
                userId, notificationId, changed);

        return notificationMapper.toResponse(requireOwn(userId, notificationId));
    }

    private Notification requireOwn(Long userId, Long notificationId) {
        return notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NotFoundException("Notification not found."));
    }
}
