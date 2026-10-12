package com.fitzza.notification.controller;

import com.fitzza.notification.dto.NotificationEventRequest;
import com.fitzza.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 게이트웨이에는 라우트가 없다. 다른 서비스가 X-Internal-Token을 붙여 직접 호출한다.
@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationController {

    private final NotificationService notificationService;

    public InternalNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@Valid @RequestBody NotificationEventRequest request) {
        notificationService.receive(request);
    }
}
