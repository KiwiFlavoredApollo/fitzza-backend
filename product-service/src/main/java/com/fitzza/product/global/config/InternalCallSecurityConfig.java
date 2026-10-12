package com.fitzza.product.global.config;

import com.fitzza.product.global.constant.ApiHeaders;
import com.fitzza.product.global.exception.BusinessException;
import com.fitzza.product.global.exception.ErrorCode;
import com.fitzza.product.product.controller.ProductInternalController;
import com.fitzza.product.product.controller.ProductOptionInternalController;
import com.fitzza.product.wishlist.controller.WishlistInternalController;
import com.fitzza.product.worldcup.controller.WorldcupInternalController;
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
                if (handler instanceof HandlerMethod method && isInternalController(method.getBeanType())) {
                    String token = request.getHeader(ApiHeaders.INTERNAL_TOKEN);
                    if (token == null
                            || !MessageDigest.isEqual(internalCallToken, token.getBytes(StandardCharsets.UTF_8))) {
                        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                    }
                }
                return true;
            }
        });
    }

    private static boolean isInternalController(Class<?> controllerType) {
        return ProductInternalController.class.isAssignableFrom(controllerType)
                || ProductOptionInternalController.class.isAssignableFrom(controllerType)
                || WishlistInternalController.class.isAssignableFrom(controllerType)
                || WorldcupInternalController.class.isAssignableFrom(controllerType);
    }
}
