package com.fitzza.order.order.controller;

import com.fitzza.order.global.constant.ApiHeaders;
import com.fitzza.order.global.constant.PagingConstants;
import com.fitzza.order.global.dto.PageResponse;
import com.fitzza.order.global.exception.ApiErrorResponse;
import com.fitzza.order.order.dto.OrderCreateRequest;
import com.fitzza.order.order.dto.OrderCreateResponse;
import com.fitzza.order.order.dto.OrderDetailResponse;
import com.fitzza.order.order.dto.OrderListResponse;
import com.fitzza.order.order.service.OrderService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order", description = "주문 API")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private static final String USER_ID_DESCRIPTION = "요청 사용자 ID";

    private final OrderService orderService;

    @Operation(summary = "주문 생성",
            description = "선택한 장바구니 항목의 수량과 현재 상품 가격을 주문 항목에 기록(스냅샷)하고 장바구니에서 삭제한다. 주문은 PENDING 상태로 생성된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 생성 성공"),
            @ApiResponse(responseCode = "404", description = "장바구니 항목 없음 또는 다른 사용자의 항목",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "배송 정보 누락·수량 오류 또는 판매 중이 아닌 옵션 포함",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "상품 서비스 사용 불가",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreateResponse createOrder(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Valid @RequestBody OrderCreateRequest request) {
        return orderService.createOrder(userId, request);
    }

    @Operation(summary = "주문 내역 조회", description = "최근에 주문한 순서로 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public PageResponse<OrderListResponse> getOrders(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Parameter(description = "페이지 번호(0부터 시작)")
            @RequestParam(defaultValue = PagingConstants.DEFAULT_PAGE) @Min(0) int page,
            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = PagingConstants.DEFAULT_SIZE) @Min(1) @Max(PagingConstants.MAX_SIZE) int size) {
        return orderService.findOrders(userId, page, size);
    }

    @Operation(summary = "주문 상세 조회",
            description = "본인 주문만 조회한다. 항목의 수량·단가는 주문 시점 스냅샷이며, 상품 서비스를 조회하지 못하면 상품 정보는 null이다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "다른 사용자의 주문",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "주문 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{orderId}")
    public OrderDetailResponse getOrder(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Parameter(description = "주문 ID", example = "1") @PathVariable Long orderId) {
        return orderService.findOrder(userId, orderId);
    }
}
