package com.fitzza.product.support;

import static com.fitzza.product.global.constant.ApiHeaders.INTERNAL_TOKEN;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public final class InternalCallTokenFixture {

    public static final String VALUE = "test-only-internal-call-token";

    private InternalCallTokenFixture() {
    }

    public static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header(INTERNAL_TOKEN, VALUE);
    }
}
