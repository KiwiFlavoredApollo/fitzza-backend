package com.fitzza.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!prod")
public class ApiDocsRouteConfiguration {

    @Bean
    RouteLocator apiDocsRoutes(RouteLocatorBuilder builder) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (String service : new String[] {
                "user-service", "community-service", "notification-service", "review-service"}) {
            routes.route(service + "-docs", route -> route.path("/api-docs/" + service)
                    .filters(filters -> filters.setPath("/v3/api-docs"))
                    .uri("lb://" + service));
        }
        // product·order는 public/service 그룹이 있어 Gateway에서는 /api 공개 스펙만 노출한다.
        for (String service : new String[] {"product-service", "order-service"}) {
            routes.route(service + "-docs", route -> route.path("/api-docs/" + service)
                    .filters(filters -> filters.setPath("/v3/api-docs/public"))
                    .uri("lb://" + service));
        }
        return routes.build();
    }
}
