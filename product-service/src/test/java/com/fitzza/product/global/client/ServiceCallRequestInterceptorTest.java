package com.fitzza.product.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.product.global.constant.ApiHeaders;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ServiceCallRequestInterceptorTest {

    private static final String PRODUCT_TOKEN = "test-only-product-service-token-32bytes";

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void blankTokenIsOmittedAndCurrentUserIdIsRelayed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiHeaders.USER_ID, "7");
        request.addHeader(ApiHeaders.SERVICE_TOKEN, "caller-token-must-not-be-forwarded");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();
        template.header(ApiHeaders.SERVICE_TOKEN, "already-on-template");
        template.header(ApiHeaders.USER_ID, "9");

        new ServiceCallRequestInterceptor("  ").apply(template);

        assertThat(template.headers()).doesNotContainKey(ApiHeaders.SERVICE_TOKEN);
        assertThat(template.headers().get(ApiHeaders.USER_ID)).containsExactly("7");
    }

    @Test
    void configuredTokenIsSentWithoutARequest() {
        RequestTemplate template = new RequestTemplate();

        new ServiceCallRequestInterceptor(PRODUCT_TOKEN).apply(template);

        assertThat(template.headers().get(ApiHeaders.SERVICE_TOKEN)).containsExactly(PRODUCT_TOKEN);
        assertThat(template.headers()).doesNotContainKey(ApiHeaders.USER_ID);
    }

    @Test
    void blankUserIdIsNotRelayed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiHeaders.USER_ID, " ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        new ServiceCallRequestInterceptor(PRODUCT_TOKEN).apply(template);

        assertThat(template.headers()).doesNotContainKey(ApiHeaders.USER_ID);
    }

    @Test
    void shortTokenIsRejected() {
        assertThatThrownBy(() -> new ServiceCallRequestInterceptor("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
