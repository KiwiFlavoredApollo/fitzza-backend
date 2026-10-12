package com.fitzza.community.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

// 알림 전송처럼 응답을 기다릴 필요가 없는 일을 요청 스레드와 분리해 실행한다.
@Configuration
@EnableAsync
public class AsyncConfig {
}
