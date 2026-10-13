package com.fitzza.community.notification;

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
    // notification-service의 서비스 전용 API는 호출하는 서비스의 토큰을 이 헤더로 받는다.
    static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    static final String EVENTS_PATH = "/notifications/events";

    private final RestClient restClient;

    public NotificationServiceClient(
            @LoadBalanced RestClient.Builder restClientBuilder,
            @Value("${fitzza.notification-service.base-url:http://notification-service}") String baseUrl,
            @Value("${fitzza.service-token}") String serviceToken) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("COMMUNITY_SERVICE_TOKEN must be set and non-blank");
        }
        // 다른 클라이언트와 같은 빌더 빈을 쓰므로, 주소와 헤더가 섞이지 않게 복사본에 설정한다.
        this.restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken)
                .build();
    }

    @Override
    public boolean send(NotificationEvent event) {
        try {
            restClient
                    .post()
                    .uri(EVENTS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException | IllegalStateException exception) {
            // 알림 서비스가 없거나 꺼져 있으면 Eureka 조회 단계에서 IllegalStateException이 날 수 있다.
            log.warn("알림 전송 실패, 알림 없이 진행합니다. eventId={}: {}", event.eventId(), exception.getMessage());
            return false;
        }
    }
}
