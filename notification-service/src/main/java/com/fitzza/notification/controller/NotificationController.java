package com.fitzza.notification.controller;

import com.fitzza.notification.config.OpenApiConfig;
import com.fitzza.notification.dto.ErrorResponse;
import com.fitzza.notification.dto.NotificationPageResponse;
import com.fitzza.notification.security.AuthHeaders;
import com.fitzza.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "내 알림 목록과 읽음 처리. 모두 로그인이 필요하다.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(
            summary = "알림 목록",
            description = "최신순. targetId는 댓글·투표 알림이면 게시글 ID, 피팅 알림이면 피팅 작업 ID다. "
                    + "page는 0 이상, size는 1~100이며 벗어나면 400이다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공(알림이 없으면 빈 목록)"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 숫자가 아니거나 범위를 벗어난 page·size",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public NotificationPageResponse getNotifications(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @Parameter(description = "페이지 번호(0부터)") @RequestParam(name = "page", defaultValue = "0") @Min(0)
                    int page,
            @Parameter(description = "페이지 크기(1~100)") @RequestParam(name = "size", defaultValue = "20")
                    @Min(1) @Max(NotificationService.MAX_PAGE_SIZE) int size) {
        return notificationService.getNotifications(userId, page, size);
    }

    @Operation(summary = "알림 읽음 처리", description = "이미 읽은 알림에 다시 요청해도 200이다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "읽음 처리됨(응답 본문 없음)", content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: 로그인 필요",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "NOT_OWNER: 다른 사람의 알림",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "NOTIFICATION_NOT_FOUND",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{notificationId}")
    public void markRead(
            @Parameter(hidden = true) @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("notificationId") String notificationId) {
        notificationService.markRead(userId, notificationId);
    }
}
