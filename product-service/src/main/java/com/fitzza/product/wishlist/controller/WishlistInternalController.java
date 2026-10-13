package com.fitzza.product.wishlist.controller;

import com.fitzza.product.global.config.OpenApiConfig;
import com.fitzza.product.global.security.ServiceApi;
import com.fitzza.product.global.security.ServiceCallers;
import com.fitzza.product.wishlist.dto.WishlistCheckResponse;
import com.fitzza.product.wishlist.service.WishlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/wishlists")
@RequiredArgsConstructor
public class WishlistInternalController {

    private final WishlistService wishlistService;

    @Operation(summary = "찜 소유 확인", description = "사용자가 해당 상품을 찜했는지 확인한다.")
    @ApiResponse(responseCode = "200", description = "확인 성공")
    @ServiceApi(callers = ServiceCallers.COMMUNITY)
    @GetMapping("/check")
    public WishlistCheckResponse checkWishlist(
            @Parameter(description = "사용자 ID", required = true) @RequestParam Long userId,
            @Parameter(description = "상품 ID", required = true) @RequestParam @NotBlank String itemId) {
        return wishlistService.checkWishlist(userId, itemId);
    }
}
