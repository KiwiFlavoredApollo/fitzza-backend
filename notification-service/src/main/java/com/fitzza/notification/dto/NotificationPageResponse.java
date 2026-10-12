package com.fitzza.notification.dto;

import java.util.List;

// page는 0부터 시작한다.
public record NotificationPageResponse(List<NotificationResponse> content, int page, int totalPages) {
}
