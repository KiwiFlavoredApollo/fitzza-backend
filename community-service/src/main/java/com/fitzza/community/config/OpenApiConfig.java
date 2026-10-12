package com.fitzza.community.config;

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
    public OpenAPI communityOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fitzza Community Service API")
                        .description("게시글, 댓글, 투표 API. 오류 응답은 모두 {code, message} 형태다. "
                                + "조회 API는 토큰 없이도 되지만, 만료되거나 잘못된 토큰을 실어 보내면 401이다.")
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
