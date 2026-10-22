package com.fitzza.product.worldcup.controller;

import com.fitzza.product.global.constant.ApiHeaders;
import com.fitzza.product.global.constant.PagingConstants;
import com.fitzza.product.global.dto.PageResponse;
import com.fitzza.product.global.exception.ApiErrorResponse;
import com.fitzza.product.worldcup.dto.WorldcupCreateRequest;
import com.fitzza.product.worldcup.dto.WorldcupResultResponse;
import com.fitzza.product.worldcup.service.WorldcupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Worldcup", description = "상품 이상형 월드컵 API")
@RestController
@RequestMapping("/api/worldcups")
@RequiredArgsConstructor
public class WorldcupController {

    private static final String USER_ID_DESCRIPTION = "요청 사용자 ID";

    private final WorldcupService worldcupService;

    @Operation(
            summary = "월드컵 결과 저장",
            description = "후보는 모두 요청 사용자의 찜이어야 한다. 우승 상품은 후보에 포함되어야 하며, 저장해도 찜 목록은 유지된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "저장 성공"),
            @ApiResponse(responseCode = "400", description = "후보 구성 오류",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "후보 상품 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorldcupResultResponse saveWorldcup(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Valid @RequestBody WorldcupCreateRequest request) {
        return worldcupService.saveWorldcup(userId, request);
    }

    @Operation(summary = "현재 우승 상품 조회", description = "사용자의 가장 최근 월드컵 우승 결과를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "월드컵 이력 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/latest")
    public WorldcupResultResponse getLatestWorldcup(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId) {
        return worldcupService.findLatestWorldcup(userId);
    }

    @Operation(summary = "우승 이력 조회", description = "최근 완료한 순서로 월드컵 우승 이력을 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping("/history")
    public PageResponse<WorldcupResultResponse> getWorldcupHistories(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Parameter(description = "페이지 번호(0부터 시작)")
            @RequestParam(defaultValue = PagingConstants.DEFAULT_PAGE) @Min(0) int page,
            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = PagingConstants.DEFAULT_SIZE) @Min(1) @Max(PagingConstants.MAX_SIZE) int size) {
        return worldcupService.findWorldcupHistories(userId, page, size);
    }
}
