package com.fitzza.product.product.controller;

import com.fitzza.product.global.config.OpenApiConfig;
import com.fitzza.product.global.constant.PagingConstants;
import com.fitzza.product.global.exception.ApiErrorResponse;
import com.fitzza.product.global.security.ServiceApi;
import com.fitzza.product.global.security.ServiceCallers;
import com.fitzza.product.product.dto.ProductOptionInternalResponse;
import com.fitzza.product.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "서비스 전용 API", description = "서비스끼리만 호출한다. 호출하는 서비스의 X-Service-Token이 필요하고 "
        + "Gateway로는 호출할 수 없다.")
@SecurityRequirement(name = OpenApiConfig.SERVICE_TOKEN)
@ServiceApi(callers = {})
@RestController
@RequestMapping("/product-options")
@RequiredArgsConstructor
public class ProductOptionInternalController {

    private final ProductService productService;

    @Operation(summary = "상품 옵션 일괄 조회",
            description = "옵션 ID 목록에 해당하는 옵션과 상품 정보(옵션 적용 가격, 판매 가능 여부 포함)를 반환한다. "
                    + "존재하지 않는 옵션 ID는 오류 없이 결과에서 제외한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "옵션 ID가 없거나 100개를 초과함",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: order-service 토큰이 아님",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @ServiceApi(callers = ServiceCallers.ORDER)
    @GetMapping
    public List<ProductOptionInternalResponse> getProductOptions(
            @Parameter(description = "옵션 ID 목록(쉼표 구분, 최대 100개)", example = "1,2,3")
            @RequestParam
            @NotEmpty @Size(max = PagingConstants.MAX_INTERNAL_IDS) List<@NotNull Long> optionIds) {
        return productService.findProductOptions(optionIds);
    }
}
