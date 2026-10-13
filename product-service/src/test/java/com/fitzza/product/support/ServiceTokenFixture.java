package com.fitzza.product.support;

import static com.fitzza.product.global.constant.ApiHeaders.SERVICE_TOKEN;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public final class ServiceTokenFixture {

    public static final String COMMUNITY = "test-only-community-service-token-32b";
    public static final String ORDER = "test-only-order-service-token-32bytes";
    public static final String RECOMMENDATION = "test-only-recommendation-token-32bytes";

    private ServiceTokenFixture() {
    }

    public static MockHttpServletRequestBuilder community(MockHttpServletRequestBuilder request) {
        return request.header(SERVICE_TOKEN, COMMUNITY);
    }

    public static MockHttpServletRequestBuilder order(MockHttpServletRequestBuilder request) {
        return request.header(SERVICE_TOKEN, ORDER);
    }

    public static MockHttpServletRequestBuilder recommendation(MockHttpServletRequestBuilder request) {
        return request.header(SERVICE_TOKEN, RECOMMENDATION);
    }
}
