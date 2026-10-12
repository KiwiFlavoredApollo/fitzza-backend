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
        for (String service : new String[] {"user-service", "community-service", "notification-service"}) {
            routes.route(service + "-docs", route -> route.path("/api-docs/" + service)
                    .filters(filters -> filters.setPath("/v3/api-docs"))
                    .uri("lb://" + service));
        }
        return routes.build();
    }
}
