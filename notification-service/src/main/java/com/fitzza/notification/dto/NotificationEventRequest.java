package com.fitzza.notification.dto;

import com.fitzza.notification.domain.Notification;
import com.fitzza.notification.domain.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// eventId는 보내는 서비스가 이벤트마다 정하는 고유값이다. 같은 값으로 다시 보내면 알림이 새로 생기지 않는다.
public record NotificationEventRequest(
        @NotBlank(message = "eventId가 필요합니다.")
                @Size(max = Notification.EVENT_ID_MAX_LENGTH, message = "eventId는 100자 이하여야 합니다.")
                String eventId,
        @NotNull(message = "알림 유형이 필요합니다.") NotificationType type,
        @NotNull(message = "받는 사용자 ID가 필요합니다.")
                @Positive(message = "받는 사용자 ID를 확인해주세요.")
                Long userId,
        @Size(max = Notification.TARGET_ID_MAX_LENGTH, message = "targetId는 100자 이하여야 합니다.") String targetId,
        @NotBlank(message = "알림 내용이 필요합니다.")
                @Size(max = Notification.CONTENT_MAX_LENGTH, message = "알림 내용은 500자 이하여야 합니다.")
                String content) {
}
