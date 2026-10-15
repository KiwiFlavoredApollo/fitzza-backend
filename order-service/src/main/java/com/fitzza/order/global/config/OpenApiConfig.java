package com.fitzza.order.global.config;

import com.fitzza.order.global.constant.ApiHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String SERVICE_TOKEN = "serviceToken";

    @Bean
    public OpenAPI orderOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fitzza Order Service API")
                        .description("장바구니와 주문 API. 사용자 식별은 X-User-Id 헤더로 전달한다. "
                                + "/api로 시작하지 않는 API는 서비스끼리만 호출하며 Gateway로는 호출할 수 없다.")
                        .version("v1"))
                .components(new Components()
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
                    "요청 사용자 ID. 게이트웨이가 검증한 값을 다른 서비스로도 그대로 전달한다.");
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
