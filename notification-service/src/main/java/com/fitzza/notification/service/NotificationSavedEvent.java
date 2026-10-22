package com.fitzza.notification.service;

import com.fitzza.notification.domain.NotificationType;

// 알림이 새로 저장됐을 때 발행한다. 저장이 확정된 뒤 푸시를 보내는 데 쓴다. targetId는 없으면 빈 문자열이다.
public record NotificationSavedEvent(
        String notificationId, Long userId, NotificationType type, String content, String targetId) {
}
