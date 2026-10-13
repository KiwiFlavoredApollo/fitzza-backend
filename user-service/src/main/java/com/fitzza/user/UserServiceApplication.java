package com.fitzza.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 서비스 상태는 /actuator/health로 확인한다.
@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) { SpringApplication.run(UserServiceApplication.class, args); }
}
