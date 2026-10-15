package com.fitzza.order.order.dto;

import com.fitzza.order.global.client.ProductOptionResponse;
import com.fitzza.order.order.entity.OrderEntity;
import com.fitzza.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "주문 상세")
public record OrderDetailResponse(
        @Schema(description = "주문 ID", example = "1") Long orderId,
        @Schema(description = "주문 번호", example = "ORD-20261007-3F9A1C7B2E") String orderNum,
        @Schema(description = "주문 상태") OrderStatus orderStatus,
        @Schema(description = "수령인") OrderRecipientResponse recipient,
        @Schema(description = "배송 주소") String shippingAddress,
        @Schema(description = "주문 항목") List<OrderItemResponse> items,
        @Schema(description = "총 결제 금액(원)", example = "117000") Long totalPrice) {

    public static OrderDetailResponse of(OrderEntity order, Map<Long, ProductOptionResponse> options) {
        List<OrderItemResponse> items = order.getOrderItems().stream()
                .map(item -> OrderItemResponse.of(item, options.get(item.getOptionId())))
                .toList();
        return new OrderDetailResponse(
                order.getOrderId(),
                order.getOrderNum(),
                order.getOrderStatus(),
                new OrderRecipientResponse(order.getRecipientName(), order.getRecipientPhone()),
                order.getShippingAddress(),
                items,
                order.getTotalPrice());
    }
}
