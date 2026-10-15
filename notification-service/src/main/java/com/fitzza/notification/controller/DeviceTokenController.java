package com.fitzza.notification.controller;

import com.fitzza.notification.config.OpenApiConfig;
import com.fitzza.notification.dto.DeviceTokenRequest;
import com.fitzza.notification.dto.ErrorResponse;
import com.fitzza.notification.security.AuthHeaders;
import com.fitzza.notification.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 토큰은 기기를 가리키는 값이라 URL에 넣지 않고 본문으로 받는다(로그·기록에 남지 않게).
@Tag(name = "알림 기기 토큰", description = "푸시 알림(FCM)을 받을 기기 토큰 등록·해제. 모두 로그인이 필요하다.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/notifications/tokens")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @Operation(
            summary = "기기 토큰 등록",
            description = "로그인 후나 Firebase가 토큰을 새로 발급했을 때 호출한다. 같은 토큰을 다시 보내도 204다. "
                    + "다른 계정에 등록돼 있던 토큰이면 이 계정으로 옮긴다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "등록됨(응답 본문 없음)", content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 토큰이 비었거나 512자 초과",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @Valid @RequestBody DeviceTokenRequest request) {
        deviceTokenService.register(userId, request.token());
    }

    @Operation(
            summary = "기기 토큰 해제",
            description = "로그아웃 전에 호출해 이 기기로 푸시가 가지 않게 한다. 없는 토큰이어도 204다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "해제됨(응답 본문 없음)", content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 토큰이 비었거나 512자 초과",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @Valid @RequestBody DeviceTokenRequest request) {
        deviceTokenService.unregister(userId, request.token());
    }
}
