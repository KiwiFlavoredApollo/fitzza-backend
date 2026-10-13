package com.fitzza.order.global.config;

import com.fitzza.order.global.client.ServiceCallRequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(basePackages = "com.fitzza.order")
public class FeignConfig {

    @Bean
    public ServiceCallRequestInterceptor serviceCallRequestInterceptor(
            @Value("${fitzza.service-token}") String serviceToken) {
        return new ServiceCallRequestInterceptor(serviceToken);
    }
}
