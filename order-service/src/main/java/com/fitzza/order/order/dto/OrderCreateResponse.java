package com.fitzza.order.order.dto;

import com.fitzza.order.order.entity.OrderEntity;
import com.fitzza.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "주문 생성 응답")
public record OrderCreateResponse(
        @Schema(description = "주문 ID", example = "1") Long orderId,
        @Schema(description = "주문 번호", example = "ORD-20261007-3F9A1C7B2E") String orderNum,
        @Schema(description = "총 결제 금액(원)", example = "117000") Long totalPrice,
        @Schema(description = "주문 상태") OrderStatus orderStatus) {

    public static OrderCreateResponse of(OrderEntity order) {
        return new OrderCreateResponse(
                order.getOrderId(),
                order.getOrderNum(),
                order.getTotalPrice(),
                order.getOrderStatus());
    }
}
