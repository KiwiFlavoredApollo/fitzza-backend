package com.fitzza.order.order.dto;

import com.fitzza.order.order.entity.OrderEntity;
import com.fitzza.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "주문 내역 항목")
public record OrderListResponse(
        @Schema(description = "주문 ID", example = "1") Long orderId,
        @Schema(description = "주문 번호", example = "ORD-20261007-3F9A1C7B2E") String orderNum,
        @Schema(description = "총 결제 금액(원)", example = "117000") Long totalPrice,
        @Schema(description = "주문 상태") OrderStatus orderStatus,
        @Schema(description = "주문 일시") Instant createdAt,
        @Schema(description = "주문 항목 수", example = "2") long itemCount,
        @Schema(description = "대표 썸네일 URL. 상품 서비스를 조회하지 못하면 null") String thumbnailUrl) {

    public static OrderListResponse of(OrderEntity order, long itemCount, String thumbnailUrl) {
        return new OrderListResponse(
                order.getOrderId(),
                order.getOrderNum(),
                order.getTotalPrice(),
                order.getOrderStatus(),
                order.getCreatedAt(),
                itemCount,
                thumbnailUrl);
    }
}
