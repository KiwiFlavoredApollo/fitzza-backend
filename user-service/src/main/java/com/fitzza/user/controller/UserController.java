package com.fitzza.user.controller;

import com.fitzza.user.dto.AvailabilityResponse;
import com.fitzza.user.dto.ErrorResponse;
import com.fitzza.user.dto.SignupRequest;
import com.fitzza.user.dto.SignupResponse;
import com.fitzza.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "회원", description = "회원가입과 중복 확인. 토큰 없이 호출한다.")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * 가입 및 중복 확인 요청을 처리할 사용자 서비스를 주입한다.
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 검증된 가입 요청을 처리하고 생성된 사용자 ID를 HTTP 201 응답으로 반환한다.
     */
    @Operation(
            summary = "회원가입",
            description = "비밀번호는 영문과 숫자를 모두 포함한 8~24자(특수문자 불가), 닉네임은 한글·영문·숫자 2~10자.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "가입 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 형식이 맞지 않는 값",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "DUPLICATE_EMAIL / DUPLICATE_NICKNAME / DUPLICATE_ACCOUNT: 이미 사용 중",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signUp(@Valid @RequestBody SignupRequest request) {
        return new SignupResponse(userService.signUp(request));
    }

    /**
     * 이메일 형식을 검사한 뒤 정규화된 주소의 사용 가능 여부를 반환한다.
     */
    @Operation(summary = "이메일 중복 확인", description = "available이 true면 사용할 수 있는 이메일이다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "확인 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 이메일 형식이 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/check-email")
    public AvailabilityResponse checkEmail(
            @Parameter(description = "확인할 이메일", example = "user@example.com") @RequestParam("email")
                    String email) {
        return new AvailabilityResponse(userService.isEmailAvailable(email));
    }

    /**
     * 닉네임 형식을 검사한 뒤 사용 가능 여부를 반환한다.
     */
    @Operation(summary = "닉네임 중복 확인", description = "available이 true면 사용할 수 있는 닉네임이다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "확인 성공"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 닉네임 형식이 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/check-nickname")
    public AvailabilityResponse checkNickname(
            @Parameter(description = "확인할 닉네임", example = "핏짜유저") @RequestParam("nickname") String nickname) {
        return new AvailabilityResponse(userService.isNicknameAvailable(nickname));
    }
}
