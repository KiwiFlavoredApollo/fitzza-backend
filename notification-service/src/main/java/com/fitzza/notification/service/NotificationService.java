package com.fitzza.notification.service;

import com.fitzza.notification.domain.Notification;
import com.fitzza.notification.dto.NotificationEventRequest;
import com.fitzza.notification.dto.NotificationPageResponse;
import com.fitzza.notification.dto.NotificationResponse;
import com.fitzza.notification.exception.ErrorCode;
import com.fitzza.notification.exception.NotificationApiException;
import com.fitzza.notification.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    public static final int MAX_PAGE_SIZE = 100;
    // 같은 시각에 생긴 알림의 순서가 페이지마다 달라지지 않도록 ID를 보조 정렬로 둔다.
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    // 이미 받은 eventId면 아무것도 저장하지 않는다. 보내는 쪽은 실패했을 때 같은 eventId로 다시 보내면 된다.
    @Transactional
    public void receive(NotificationEventRequest event) {
        notificationRepository.insertIfAbsent(
                UUID.randomUUID().toString(),
                event.userId(),
                event.type().name(),
                event.content().trim(),
                trimOrEmpty(event.targetId()),
                event.eventId().trim());
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse getNotifications(Long userId, int page, int size) {
        Pageable pageable =
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), NEWEST_FIRST);
        Page<Notification> notifications = notificationRepository.findByUserId(userId, pageable);
        return NotificationPageResponse.of(
                notifications.getContent().stream().map(NotificationResponse::from).toList(), notifications);
    }

    // 이미 읽은 알림을 다시 읽음 처리해도 결과는 같다.
    @Transactional
    public void markRead(Long userId, String notificationId) {
        Notification notification = notificationRepository
                .findById(notificationId)
                .orElseThrow(() -> new NotificationApiException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!notification.belongsTo(userId)) {
            throw new NotificationApiException(ErrorCode.NOT_OWNER);
        }
        notification.markRead();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
