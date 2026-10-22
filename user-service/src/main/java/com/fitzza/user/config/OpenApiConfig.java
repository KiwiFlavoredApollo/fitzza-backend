package com.fitzza.user.config;

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
    public static final String REFRESH_COOKIE = "refreshCookie";
    public static final String SERVICE_TOKEN = "serviceToken";

    @Bean
    public OpenAPI userOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fitzza User Service API")
                        .description("회원가입, 로그인·토큰, 프로필 API. 오류 응답은 모두 {status, code, message} 형태다. "
                                + "/api로 시작하지 않는 API는 서비스끼리만 호출하며 Gateway로는 호출할 수 없다.")
                        .version("v1"))
                // 문서는 Gateway의 Swagger UI에서 열리므로 호출 주소를 Gateway 기준 상대 경로로 둔다.
                .servers(List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        .addSecuritySchemes(
                                REFRESH_COOKIE,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name("refreshToken"))
                        .addSecuritySchemes(
                                SERVICE_TOKEN,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Service-Token")));
    }
}
