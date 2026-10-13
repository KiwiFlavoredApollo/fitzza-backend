package com.fitzza.notification.controller;

import com.fitzza.notification.config.OpenApiConfig;
import com.fitzza.notification.dto.ErrorResponse;
import com.fitzza.notification.dto.NotificationEventRequest;
import com.fitzza.notification.security.ServiceApi;
import com.fitzza.notification.security.ServiceCallers;
import com.fitzza.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// /api로 시작하지 않아 게이트웨이 라우트가 없다. 다른 서비스가 자기 X-Service-Token을 붙여 직접 호출한다.
@Tag(name = "서비스 전용 API", description = "서비스끼리만 호출한다. 호출하는 서비스의 X-Service-Token이 필요하고 "
        + "Gateway로는 호출할 수 없다.")
@SecurityRequirement(name = OpenApiConfig.SERVICE_TOKEN)
// 메서드에 허용 서비스를 적지 않으면 아무 서비스도 부를 수 없다. 새 API를 실수로 열어 두지 않기 위해서다.
@ServiceApi(callers = {})
@RestController
@RequestMapping("/notifications")
public class InternalNotificationController {

    private final NotificationService notificationService;

    public InternalNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(
            summary = "알림 이벤트 수신",
            description = "받은 이벤트를 userId의 알림으로 저장한다. eventId는 보내는 서비스가 이벤트마다 정하는 "
                    + "고유값이며, 같은 eventId로 다시 보내도 알림은 한 번만 생긴다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "접수됨(응답 본문 없음)", content = @Content),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_REQUEST: 필수 값 누락, 없는 알림 유형, 길이 초과",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "UNAUTHENTICATED: community·recommendation·vton 서비스 토큰이 아님",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @ServiceApi(callers = {ServiceCallers.COMMUNITY, ServiceCallers.RECOMMENDATION, ServiceCallers.VTON})
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@Valid @RequestBody NotificationEventRequest request) {
        notificationService.receive(request);
    }
}
