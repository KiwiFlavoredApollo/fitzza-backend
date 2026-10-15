package com.fitzza.order.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "주문 생성 요청. 선택한 장바구니 항목으로 주문을 만들고 해당 장바구니 항목은 삭제된다.")
public record OrderCreateRequest(
        @Schema(description = "주문할 장바구니 ID 목록", example = "[1, 2]")
        @NotEmpty List<@NotNull Long> cartIds,
        @Schema(description = "수령인 이름", example = "홍길동")
        @NotBlank @Size(max = 50) String recipientName,
        @Schema(description = "수령인 연락처", example = "010-1234-5678")
        @NotBlank @Pattern(regexp = "^[0-9+\\-]{9,20}$", message = "숫자, '+', '-'만 사용해 9~20자로 입력해야 합니다.")
        String recipientPhone,
        @Schema(description = "배송 주소", example = "서울특별시 강남구 테헤란로 1 101동 101호")
        @NotBlank @Size(max = 300) String shippingAddress) {
}
