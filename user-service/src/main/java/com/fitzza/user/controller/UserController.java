package com.fitzza.user.controller;

import com.fitzza.user.dto.ErrorResponse;
import com.fitzza.user.dto.SignupConflictResponse;
import com.fitzza.user.dto.SignupRequest;
import com.fitzza.user.dto.SignupResponse;
import com.fitzza.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "회원", description = "회원가입. 토큰 없이 호출한다.")
@RestController
// 회원가입은 로그인·재발급·로그아웃과 같은 /api/auth 아래에 둔다(이슈 #36).
@RequestMapping("/api/auth")
public class UserController {

    private final UserService userService;

    /**
     * 가입 요청을 처리할 사용자 서비스를 주입한다.
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
                description = "DUPLICATE_VALUE: 이메일·닉네임 중복. errors에 중복된 필드를 모두 담는다.",
                content = @Content(schema = @Schema(implementation = SignupConflictResponse.class)))
    })
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signUp(@Valid @RequestBody SignupRequest request) {
        return new SignupResponse(userService.signUp(request));
    }
}
