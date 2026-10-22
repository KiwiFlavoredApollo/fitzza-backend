package com.fitzza.product.global.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceTokenSecurityConfigTest {

    private static final String COMMUNITY = "test-only-community-service-token-32b";
    private static final String ORDER = "test-only-order-service-token-32bytes";
    private static final String RECOMMENDATION = "test-only-recommendation-token-32bytes";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "too-short"})
    void missingBlankOrShortRequiredTokensAreRejected(String token) {
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(token, ORDER, RECOMMENDATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(COMMUNITY, token, RECOMMENDATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recommendationTokenIsOptionalButMustDifferFromTheOthers() {
        new ServiceTokenSecurityConfig(COMMUNITY, ORDER, "");
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(COMMUNITY, ORDER, COMMUNITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(COMMUNITY, COMMUNITY, RECOMMENDATION))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
