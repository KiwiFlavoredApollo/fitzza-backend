package com.fitzza.order.order.dto;

import com.fitzza.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "서비스 전용 주문 항목. 리뷰·설문이 본인 주문인지와 배송 완료 여부를 확인할 때 쓴다.")
public record OrderItemInternalResponse(
        @Schema(description = "요청 userId의 주문 항목인지", example = "true") boolean owned,
        @Schema(description = "상품 ID. 상품 서비스를 조회하지 못하면 null", example = "55") Long productId,
        @Schema(description = "상품 옵션 ID", example = "501") Long optionId,
        @Schema(description = "주문 상태", example = "COMPLETED") OrderStatus orderStatus) {
}
