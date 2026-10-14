package com.fitzza.order.cart.dto;

import com.fitzza.order.cart.entity.CartEntity;
import com.fitzza.order.global.client.ProductOptionResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "장바구니 항목")
public record CartItemResponse(
        @Schema(description = "장바구니 ID", example = "1") Long cartId,
        @Schema(description = "상품 옵션 ID", example = "10") Long optionId,
        @Schema(description = "수량", example = "2") Integer quantity,
        @Schema(description = "구매 가능 여부. 옵션이 없어졌거나 판매 중이 아니면 false") boolean available,
        @Schema(description = "상품 ID") Long productId,
        @Schema(description = "브랜드명") String brandName,
        @Schema(description = "상품명") String productName,
        @Schema(description = "색상") String color,
        @Schema(description = "사이즈") String size,
        @Schema(description = "대표 이미지 URL") String imageUrl,
        @Schema(description = "개당 가격(원). 구매 불가 항목이면 null") Integer unitPrice,
        @Schema(description = "항목 합계(원). 구매 불가 항목이면 0", example = "78000") long linePrice) {

    public static CartItemResponse of(CartEntity cart, ProductOptionResponse option) {
        if (option == null) {
            return new CartItemResponse(
                    cart.getCartId(), cart.getOptionId(), cart.getQuantity(), false,
                    null, null, null, null, null, null, null, 0L);
        }
        boolean available = option.onSale();
        return new CartItemResponse(
                cart.getCartId(),
                cart.getOptionId(),
                cart.getQuantity(),
                available,
                option.productId(),
                option.brandName(),
                option.productName(),
                option.color(),
                option.size(),
                option.imageUrl(),
                available ? option.price() : null,
                available ? (long) option.price() * cart.getQuantity() : 0L);
    }
}
