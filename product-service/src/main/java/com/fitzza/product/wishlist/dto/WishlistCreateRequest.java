package com.fitzza.product.wishlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "찜 추가 요청")
public record WishlistCreateRequest(
        @Schema(description = "상품 ID", example = "1")
        @NotNull @Min(1) Long itemId) {
}
