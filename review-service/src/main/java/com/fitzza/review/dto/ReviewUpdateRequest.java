package com.fitzza.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 별점·내용·사진을 통째로 바꾼다. imageUrl을 보내지 않거나 null이면 사진을 지운다.
public record ReviewUpdateRequest(
        @NotNull(message = "별점을 골라주세요.")
                @Min(value = 1, message = "별점은 1~5점입니다.")
                @Max(value = 5, message = "별점은 1~5점입니다.")
                Integer rating,
        @NotBlank(message = "리뷰 내용을 입력해주세요.")
                @Size(max = ReviewCreateRequest.MAX_CONTENT_LENGTH, message = "리뷰는 1000자까지 쓸 수 있습니다.")
                String content,
        @Size(max = 500, message = "이미지 주소가 너무 깁니다.") String imageUrl) {
}
