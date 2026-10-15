package com.fitzza.order.global.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = ServiceProbeController.class,
        properties = {
                "spring.cloud.config.enabled=false",
                "fitzza.service-tokens.review=test-only-review-service-token-32bytes"
        })
@Import(ServiceTokenSecurityConfig.class)
class ServiceTokenSecurityConfigTest {

    private static final String SERVICE_TOKEN = "X-Service-Token";
    private static final String COMMUNITY_TOKEN = "test-only-community-service-token-32b";
    private static final String REVIEW_TOKEN = "test-only-review-service-token-32bytes";

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Test
    void serviceApiRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/service-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "wrong-token", COMMUNITY_TOKEN})
    void serviceApiRejectsTokenThatIsNotAllowed(String token) throws Exception {
        mockMvc.perform(get("/service-probe").header(SERVICE_TOKEN, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void reviewCallerApiAcceptsReviewToken() throws Exception {
        mockMvc.perform(get("/review-probe").header(SERVICE_TOKEN, REVIEW_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void reviewCallerApiRejectsCommunityToken() throws Exception {
        mockMvc.perform(get("/review-probe").header(SERVICE_TOKEN, COMMUNITY_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void publicApiDoesNotRequireServiceToken() throws Exception {
        mockMvc.perform(get("/public-probe"))
                .andExpect(status().isOk());
    }

    @Test
    void shortOptionalCallerTokenIsRejected() {
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig("too-short", REVIEW_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingReviewTokenIsRejected() {
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig("", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingCommunityTokenIsAllowed() {
        new ServiceTokenSecurityConfig("", REVIEW_TOKEN);
        new ServiceTokenSecurityConfig(null, REVIEW_TOKEN);
    }
}

@RestController
class ServiceProbeController {

    @ServiceApi(callers = {})
    @GetMapping("/service-probe")
    void serviceProbe() {
    }

    @ServiceApi(callers = ServiceCallers.REVIEW)
    @GetMapping("/review-probe")
    void reviewProbe() {
    }

    @GetMapping("/public-probe")
    void publicProbe() {
    }
}
