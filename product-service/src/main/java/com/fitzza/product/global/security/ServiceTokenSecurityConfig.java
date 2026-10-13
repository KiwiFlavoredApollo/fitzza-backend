package com.fitzza.product.global.security;

import com.fitzza.product.global.constant.ApiHeaders;
import com.fitzza.product.global.exception.BusinessException;
import com.fitzza.product.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 서비스마다 다른 토큰을 받아, 누가 불렀는지 확인한 뒤 @ServiceApi에 허용된 서비스만 통과시킨다.
// 토큰 하나가 새도 그 서비스에 허용된 API만 부를 수 있다.
@Configuration
public class ServiceTokenSecurityConfig implements WebMvcConfigurer {

    static final int MIN_TOKEN_BYTES = 32;

    private final Map<String, byte[]> tokensByCaller;

    // community·order는 지금 이 서비스를 호출하므로 필수다. recommendation은 실측 조회를 붙일 때 설정한다.
    public ServiceTokenSecurityConfig(
            @Value("${fitzza.service-tokens.community}") String communityToken,
            @Value("${fitzza.service-tokens.order}") String orderToken,
            @Value("${fitzza.service-tokens.recommendation:}") String recommendationToken) {
        Map<String, byte[]> tokens = new LinkedHashMap<>();
        register(tokens, ServiceCallers.COMMUNITY, communityToken, true);
        register(tokens, ServiceCallers.ORDER, orderToken, true);
        register(tokens, ServiceCallers.RECOMMENDATION, recommendationToken, false);
        this.tokensByCaller = Map.copyOf(tokens);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // 경로 문자열이 아니라 연결된 메서드로 판단해서, 경로를 다르게 적어 우회하지 못하게 한다.
                if (handler instanceof HandlerMethod method) {
                    ServiceApi serviceApi = serviceApiOf(method);
                    if (serviceApi != null
                            && !Arrays.asList(serviceApi.callers())
                                    .contains(callerOf(request.getHeader(ApiHeaders.SERVICE_TOKEN)))) {
                        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                    }
                }
                return true;
            }
        });
    }

    private static ServiceApi serviceApiOf(HandlerMethod method) {
        ServiceApi onMethod = method.getMethodAnnotation(ServiceApi.class);
        return onMethod != null ? onMethod : method.getBeanType().getAnnotation(ServiceApi.class);
    }

    // 모든 토큰과 끝까지 비교해서, 어느 서비스 토큰과 맞는지가 응답 시간으로 드러나지 않게 한다.
    private String callerOf(String token) {
        if (token == null) {
            return null;
        }
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (Map.Entry<String, byte[]> entry : tokensByCaller.entrySet()) {
            if (MessageDigest.isEqual(entry.getValue(), given)) {
                matched = entry.getKey();
            }
        }
        return matched;
    }

    private static void register(Map<String, byte[]> tokens, String caller, String token, boolean required) {
        if (token == null || token.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(caller + " service token must be set");
            }
            return;
        }
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_TOKEN_BYTES) {
            throw new IllegalArgumentException(caller + " service token must be at least 32 bytes");
        }
        Set<String> sameToken = new HashSet<>();
        tokens.forEach((other, existing) -> {
            if (MessageDigest.isEqual(existing, bytes)) {
                sameToken.add(other);
            }
        });
        // 두 서비스가 같은 토큰을 쓰면 누가 불렀는지 구분할 수 없다.
        if (!sameToken.isEmpty()) {
            throw new IllegalArgumentException(caller + " service token must differ from " + sameToken);
        }
        tokens.put(caller, bytes);
    }
}
