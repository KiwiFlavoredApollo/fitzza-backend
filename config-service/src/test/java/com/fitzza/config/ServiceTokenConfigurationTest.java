package com.fitzza.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "COMMUNITY_SERVICE_TOKEN=test-community-token-for-config-server",
    "ORDER_SERVICE_TOKEN=test-order-token-for-config-server",
    "REVIEW_SERVICE_TOKEN=test-review-token-for-config-server",
    "RECOMMENDATION_SERVICE_TOKEN=",
    "PRODUCT_SERVICE_TOKEN=",
    "VTON_SERVICE_TOKEN="
})
class ServiceTokenConfigurationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @ParameterizedTest
    @CsvSource({
        "user-service, fitzza.service-tokens.community, community",
        "product-service, fitzza.service-tokens.community, community",
        "product-service, fitzza.service-tokens.order, order",
        "order-service, fitzza.service-token, order",
        "order-service, fitzza.service-tokens.review, review",
        "community-service, fitzza.service-token, community",
        "review-service, fitzza.service-token, review",
        "notification-service, fitzza.service-tokens.community, community"
    })
    void clientResolvesTokensWithoutLocalTokenEnvironmentVariables(
            String application, String property, String tokenOwner) {
        StandardEnvironment client = fetchConfiguration(application);

        assertThat(client.getProperty(property))
                .isEqualTo("test-" + tokenOwner + "-token-for-config-server");
    }

    @Test
    void optionalTokensRemainEmptyWhenNotConfiguredOnServer() {
        StandardEnvironment client = fetchConfiguration("notification-service");

        assertThat(client.getProperty("fitzza.service-tokens.recommendation")).isEmpty();
        assertThat(client.getProperty("fitzza.service-tokens.vton")).isEmpty();
    }

    private StandardEnvironment fetchConfiguration(String application) {
        Environment configuration = restTemplate.getForObject(
                "/{application}/default", Environment.class, application);
        assertThat(configuration).isNotNull();

        StandardEnvironment client = new StandardEnvironment();
        client.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        client.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        configuration.getPropertySources().forEach(source -> {
            Map<String, Object> properties = new HashMap<>();
            source.getSource().forEach((key, value) -> properties.put(key.toString(), value));
            client.getPropertySources().addLast(new MapPropertySource(source.getName(), properties));
        });
        return client;
    }
}
