package com.fitzza.order.support;

import com.fitzza.order.cart.entity.CartEntity;
import com.fitzza.order.global.client.ProductOptionResponse;
import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class TestFixtures {

    private TestFixtures() {
    }

    public static ProductOptionResponse option(Long optionId, int price, String status) {
        return new ProductOptionResponse(
                optionId, optionId + 1000, "Fitzza", "상품 " + optionId, "BLACK", "M", price, null, status);
    }

    public static CartEntity cart(Long cartId, Long userId, Long optionId, int quantity) {
        CartEntity cart = CartEntity.builder().userId(userId).optionId(optionId).quantity(quantity).build();
        ReflectionTestUtils.setField(cart, "cartId", cartId);
        return cart;
    }

    public static FeignException feignException(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET, "/internal/product-options", Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("getOptions", feign.Response.builder()
                .status(status)
                .reason("test")
                .request(request)
                .headers(Map.of())
                .build());
    }

    public static void assertErrorCode(ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
