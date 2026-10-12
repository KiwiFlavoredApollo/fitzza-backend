package com.fitzza.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fitzza.notification.domain.Notification;
import com.fitzza.notification.domain.NotificationType;
import java.time.Instant;

public record NotificationResponse(
        String notificationId,
        NotificationType type,
        String content,
        String targetId,
        @JsonProperty("isRead") boolean read,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getContent(),
                notification.getTargetId(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
