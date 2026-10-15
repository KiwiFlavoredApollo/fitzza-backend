package com.fitzza.order.global.exception;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "오류 응답")
public record ApiErrorResponse(
        @Schema(description = "HTTP 상태 코드", example = "404") int status,
        @Schema(description = "오류 코드", example = "ORDER_NOT_FOUND") String code,
        @Schema(description = "오류 메시지", example = "주문을 찾을 수 없습니다.") String message) {
}
