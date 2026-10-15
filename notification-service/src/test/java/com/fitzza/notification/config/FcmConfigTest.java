package com.fitzza.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.notification.push.LoggingFcmSender;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class FcmConfigTest {

    private static final String JSON = "{\"type\":\"service_account\"}";

    @Test
    void withoutCredentialsPushOnlyLogs() throws Exception {
        assertThat(new FcmConfig().fcmSender("")).isInstanceOf(LoggingFcmSender.class);
        assertThat(new FcmConfig().fcmSender("   ")).isInstanceOf(LoggingFcmSender.class);
    }

    @Test
    void rawJsonCredentialsAreUsedAsIs() {
        assertThat(new String(FcmConfig.decodeCredentials("  " + JSON + "\n"), StandardCharsets.UTF_8))
                .isEqualTo(JSON);
    }

    @Test
    void base64CredentialsAreDecoded() {
        String encoded = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(FcmConfig.decodeCredentials(encoded), StandardCharsets.UTF_8)).isEqualTo(JSON);
    }

    @Test
    void credentialsThatAreNeitherAreRejectedWithoutEchoingTheValue() {
        assertThatThrownBy(() -> FcmConfig.decodeCredentials("not base64!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not base64!");
    }
}
