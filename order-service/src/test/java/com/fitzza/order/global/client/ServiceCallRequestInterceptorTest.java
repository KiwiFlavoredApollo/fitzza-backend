package com.fitzza.order.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.order.global.constant.ApiHeaders;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ServiceCallRequestInterceptorTest {

    private static final String ORDER_TOKEN = "test-only-order-service-token-32bytes";

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sendsOwnTokenAndRelaysCurrentUserId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiHeaders.USER_ID, "7");
        request.addHeader(ApiHeaders.SERVICE_TOKEN, "caller-token-must-not-be-forwarded");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();
        template.header(ApiHeaders.SERVICE_TOKEN, "already-on-template");
        template.header(ApiHeaders.USER_ID, "9");

        new ServiceCallRequestInterceptor(ORDER_TOKEN).apply(template);

        assertThat(template.headers().get(ApiHeaders.SERVICE_TOKEN)).containsExactly(ORDER_TOKEN);
        assertThat(template.headers().get(ApiHeaders.USER_ID)).containsExactly("7");
    }

    @Test
    void sendsTokenWithoutARequest() {
        RequestTemplate template = new RequestTemplate();

        new ServiceCallRequestInterceptor(ORDER_TOKEN).apply(template);

        assertThat(template.headers().get(ApiHeaders.SERVICE_TOKEN)).containsExactly(ORDER_TOKEN);
        assertThat(template.headers()).doesNotContainKey(ApiHeaders.USER_ID);
    }

    @Test
    void blankUserIdIsNotRelayed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiHeaders.USER_ID, " ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        new ServiceCallRequestInterceptor(ORDER_TOKEN).apply(template);

        assertThat(template.headers()).doesNotContainKey(ApiHeaders.USER_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "too-short"})
    void blankOrShortTokenIsRejected(String token) {
        assertThatThrownBy(() -> new ServiceCallRequestInterceptor(token))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
