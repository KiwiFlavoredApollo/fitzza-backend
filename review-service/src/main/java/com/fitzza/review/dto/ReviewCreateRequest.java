package com.fitzza.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReviewCreateRequest(
        @NotNull(message = "상품을 확인해주세요.") @Positive(message = "상품을 확인해주세요.") Long productId,
        @NotNull(message = "주문 상품을 선택해주세요.") @Positive(message = "주문 상품을 확인해주세요.") Long orderItemId,
        @NotNull(message = "별점을 골라주세요.")
                @Min(value = 1, message = "별점은 1~5점입니다.")
                @Max(value = 5, message = "별점은 1~5점입니다.")
                Integer rating,
        @NotBlank(message = "리뷰 내용을 입력해주세요.")
                @Size(max = ReviewCreateRequest.MAX_CONTENT_LENGTH, message = "리뷰는 1000자까지 쓸 수 있습니다.")
                String content,
        @Size(max = 500, message = "이미지 주소가 너무 깁니다.") String imageUrl) {

    public static final int MAX_CONTENT_LENGTH = 1000;
}
