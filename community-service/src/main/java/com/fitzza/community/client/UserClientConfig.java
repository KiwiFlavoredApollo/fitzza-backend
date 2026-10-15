package com.fitzza.community.client;

import com.fitzza.community.logging.RequestIdPropagation;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class UserClientConfig {

    private static final int CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int READ_TIMEOUT_MILLIS = 2000;

    // @LoadBalanced가 붙으면 http://user-service 같은 서비스 이름을 Eureka에 등록된 주소로 바꿔 호출한다.
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return plainRestClientBuilder();
    }

    // Eureka에 등록되지 않은 FastAPI 서비스(추천·가상 피팅)를 주소로 부를 때 쓴다. 시간 제한과 요청 ID 전달은 같다.
    static RestClient.Builder plainRestClientBuilder() {
        return RestClient.builder().requestFactory(timeoutRequestFactory()).requestInterceptor(new RequestIdPropagation());
    }

    private static ClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
        return requestFactory;
    }
}
