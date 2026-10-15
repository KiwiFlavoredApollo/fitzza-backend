package com.fitzza.notification.dto;

import com.fitzza.notification.domain.DeviceToken;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "FCM 기기 토큰")
public record DeviceTokenRequest(
        @Schema(description = "FE가 Firebase에서 받은 기기 토큰", example = "fcm-device-token")
                @NotBlank(message = "기기 토큰이 필요합니다.")
                @Size(max = DeviceToken.TOKEN_MAX_LENGTH, message = "기기 토큰은 512자 이하여야 합니다.")
                String token) {
}
