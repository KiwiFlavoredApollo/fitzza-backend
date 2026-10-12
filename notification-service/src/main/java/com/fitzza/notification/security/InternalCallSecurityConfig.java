package com.fitzza.notification.security;

import com.fitzza.notification.controller.InternalNotificationController;
import com.fitzza.notification.exception.ErrorCode;
import com.fitzza.notification.exception.NotificationApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 알림 이벤트는 다른 서비스만 보낼 수 있다. 토큰 없이 받으면 누구나 남에게 가짜 알림을 만들 수 있다.
@Configuration
public class InternalCallSecurityConfig implements WebMvcConfigurer {

    private final byte[] internalCallToken;

    public InternalCallSecurityConfig(@Value("${internal.call-token}") String internalCallToken) {
        if (internalCallToken == null || internalCallToken.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_CALL_TOKEN must be set and non-blank");
        }
        this.internalCallToken = internalCallToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // 경로 문자열이 아니라 연결된 컨트롤러로 판단해서, 경로를 다르게 적어 우회하지 못하게 한다.
                if (handler instanceof HandlerMethod method
                        && InternalNotificationController.class.isAssignableFrom(method.getBeanType())) {
                    String token = request.getHeader(AuthHeaders.INTERNAL_TOKEN);
                    if (token == null
                            || !MessageDigest.isEqual(internalCallToken, token.getBytes(StandardCharsets.UTF_8))) {
                        throw new NotificationApiException(ErrorCode.UNAUTHENTICATED);
                    }
                }
                return true;
            }
        });
    }
}
