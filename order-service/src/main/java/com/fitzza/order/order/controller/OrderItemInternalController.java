package com.fitzza.order.order.controller;

import com.fitzza.order.global.config.OpenApiConfig;
import com.fitzza.order.global.exception.ApiErrorResponse;
import com.fitzza.order.global.security.ServiceApi;
import com.fitzza.order.global.security.ServiceCallers;
import com.fitzza.order.order.dto.OrderItemInternalResponse;
import com.fitzza.order.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "서비스 전용 API", description = "서비스끼리만 호출한다. 호출하는 서비스의 X-Service-Token이 필요하고 "
        + "Gateway로는 호출할 수 없다.")
@SecurityRequirement(name = OpenApiConfig.SERVICE_TOKEN)
@ServiceApi(callers = {})
@RestController
@RequestMapping("/order-items")
@RequiredArgsConstructor
public class OrderItemInternalController {

    private final OrderService orderService;

    @Operation(summary = "주문 항목 조회",
            description = "리뷰·설문 작성 전 본인 주문 여부와 주문 상태를 확인한다. 항목이 없으면 404다. "
                    + "다른 사용자의 항목이어도 404 대신 owned=false로 200을 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "userId 누락 또는 잘못된 경로",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: review-service 토큰이 아님",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "주문 항목 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @ServiceApi(callers = ServiceCallers.REVIEW)
    @GetMapping("/{orderItemId}")
    public OrderItemInternalResponse getOrderItem(
            @Parameter(description = "주문 항목 ID", example = "31")
            @PathVariable @Positive Long orderItemId,
            @Parameter(description = "확인할 사용자 ID", example = "7")
            @RequestParam @NotNull @Positive Long userId) {
        return orderService.findOrderItem(orderItemId, userId);
    }
}
