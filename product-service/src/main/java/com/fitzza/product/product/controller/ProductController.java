package com.fitzza.product.product.controller;

import com.fitzza.product.global.constant.ApiHeaders;
import com.fitzza.product.global.constant.PagingConstants;
import com.fitzza.product.global.exception.ApiErrorResponse;
import com.fitzza.product.product.dto.ProductDetailResponse;
import com.fitzza.product.product.dto.ProductListPageResponse;
import com.fitzza.product.product.dto.ProductSortType;
import com.fitzza.product.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Product", description = "상품 조회 API")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "상품 목록 조회",
            description = "카테고리 코드 길이에 따라 대분류(3)·중분류(6)·소분류(9)로 조회한다. "
                    + "코드가 없으면 전체 노출 상품을 반환한다. "
                    + "sort는 LATEST(상품 ID 내림차순) 또는 LOW_PRICE(낮은 가격, 같으면 상품 ID 내림차순)이며 "
                    + "그 밖의 값은 LATEST로 처리한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 파라미터",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ProductListPageResponse getProducts(
            @Parameter(description = "카테고리 코드. 길이 3은 대분류, 6은 중분류, 9는 소분류", example = "001")
            @RequestParam(required = false) String category,
            @Parameter(description = "정렬 기준. LATEST 또는 LOW_PRICE. 잘못된 값은 LATEST", example = "LATEST")
            @RequestParam(required = false) String sort,
            @Parameter(description = "페이지 번호(0부터 시작)")
            @RequestParam(defaultValue = PagingConstants.DEFAULT_PAGE) @Min(0) int page,
            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = PagingConstants.DEFAULT_SIZE) @Min(1) @Max(PagingConstants.MAX_SIZE) int size) {
        return productService.findProducts(category, ProductSortType.from(sort), page, size);
    }

    @Operation(summary = "상품 상세 조회",
            description = "상품 기본 정보와 옵션별 실측 정보를 함께 조회한다. X-User-Id가 있으면 wished를 포함한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "상품 없음 또는 비노출 상품",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{productId}")
    public ProductDetailResponse getProduct(
            @Parameter(description = "상품 ID", example = "1") @PathVariable Long productId,
            @Parameter(description = "요청 사용자 ID. 있으면 찜 여부를 포함한다.")
            @RequestHeader(value = ApiHeaders.USER_ID, required = false) Long userId) {
        return productService.findProduct(productId, userId);
    }
}
