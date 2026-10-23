package com.fitzza.review.dto;

import com.fitzza.review.domain.Review;
import java.time.Instant;

public record ReviewResponse(
        Long reviewId,
        Long productId,
        Long userId,
        String source,
        int rating,
        String content,
        String imageUrl,
        Instant createdAt) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getProductId(),
                review.getUserId(),
                review.getSource().name(),
                review.getRating(),
                review.getContent(),
                review.getImageUrl(),
                review.getCreatedAt());
    }
}
