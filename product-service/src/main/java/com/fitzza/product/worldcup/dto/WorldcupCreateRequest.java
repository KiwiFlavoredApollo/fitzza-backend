package com.fitzza.product.worldcup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "월드컵 결과 저장 요청")
public record WorldcupCreateRequest(
        @Schema(description = "우승 상품 ID", example = "3") @NotNull Long winnerProductId,
        @Schema(description = "월드컵에 참여한 후보 상품 ID 목록(우승 포함). 모두 요청 사용자의 찜이어야 한다.", example = "[1, 2, 3, 4]")
        @NotNull @Size(min = 2, max = 64) List<@NotNull Long> candidateProductIds) {
}
