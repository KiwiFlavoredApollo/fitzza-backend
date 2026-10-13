package com.fitzza.user.controller;

import com.fitzza.user.config.OpenApiConfig;
import com.fitzza.user.dto.ErrorResponse;
import com.fitzza.user.dto.LoginRequest;
import com.fitzza.user.dto.LoginResponse;
import com.fitzza.user.dto.RefreshTokenRequest;
import com.fitzza.user.dto.TokenResponse;
import com.fitzza.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "로그인(signin), 토큰 재발급(refresh), 로그아웃(signout)")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    /**
     * 로그인 요청을 처리할 인증 서비스를 주입한다.
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 검증된 로그인 요청으로 인증하고 액세스 토큰과 사용자 정보를 반환한다.
     */
    @Operation(
            summary = "로그인",
            description = "Access Token(15분)과 Refresh Token(30일)을 발급한다. "
                    + "이후 요청은 Authorization: Bearer {accessToken} 헤더로 보낸다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 이메일 또는 비밀번호 누락",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "INVALID_CREDENTIALS: 이메일 또는 비밀번호가 틀림(원인은 구분하지 않음)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/signin")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(
            summary = "토큰 재발급",
            description = "Refresh Token으로 새 Access Token과 새 Refresh Token을 받는다. "
                    + "쓴 Refresh Token은 바로 폐기되어 다시 쓸 수 없다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "재발급 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: Refresh Token 누락",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "INVALID_REFRESH_TOKEN: 없거나 만료됐거나 이미 쓴 토큰. 다시 로그인해야 한다.",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/refresh")
    public TokenResponse reissue(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.reissue(request);
    }

    // Access Token이 이미 만료된 사용자도 로그아웃할 수 있도록 Refresh Token만 필수로 받는다.
    // Authorization 헤더에 Access Token이 같이 오면 그 토큰도 바로 쓸 수 없게 한다.
    @Operation(
            summary = "로그아웃",
            description = "본문의 Refresh Token을 폐기한다(필수). Authorization 헤더에 Access Token을 같이 보내면 "
                    + "그 토큰도 즉시 차단된다(선택). 이미 폐기된 토큰으로 다시 요청해도 200이다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그아웃 성공(응답 본문 없음)", content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: Refresh Token 누락",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/signout")
    public void logout(
            @Valid @RequestBody RefreshTokenRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorization) {
        authService.logout(request, bearerTokenOf(authorization));
    }

    // 게이트웨이가 블랙리스트를 조회할 때와 같은 문자열이 되도록 접두어만 떼고 앞뒤 공백을 지운다.
    // 인증 방식 이름(Bearer)은 대소문자를 구분하지 않는다. 게이트웨이도 같은 기준으로 토큰을 받는다.
    private static String bearerTokenOf(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
