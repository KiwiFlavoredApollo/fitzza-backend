package com.fitzza.user.controller;

import com.fitzza.user.config.OpenApiConfig;
import com.fitzza.user.dto.ErrorResponse;
import com.fitzza.user.dto.NicknameResponse;
import com.fitzza.user.security.ServiceApi;
import com.fitzza.user.security.ServiceCallers;
import com.fitzza.user.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 다른 서비스만 호출하는 API. /api로 시작하지 않아 게이트웨이 라우트가 없다.
@Tag(name = "서비스 전용 API", description = "서비스끼리만 호출한다. 호출하는 서비스의 X-Service-Token이 필요하고 "
        + "Gateway로는 호출할 수 없다.")
@SecurityRequirement(name = OpenApiConfig.SERVICE_TOKEN)
// 메서드에 허용 서비스를 적지 않으면 아무 서비스도 부를 수 없다. 새 API를 실수로 열어 두지 않기 위해서다.
@ServiceApi(callers = {})
@RestController
@RequestMapping("/users")
public class InternalUserController {

    private final ProfileService profileService;

    public InternalUserController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(
            summary = "닉네임 일괄 조회",
            description = "없는 사용자는 결과에서 빠진다. 한 번에 최대 100명까지 조회한다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: ID가 없거나 숫자가 아니거나 100개 초과",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: community-service 토큰이 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @ServiceApi(callers = ServiceCallers.COMMUNITY)
    @GetMapping
    public List<NicknameResponse> findNicknames(
            @Parameter(description = "사용자 ID 목록(쉼표 구분)", example = "7,8") @RequestParam("userIds")
                    List<Long> userIds) {
        return profileService.findNicknames(userIds);
    }

    @Operation(
            summary = "입력된 신체 정보 조회",
            description = "사용자가 입력한 항목만 내려간다. 입력하지 않은 항목은 키 자체가 없다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: recommendation-service 토큰이 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "USER_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @ServiceApi(callers = ServiceCallers.RECOMMENDATION)
    @GetMapping("/{userId}/body")
    public Map<String, Object> getFilledBodyFields(@PathVariable("userId") Long userId) {
        return profileService.getFilledBodyFields(userId);
    }
}
