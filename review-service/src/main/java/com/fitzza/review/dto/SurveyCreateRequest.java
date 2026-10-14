package com.fitzza.review.dto;

import com.fitzza.review.domain.SizeFeedback;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SurveyCreateRequest(
        @NotNull(message = "주문 상품을 선택해주세요.") @Positive(message = "주문 상품을 확인해주세요.") Long orderItemId,
        @NotNull(message = "사이즈 평가를 골라주세요.") SizeFeedback sizeFeedback) {
}
