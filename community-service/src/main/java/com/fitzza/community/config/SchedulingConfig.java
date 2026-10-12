package com.fitzza.community.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 투표 마감처럼 정해진 간격으로 도는 작업을 켠다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
