package com.fitzza.product.global.config;

import com.fitzza.product.global.constant.ApiHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
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
    public OpenAPI productOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Fitzza Product Service API")
                        .description("상품, 찜, 월드컵 API. 사용자 식별은 X-User-Id 헤더로 전달한다. "
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
        return (operation, handlerMethod) -> operation
                .addParametersItem(header(ApiHeaders.USER_ID, "요청 사용자 ID. 게이트웨이가 검증한 값을 다른 서비스로도 그대로 전달한다."))
                .addParametersItem(header(ApiHeaders.SERVICE_TOKEN, "호출하는 서비스의 토큰. 서비스 전용 API에서만 검사한다."));
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
