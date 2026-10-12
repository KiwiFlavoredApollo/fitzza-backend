package com.fitzza.community.notification;

// notification-service의 POST /internal/notifications/events 본문과 같은 모양이다.
public record NotificationEvent(String eventId, String type, Long userId, String targetId, String content) {
}
