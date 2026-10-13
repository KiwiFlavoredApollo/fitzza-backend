package com.fitzza.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fitzza.gateway.security.AccessTokenBlacklist;
import com.fitzza.gateway.security.AccessTokenVerifier;
import com.fitzza.gateway.security.JwtAuthenticationFilter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class RequestIdFilterTest {

    private static final String HEADER = RequestIdFilter.REQUEST_ID_HEADER;

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void createsAnIdWhenTheClientSentNoneAndPutsItOnTheRequestAndTheResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/me").build());

        String forwarded = forwardedId(exchange);

        assertThat(UUID.fromString(forwarded)).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER)).isEqualTo(forwarded);
    }

    @Test
    void keepsAWellFormedIdFromTheClient() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/me").header(HEADER, "fe-req_2026.10.13").build());

        assertThat(forwardedId(exchange)).isEqualTo("fe-req_2026.10.13");
        assertThat(exchange.getResponse().getHeaders().getFirst(HEADER)).isEqualTo("fe-req_2026.10.13");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space inside", "line\nbreak-injection", "한글아이디입니다요"})
    void replacesAMalformedIdFromTheClient(String received) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/me").header(HEADER, received).build());

        String forwarded = forwardedId(exchange);

        assertThat(forwarded).isNotEqualTo(received);
        assertThat(UUID.fromString(forwarded)).isNotNull();
    }

    @Test
    void runsBeforeTheTokenFilterSoRejectedRequestsAlsoCarryTheId() {
        JwtAuthenticationFilter tokenFilter = new JwtAuthenticationFilter(
                mock(AccessTokenVerifier.class), mock(AccessTokenBlacklist.class));

        assertThat(filter.getOrder()).isLessThan(tokenFilter.getOrder());
    }

    private String forwardedId(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();
        return forwarded.get().getRequest().getHeaders().getFirst(HEADER);
    }
}
