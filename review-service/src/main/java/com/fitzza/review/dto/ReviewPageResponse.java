package com.fitzza.review.dto;

import java.util.List;
import org.springframework.data.domain.Page;

// page는 0부터 시작한다. product-service·community-service 목록 응답과 같은 필드를 쓴다.
public record ReviewPageResponse(
        List<ReviewResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static ReviewPageResponse from(Page<ReviewResponse> page) {
        return new ReviewPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
