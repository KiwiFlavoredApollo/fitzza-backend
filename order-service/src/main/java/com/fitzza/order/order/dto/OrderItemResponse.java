package com.fitzza.order.order.dto;

import com.fitzza.order.global.client.ProductOptionResponse;
import com.fitzza.order.order.entity.OrderItemEntity;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 항목. 수량과 단가는 주문 시점 스냅샷이며 상품 정보는 조회 시점의 값이다.")
public record OrderItemResponse(
        @Schema(description = "주문 항목 ID", example = "1") Long orderItemId,
        @Schema(description = "상품 ID. 상품 서비스를 조회하지 못하면 null") Long productId,
        @Schema(description = "상품명. 상품 서비스를 조회하지 못하면 null") String productName,
        @Schema(description = "색상. 상품 서비스를 조회하지 못하면 null") String color,
        @Schema(description = "사이즈. 상품 서비스를 조회하지 못하면 null") String size,
        @Schema(description = "주문 수량", example = "2") Integer orderQty,
        @Schema(description = "주문 시점 단가(원)", example = "39000") Integer unitPrice) {

    public static OrderItemResponse of(OrderItemEntity item, ProductOptionResponse option) {
        if (option == null) {
            return new OrderItemResponse(
                    item.getOrderItemId(), null, null, null, null, item.getOrderQty(), item.getUnitPrice());
        }
        return new OrderItemResponse(
                item.getOrderItemId(),
                option.productId(),
                option.productName(),
                option.color(),
                option.size(),
                item.getOrderQty(),
                item.getUnitPrice());
    }
}
