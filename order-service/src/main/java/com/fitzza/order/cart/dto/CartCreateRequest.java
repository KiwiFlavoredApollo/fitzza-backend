package com.fitzza.order.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "장바구니 추가 요청. 이미 담긴 옵션이면 수량이 합산된다.")
public record CartCreateRequest(
        @Schema(description = "상품 옵션 ID", example = "10") @NotNull Long optionId,
        @Schema(description = "추가할 수량. 생략하면 1", example = "1", defaultValue = "1")
        @Min(1) Integer quantity) {

    private static final int DEFAULT_QUANTITY = 1;

    public CartCreateRequest {
        if (quantity == null) {
            quantity = DEFAULT_QUANTITY;
        }
    }
}
