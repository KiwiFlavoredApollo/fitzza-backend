package com.fitzza.notification.controller;

import com.fitzza.notification.dto.NotificationPageResponse;
import com.fitzza.notification.dto.UnreadCountResponse;
import com.fitzza.notification.security.AuthHeaders;
import com.fitzza.notification.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public NotificationPageResponse getNotifications(
            @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return notificationService.getNotifications(userId, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse countUnread(@RequestHeader(AuthHeaders.USER_ID) Long userId) {
        return notificationService.countUnread(userId);
    }

    @PatchMapping("/{notificationId}/read")
    public void markRead(
            @RequestHeader(AuthHeaders.USER_ID) Long userId,
            @PathVariable("notificationId") String notificationId) {
        notificationService.markRead(userId, notificationId);
    }
}
