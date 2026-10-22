package com.fitzza.order.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "장바구니 수정 요청. 옵션을 바꾸면 이미 담긴 다른 옵션 항목과 수량이 합쳐진다.")
public record CartUpdateRequest(
        @Schema(description = "변경 후 상품 옵션 ID", example = "11") @NotNull Long optionId,
        @Schema(description = "변경 후 수량", example = "2")
        @NotNull @Min(1) Integer quantity) {
}
