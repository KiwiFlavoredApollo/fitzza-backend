package com.fitzza.notification.config;

import com.fitzza.notification.logging.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

// 푸시 전송처럼 응답을 기다릴 필요가 없는 일을 요청 스레드와 분리해 실행한다.
@Configuration
@EnableAsync
public class AsyncConfig {

    // Spring Boot가 만드는 비동기 실행기에 적용돼, 별도 스레드의 로그에도 요청 ID가 이어진다.
    @Bean
    public TaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }
}
