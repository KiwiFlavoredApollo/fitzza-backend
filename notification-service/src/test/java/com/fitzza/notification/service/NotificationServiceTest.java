package com.fitzza.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitzza.notification.domain.Notification;
import com.fitzza.notification.domain.NotificationType;
import com.fitzza.notification.dto.NotificationEventRequest;
import com.fitzza.notification.dto.NotificationPageResponse;
import com.fitzza.notification.exception.ErrorCode;
import com.fitzza.notification.exception.NotificationApiException;
import com.fitzza.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class NotificationServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final String NOTIFICATION_ID = "0b0f6f0e-6f0c-4d51-9c35-6f5d2f1f7a11";

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationService notificationService = new NotificationService(notificationRepository);

    @Test
    void receiveStoresTheEventUnderANewIdWithTrimmedValues() {
        notificationService.receive(new NotificationEventRequest(
                " comment-55 ", NotificationType.COMMENT, USER_ID, " 10 ", " 새 댓글이 달렸습니다. "));

        ArgumentCaptor<String> logId = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository)
                .insertIfAbsent(
                        logId.capture(),
                        eq(USER_ID),
                        eq("COMMENT"),
                        eq("새 댓글이 달렸습니다."),
                        eq("10"),
                        eq("comment-55"));
        assertThat(UUID.fromString(logId.getValue()).toString()).isEqualTo(logId.getValue());
    }

    @Test
    void receivePassesAnEmptyTargetWhenTheEventHasNone() {
        notificationService.receive(new NotificationEventRequest(
                "fitting-3-failed", NotificationType.FITTING_FAILED, USER_ID, null, "피팅에 실패했습니다."));
        notificationService.receive(new NotificationEventRequest(
                "fitting-4-failed", NotificationType.FITTING_FAILED, USER_ID, " ", "피팅에 실패했습니다."));

        verify(notificationRepository)
                .insertIfAbsent(
                        any(String.class),
                        eq(USER_ID),
                        eq("FITTING_FAILED"),
                        eq("피팅에 실패했습니다."),
                        eq(""),
                        eq("fitting-3-failed"));
        verify(notificationRepository)
                .insertIfAbsent(
                        any(String.class),
                        eq(USER_ID),
                        eq("FITTING_FAILED"),
                        eq("피팅에 실패했습니다."),
                        eq(""),
                        eq("fitting-4-failed"));
    }

    @Test
    void listReturnsTheUsersNotificationsInTheOrderTheRepositoryGaveThem() {
        Notification newer = notification("n-2", NotificationType.VOTE_CLOSED, "투표가 마감됐습니다.", "10", false);
        Notification older = notification("n-1", NotificationType.COMMENT, "새 댓글이 달렸습니다.", "10", true);
        when(notificationRepository.findByUserId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(0, 20), 2));

        NotificationPageResponse page = notificationService.getNotifications(USER_ID, 0, 20);

        assertThat(page.page()).isZero();
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).notificationId()).isEqualTo("n-2");
        assertThat(page.content().get(0).type()).isEqualTo(NotificationType.VOTE_CLOSED);
        assertThat(page.content().get(0).content()).isEqualTo("투표가 마감됐습니다.");
        assertThat(page.content().get(0).targetId()).isEqualTo("10");
        assertThat(page.content().get(0).read()).isFalse();
        assertThat(page.content().get(1).notificationId()).isEqualTo("n-1");
        assertThat(page.content().get(1).read()).isTrue();
    }

    @Test
    void listClampsPagingAndAsksForNewestFirst() {
        when(notificationRepository.findByUserId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(Page.<Notification>empty());

        NotificationPageResponse page = notificationService.getNotifications(USER_ID, -3, 500);

        assertThat(page.content()).isEmpty();
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByUserId(eq(USER_ID), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(NotificationService.MAX_PAGE_SIZE);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void unreadCountComesFromTheUsersUnreadNotifications() {
        when(notificationRepository.countByUserIdAndReadFalse(USER_ID)).thenReturn(3L);

        assertThat(notificationService.countUnread(USER_ID).count()).isEqualTo(3L);
    }

    @Test
    void markReadMarksTheOwnersNotification() {
        Notification notification = mock(Notification.class);
        when(notification.belongsTo(USER_ID)).thenReturn(true);
        when(notificationRepository.findById(NOTIFICATION_ID)).thenReturn(Optional.of(notification));

        notificationService.markRead(USER_ID, NOTIFICATION_ID);

        verify(notification).markRead();
    }

    @Test
    void markReadRejectsSomeoneElsesNotification() {
        Notification notification = mock(Notification.class);
        when(notification.belongsTo(OTHER_ID)).thenReturn(false);
        when(notificationRepository.findById(NOTIFICATION_ID)).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markRead(OTHER_ID, NOTIFICATION_ID))
                .isInstanceOf(NotificationApiException.class)
                .extracting(exception -> ((NotificationApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOT_OWNER);
        verify(notification, never()).markRead();
    }

    @Test
    void markReadFailsWhenTheNotificationDoesNotExist() {
        when(notificationRepository.findById(NOTIFICATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(USER_ID, NOTIFICATION_ID))
                .isInstanceOf(NotificationApiException.class)
                .extracting(exception -> ((NotificationApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    private static Notification notification(
            String id, NotificationType type, String content, String targetId, boolean read) {
        Notification notification = mock(Notification.class);
        when(notification.getId()).thenReturn(id);
        when(notification.getType()).thenReturn(type);
        when(notification.getContent()).thenReturn(content);
        when(notification.getTargetId()).thenReturn(targetId);
        when(notification.isRead()).thenReturn(read);
        when(notification.getCreatedAt()).thenReturn(Instant.parse("2026-10-12T00:00:00Z"));
        return notification;
    }
}
