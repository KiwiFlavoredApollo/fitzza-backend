package com.fitzza.order.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "장바구니 조회 결과. 합계에는 구매 가능한 항목만 포함된다.")
public record CartResponse(
        @Schema(description = "장바구니 항목(최근에 담은 순)") List<CartItemResponse> items,
        @Schema(description = "구매 가능 항목의 총 수량", example = "3") long totalQuantity,
        @Schema(description = "구매 가능 항목의 총 금액(원)", example = "117000") long totalPrice) {

    public static CartResponse from(List<CartItemResponse> items) {
        long totalQuantity = items.stream()
                .filter(CartItemResponse::available)
                .mapToLong(item -> item.quantity())
                .sum();
        long totalPrice = items.stream().mapToLong(CartItemResponse::linePrice).sum();
        return new CartResponse(items, totalQuantity, totalPrice);
    }
}
