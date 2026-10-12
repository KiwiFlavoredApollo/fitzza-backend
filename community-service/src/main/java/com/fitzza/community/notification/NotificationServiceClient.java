package com.fitzza.community.notification;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class NotificationServiceClient implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceClient.class);
    // notification-service의 내부 API는 이 헤더의 값이 INTERNAL_CALL_TOKEN과 같아야 받는다.
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    static final String EVENTS_PATH = "/internal/notifications/events";

    private final RestClient restClient;

    public NotificationServiceClient(
            @LoadBalanced RestClient.Builder restClientBuilder,
            @Value("${fitzza.notification-service.base-url}") String baseUrl,
            @Value("${internal.call-token}") String internalCallToken) {
        if (internalCallToken == null || internalCallToken.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_CALL_TOKEN must be set and non-blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Notification service base URL must be explicitly set to HTTPS");
        }
        URI baseUri = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(baseUri.getScheme()) || baseUri.getHost() == null) {
            throw new IllegalArgumentException("Notification service base URL must be an absolute HTTPS URL");
        }
        // 다른 클라이언트와 같은 빌더 빈을 쓰므로, 주소와 헤더가 섞이지 않게 복사본에 설정한다.
        this.restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .defaultHeader(INTERNAL_TOKEN_HEADER, internalCallToken)
                .requestInterceptor((request, body, execution) -> {
                    // Eureka가 주소를 재구성한 뒤에도 토큰이 HTTP로 전송되지 않게 한다.
                    if (!"https".equalsIgnoreCase(request.getURI().getScheme())) {
                        throw new IllegalStateException("Notification requests must use HTTPS");
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public void send(NotificationEvent event) {
        try {
            restClient
                    .post()
                    .uri(EVENTS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException | IllegalStateException exception) {
            // 알림 서비스가 없거나 꺼져 있으면 Eureka 조회 단계에서 IllegalStateException이 날 수 있다.
            log.warn("알림 전송 실패, 알림 없이 진행합니다. eventId={}: {}", event.eventId(), exception.getMessage());
        }
    }
}
