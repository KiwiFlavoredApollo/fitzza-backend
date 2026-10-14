package com.fitzza.review.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI reviewOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fitzza Review Service API")
                        .description("상품 리뷰와 구매 상품 사이즈 설문 API. 오류 응답은 모두 {status, code, message} 형태다. "
                                + "리뷰 목록 조회만 토큰 없이 되고, 나머지는 로그인이 필요하다.")
                        .version("v1"))
                // 문서는 Gateway의 Swagger UI에서 열리므로 호출 주소를 Gateway 기준 상대 경로로 둔다.
                .servers(List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
