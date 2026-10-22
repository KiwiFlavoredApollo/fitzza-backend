package com.fitzza.product.global.config;

import com.fitzza.product.global.constant.ApiHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    public static final String SERVICE_TOKEN = "serviceToken";

    @Bean
    public OpenAPI productOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fitzza Product Service API")
                        .description("상품, 찜, 월드컵 API. Gateway(8080)에서는 Authorize에 Access Token(Bearer)을 넣는다. "
                                + "Gateway가 JWT를 검증한 뒤 X-User-Id로 이 서비스에 전달한다. "
                                + "/api로 시작하지 않는 API는 서비스끼리만 호출하며 Gateway로는 호출할 수 없다.")
                        .version("v1"))
                // Gateway Swagger UI에서 열리므로 Try it out은 Gateway 호스트(/)를 쓴다.
                .servers(List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Gateway로 호출할 때 쓰는 Access Token"))
                        .addSecuritySchemes(
                                SERVICE_TOKEN,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(ApiHeaders.SERVICE_TOKEN)));
    }

    @Bean
    public OperationCustomizer globalServiceHeaders() {
        return (operation, handlerMethod) -> operation
                .addParametersItem(header(
                        ApiHeaders.USER_ID,
                        "서비스가 받는 사용자 ID. Gateway 경유 시 JWT에서 설정되므로 Try it out에서는 Authorize Bearer를 쓴다."))
                .addParametersItem(header(
                        ApiHeaders.SERVICE_TOKEN, "호출하는 서비스의 토큰. 서비스 전용 API에서만 검사한다."));
    }

    @Bean
    public GroupedOpenApi publicApiGroup() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/api/**")
                .addOperationCustomizer((operation, handlerMethod) -> {
                    operation.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
                    return operation;
                })
                .build();
    }

    @Bean
    public GroupedOpenApi serviceApiGroup() {
        return GroupedOpenApi.builder()
                .group("service")
                .pathsToMatch("/products", "/products/**", "/product-options", "/wishlists/**", "/worldcups/**")
                .build();
    }

    private static Parameter header(String name, String description) {
        return new Parameter()
                .in("header")
                .name(name)
                .description(description)
                .required(false)
                .schema(new StringSchema());
    }
}
