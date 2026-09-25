package com.campuscoin.budget.mapper;

import org.springframework.stereotype.Component;

import com.campuscoin.budget.dto.NotificationResponse;
import com.campuscoin.budget.entity.Notification;

/**
 * Maps a {@code notifications} row to the API model (UC-14).
 *
 * <p>Its own class for the reason the other mappers give: the single place that decides which
 * columns may leave the server. The entity maps {@code user_id} because the ownership queries need
 * it, and centralising the projection here means that column cannot appear in a response by
 * accident - which matters here because a notification's body is readable prose about one student's
 * spending.
 *
 * <p>Deliberately omitted: the owner, the row's own identifier is kept, and nothing else is carried
 * over. Every field the entity maps is published except {@code userId}.
 */
@Component
public class NotificationMapper {

    /** UC-14: one notification as the client sees it. */
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
