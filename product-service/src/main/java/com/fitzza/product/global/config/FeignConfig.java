package com.fitzza.product.global.config;

import com.fitzza.product.global.client.ServiceCallRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(basePackages = "com.fitzza.product")
public class FeignConfig {

    @Bean
    public ServiceCallRequestInterceptor serviceCallRequestInterceptor(
            @Value("${fitzza.service-token:}") String serviceToken) {
        return new ServiceCallRequestInterceptor(serviceToken);
    }
}
