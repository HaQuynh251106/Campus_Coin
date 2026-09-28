package com.campuscoin.budget.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.budget.dto.NotificationResponse;
import com.campuscoin.budget.entity.Notification;

@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getLinkUrl(),
                notification.getRefEntityType(),
                notification.getRefEntityId(),
                notification.getIsRead(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
