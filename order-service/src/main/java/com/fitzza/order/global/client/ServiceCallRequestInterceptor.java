package com.fitzza.order.global.client;

import com.fitzza.order.global.constant.ApiHeaders;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import java.nio.charset.StandardCharsets;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// 다른 서비스로 나가는 Feign 요청에 이 서비스의 토큰과 현재 요청의 X-User-Id를 붙인다.
public class ServiceCallRequestInterceptor implements RequestInterceptor {

    static final int MIN_TOKEN_BYTES = 32;

    private final String serviceToken;

    public ServiceCallRequestInterceptor(String serviceToken) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalArgumentException("ORDER_SERVICE_TOKEN must be set and non-blank");
        }
        if (serviceToken.getBytes(StandardCharsets.UTF_8).length < MIN_TOKEN_BYTES) {
            throw new IllegalArgumentException("ORDER_SERVICE_TOKEN must be at least 32 bytes");
        }
        this.serviceToken = serviceToken;
    }

    @Override
    public void apply(RequestTemplate template) {
        // 들어온 요청의 서비스 토큰을 그대로 넘기지 않고, 설정된 이 서비스 토큰만 보낸다.
        template.removeHeader(ApiHeaders.SERVICE_TOKEN);
        template.header(ApiHeaders.SERVICE_TOKEN, serviceToken);
        template.removeHeader(ApiHeaders.USER_ID);
        String userId = currentUserId();
        if (userId != null) {
            template.header(ApiHeaders.USER_ID, userId);
        }
    }

    private static String currentUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        String userId = servletAttributes.getRequest().getHeader(ApiHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userId;
    }
}
