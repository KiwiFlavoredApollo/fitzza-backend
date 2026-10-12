package com.fitzza.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// 토큰 자체의 검사는 AccessTokenVerifierTest와 JwtAuthenticationFilterTest에서 본다.
// 여기서는 JwtAuthenticationFilter를 지난 뒤의 요청, 즉 검증된 사용자 헤더가 있거나 없는 요청만 다룬다.
class UserAuthenticationFilterTest {

    private final UserAuthenticationFilter filter = new UserAuthenticationFilter();

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/users/me", "PATCH /api/users/me/body"})
    void forwardsRequestsTheTokenFilterAuthenticated(String endpoint) {
        AtomicReference<ServerWebExchange> forwarded = filter(exchange(endpoint, "user-service", "42"));

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().get(JwtAuthenticationFilter.USER_ID_HEADER))
                .containsExactly("42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/users/me", "PATCH /api/users/me/body",
            "GET /api/users/new-private-endpoint", "POST /api/users/profile/options",
            "GET /api/users/%6de", "GET /api/users/me/"})
    void rejectsUserRouteRequestsWithoutAnAuthenticatedUser(String endpoint) {
        MockServerWebExchange exchange = exchange(endpoint, "user-service", null);

        assertThat(filter(exchange).get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"status\":401")
                .contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST /api/auth/signin", "POST /api/auth/refresh", "POST /api/auth/signout",
            "POST /api/auth/signup",
            "GET /api/users/profile/options", "GET /api/users/status"})
    void keepsPublicEndpointsAccessibleWithoutLogin(String endpoint) {
        assertThat(filter(exchange(endpoint, "user-service", null)).get()).isNotNull();
    }

    @Test
    void leavesOtherRoutesToTheirOwnServices() {
        assertThat(filter(exchange("GET /api/community/posts", "community-service", null)).get()).isNotNull();
    }

    @Test
    void runsAfterTheTokenFilterHasReplacedTheUserHeader() {
        JwtAuthenticationFilter tokenFilter = new JwtAuthenticationFilter(
                new AccessTokenVerifier(TestTokens.SECRET), mock(AccessTokenBlacklist.class));

        assertThat(filter.getOrder()).isGreaterThan(tokenFilter.getOrder());
    }

    private AtomicReference<ServerWebExchange> filter(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, request -> {
            forwarded.set(request);
            return Mono.empty();
        }).block();
        return forwarded;
    }

    private MockServerWebExchange exchange(String endpoint, String routeId, String verifiedUserId) {
        String[] parts = endpoint.split(" ", 2);
        MockServerHttpRequest.BaseBuilder<?> request =
                MockServerHttpRequest.method(HttpMethod.valueOf(parts[0]), parts[1]);
        if (verifiedUserId != null) {
            request.header(JwtAuthenticationFilter.USER_ID_HEADER, verifiedUserId);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(routeId).uri("http://localhost:8081").predicate(ignored -> true).build());
        return exchange;
    }
}
