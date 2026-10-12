package com.fitzza.community.dto;

import java.util.List;
import org.springframework.data.domain.Page;

// page는 0부터 시작한다. product-service의 목록 응답과 같은 필드를 쓴다.
public record PostPageResponse(
        List<PostSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static PostPageResponse of(List<PostSummaryResponse> content, Page<?> page) {
        return new PostPageResponse(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
