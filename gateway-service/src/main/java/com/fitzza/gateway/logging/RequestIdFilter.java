package com.fitzza.gateway.logging;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 요청마다 X-Request-Id를 정해 뒤쪽 서비스로 넘기고 응답에도 담는다. 서비스는 이 값을 로그에 찍으므로,
 * 프론트가 응답 헤더의 ID를 알려주면 Gateway부터 각 서비스 로그까지 같은 요청을 따라갈 수 있다.
 * 클라이언트가 보낸 값은 형식이 맞을 때만 이어 쓰고, 없거나 형식이 다르면 새로 만든다(로그에 임의 문자열이 섞이지 않게).
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = resolve(exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER));
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        // 인증 실패처럼 Gateway가 직접 답하는 응답에도 ID가 남도록 체인 실행 전에 넣는다.
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    // 토큰 검증 필터(-1)보다 먼저 실행돼야 거절된 요청에도 ID가 붙는다.
    @Override
    public int getOrder() {
        return -2;
    }

    static String resolve(String received) {
        if (received != null && ALLOWED.matcher(received).matches()) {
            return received;
        }
        return UUID.randomUUID().toString();
    }
}
