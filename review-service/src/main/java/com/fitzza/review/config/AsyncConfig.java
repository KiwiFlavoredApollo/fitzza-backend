package com.fitzza.review.config;

import com.fitzza.review.logging.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// 추천 서비스로 설문 결과를 보내는 일을 요청 스레드와 분리하고, 보내지 못한 설문을 주기적으로 다시 보낸다.
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    // Spring Boot가 만드는 비동기 실행기에 적용돼, 별도 스레드의 로그에도 요청 ID가 이어진다.
    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }
}
