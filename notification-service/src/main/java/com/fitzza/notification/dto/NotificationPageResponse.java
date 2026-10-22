package com.fitzza.notification.dto;

import java.util.List;
import org.springframework.data.domain.Page;

// page는 0부터 시작한다. product-service의 목록 응답과 같은 필드를 쓴다.
public record NotificationPageResponse(
        List<NotificationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static NotificationPageResponse of(List<NotificationResponse> content, Page<?> page) {
        return new NotificationPageResponse(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
