package com.fitzza.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RefreshTokenCookieConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(RefreshTokenCookie.class)
            .withPropertyValues("spring.cloud.config.enabled=false", "jwt.refresh-expiration-seconds=120");

    @Test
    void secureEnvironmentVariableEnablesSecureCookies() {
        contextRunner.withPropertyValues("REFRESH_COOKIE_SECURE=true").run(context -> {
            assertThat(context).hasNotFailed();
            String cookie = context.getBean(RefreshTokenCookie.class).issue("test-token");
            assertThat(cookie).contains("; Secure", "; HttpOnly", "SameSite=Lax", "Path=/api", "Max-Age=120")
                    .doesNotContain("Domain=");
            assertThat(context.getBean(RefreshTokenCookie.class).clear())
                    .contains("; Secure", "; HttpOnly", "SameSite=Lax", "Path=/api", "Max-Age=0")
                    .doesNotContain("Domain=");
        });
    }

    @Test
    void missingSecureSettingDefaultsToHttpCompatibleCookies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RefreshTokenCookie.class).issue("test-token"))
                    .contains("HttpOnly").doesNotContain("; Secure");
        });
    }

    @Test
    void secureEnvironmentVariableCanDisableSecureCookies() {
        contextRunner.withPropertyValues("REFRESH_COOKIE_SECURE=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RefreshTokenCookie.class).issue("test-token"))
                    .contains("HttpOnly", "Max-Age=120", "Path=/api").doesNotContain("; Secure");
            assertThat(context.getBean(RefreshTokenCookie.class).clear())
                    .contains("HttpOnly", "Max-Age=0", "Path=/api").doesNotContain("; Secure");
        });
    }
}
