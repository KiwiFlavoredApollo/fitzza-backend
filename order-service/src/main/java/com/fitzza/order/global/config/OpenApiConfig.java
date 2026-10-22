package com.fitzza.order.global.config;

import com.fitzza.order.global.constant.ApiHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
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
    public OpenAPI orderOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fitzza Order Service API")
                        .description("장바구니와 주문 API. Gateway(8080)에서는 Authorize에 Access Token(Bearer)을 넣는다. "
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
        return (operation, handlerMethod) -> {
            addHeaderIfAbsent(operation, ApiHeaders.USER_ID,
                    "서비스가 받는 사용자 ID. Gateway 경유 시 JWT에서 설정되므로 Try it out에서는 Authorize Bearer를 쓴다.");
            addHeaderIfAbsent(operation, ApiHeaders.SERVICE_TOKEN,
                    "호출하는 서비스의 토큰. 서비스 전용 API에서만 검사한다.");
            return operation;
        };
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
                .pathsToMatch("/order-items", "/order-items/**")
                .build();
    }

    private static void addHeaderIfAbsent(Operation operation, String name, String description) {
        if (operation.getParameters() != null && operation.getParameters().stream()
                .anyMatch(parameter -> name.equals(parameter.getName()) && "header".equals(parameter.getIn()))) {
            return;
        }
        operation.addParametersItem(header(name, description));
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
