package com.fitzza.review.client;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

// 주문 서비스의 서비스 전용 API(GET /order-items/{orderItemId}?userId=)를 부른다.
@Component
public class OrderServiceClient implements OrderItems {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceClient.class);

    static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final RestClient restClient;
    private final boolean tokenConfigured;

    // 공용 빌더를 그대로 고치면 다른 클라이언트의 주소가 바뀌므로 복사해서 쓴다.
    public OrderServiceClient(
            @LoadBalanced RestClient.Builder restClientBuilder,
            @Value("${fitzza.order-service.base-url:http://order-service}") String baseUrl,
            @Value("${fitzza.service-token:}") String serviceToken) {
        this.tokenConfigured = serviceToken != null && !serviceToken.isBlank();
        RestClient.Builder builder = restClientBuilder.clone().baseUrl(baseUrl);
        if (tokenConfigured) {
            builder.defaultHeader(SERVICE_TOKEN_HEADER, serviceToken);
        }
        this.restClient = builder.build();
    }

    @Override
    public Optional<OrderItem> findOrderItem(long orderItemId, Long userId) {
        // 토큰이 없으면 주문 서비스가 401로 거절하므로 부르지 않고 일시 장애와 같이 다룬다(설정 전까지 작성 불가).
        if (!tokenConfigured) {
            log.warn("REVIEW_SERVICE_TOKEN이 설정되지 않아 주문 항목을 확인할 수 없습니다.");
            throw new ResourceAccessException("REVIEW_SERVICE_TOKEN is not configured");
        }
        try {
            return Optional.ofNullable(restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/order-items/{orderItemId}")
                            .queryParam("userId", userId)
                            .build(orderItemId))
                    .retrieve()
                    .body(OrderItem.class));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        }
    }
}
