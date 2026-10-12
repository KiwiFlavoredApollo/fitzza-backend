package com.fitzza.order.global.config;

import com.fitzza.order.global.constant.ApiHeaders;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(basePackages = "com.fitzza.order")
public class FeignConfig {

    @Bean
    public RequestInterceptor internalCallTokenRequestInterceptor(
            @Value("${internal.call-token}") String internalCallToken) {
        if (internalCallToken == null || internalCallToken.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_CALL_TOKEN must be set and non-blank");
        }
        return template -> template.header(ApiHeaders.INTERNAL_TOKEN, internalCallToken);
    }
}
