package com.fitzza.product.worldcup.controller;

import com.fitzza.product.global.exception.ApiErrorResponse;
import com.fitzza.product.worldcup.dto.WorldcupInternalResponse;
import com.fitzza.product.worldcup.service.WorldcupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Worldcup Internal", description = "서비스 간 통신용 월드컵 API")
@RestController
@RequestMapping("/internal/worldcups")
@RequiredArgsConstructor
public class WorldcupInternalController {

    private final WorldcupService worldcupService;

    @Operation(summary = "월드컵 결과 조회", description = "월드컵 이력의 소유 여부(owned)와 소유한 경우 우승 상품 정보를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "월드컵 이력 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{worldcupHistoryId}")
    public WorldcupInternalResponse getWorldcup(
            @Parameter(description = "월드컵 이력 ID", example = "1") @PathVariable Long worldcupHistoryId,
            @Parameter(description = "소유 여부를 확인할 사용자 ID", required = true) @RequestParam Long userId) {
        return worldcupService.findWorldcup(worldcupHistoryId, userId);
    }
}
