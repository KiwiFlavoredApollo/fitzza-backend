package com.fitzza.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fitzza.user.config.OpenApiConfig;
import com.fitzza.user.dto.ErrorResponse;
import com.fitzza.user.dto.MyProfileResponse;
import com.fitzza.user.dto.UserBodyResponse;
import com.fitzza.user.security.AuthHeaders;
import com.fitzza.user.service.ProfileService;
import com.fitzza.user.service.UserBodyUpdateParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "프로필", description = "내 프로필 조회와 저장")
@RestController
@RequestMapping("/api/users")
public class ProfileController {

    private final ProfileService profileService;
    private final UserBodyUpdateParser userBodyUpdateParser;

    public ProfileController(ProfileService profileService, UserBodyUpdateParser userBodyUpdateParser) {
        this.profileService = profileService;
        this.userBodyUpdateParser = userBodyUpdateParser;
    }

    @Operation(
            summary = "내 프로필 조회",
            description = "계정 정보와 신체·취향 정보를 한 단계로 펼쳐서 내려준다. 입력하지 않은 항목은 null이고, "
                    + "preferredStyles만 입력하지 않으면 빈 배열([])이다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(
                responseCode = "401",
                description = "토큰 없음·만료·위조",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "USER_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public MyProfileResponse getMyProfile(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId) {
        return profileService.getMyProfile(userId);
    }

    @Operation(
            summary = "내 신체 정보 저장·수정·삭제",
            description = "모든 필드가 선택이다. 보내지 않은 필드는 그대로 두고, null로 보낸 필드는 지운다. "
                    + "성별 M·F, 키 100~250cm, 체중 20~300kg, 신발 150~350mm. "
                    + "핏·스타일은 정해진 code 값(예: OVERSIZED, CASUAL).",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = UserBodyResponse.class))))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정된 신체 정보"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 범위를 벗어난 값이나 없는 코드",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "토큰 없음·만료·위조",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "USER_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/me")
    public UserBodyResponse updateMyBody(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @RequestBody JsonNode body) {
        return profileService.updateMyBody(userId, userBodyUpdateParser.parse(body));
    }
}
