package com.fitzza.order.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "수령인 정보")
public record OrderRecipientResponse(
        @Schema(description = "수령인 이름", example = "홍길동") String name,
        @Schema(description = "수령인 연락처", example = "010-1234-5678") String phone) {
}
