package com.fitzza.review.dto;

import com.fitzza.review.domain.Review;
import java.time.Instant;

public record ReviewUpdatedResponse(Long reviewId, int rating, String content, String imageUrl, Instant updatedAt) {

    public static ReviewUpdatedResponse from(Review review) {
        return new ReviewUpdatedResponse(
                review.getId(), review.getRating(), review.getContent(), review.getImageUrl(), review.getUpdatedAt());
    }
}
