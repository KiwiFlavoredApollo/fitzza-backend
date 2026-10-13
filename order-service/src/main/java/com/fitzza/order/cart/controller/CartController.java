package com.fitzza.order.cart.controller;

import com.fitzza.order.cart.dto.CartCreateRequest;
import com.fitzza.order.cart.dto.CartItemResponse;
import com.fitzza.order.cart.dto.CartResponse;
import com.fitzza.order.cart.dto.CartUpdateRequest;
import com.fitzza.order.cart.service.CartService;
import com.fitzza.order.global.constant.ApiHeaders;
import com.fitzza.order.global.exception.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cart", description = "장바구니 API")
@RestController
@RequestMapping("/api/carts")
@RequiredArgsConstructor
public class CartController {

    private static final String USER_ID_DESCRIPTION = "요청 사용자 ID";

    private final CartService cartService;

    @Operation(summary = "장바구니 조회", description = "최근에 담은 순서로 항목과 구매 가능 항목의 합계를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "503", description = "상품 서비스 사용 불가",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public CartResponse getCart(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId) {
        return cartService.findCart(userId);
    }

    @Operation(summary = "장바구니 추가", description = "이미 담긴 옵션이면 수량을 합산한다. 판매 중인 옵션만 담을 수 있다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "추가 성공"),
            @ApiResponse(responseCode = "400", description = "수량이 1보다 작음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "옵션 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "판매 중이 아닌 옵션",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CartItemResponse addCart(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Valid @RequestBody CartCreateRequest request) {
        return cartService.addCart(userId, request);
    }

    @Operation(summary = "장바구니 수정", description = "옵션과 수량을 변경한다. 바꾼 옵션이 이미 담겨 있으면 해당 항목에 수량이 합산된다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "404", description = "장바구니 항목 또는 옵션 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "판매 중이 아닌 옵션",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PatchMapping("/{cartId}")
    public CartItemResponse updateCart(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Parameter(description = "장바구니 ID", example = "1") @PathVariable Long cartId,
            @Valid @RequestBody CartUpdateRequest request) {
        return cartService.updateCart(userId, cartId, request);
    }

    @Operation(summary = "장바구니 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "404", description = "장바구니 항목 없음",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/{cartId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCart(
            @Parameter(description = USER_ID_DESCRIPTION, required = true)
            @RequestHeader(ApiHeaders.USER_ID) Long userId,
            @Parameter(description = "장바구니 ID", example = "1") @PathVariable Long cartId) {
        cartService.removeCart(userId, cartId);
    }
}
