package com.fitzza.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitzza.notification.domain.Notification;
import com.fitzza.notification.domain.NotificationType;
import com.fitzza.notification.support.PostgresModeJpaTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@PostgresModeJpaTest
class NotificationRepositoryTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_ID = 8L;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void theSameEventIsStoredOnlyOnce() {
        int first = insert(USER_ID, NotificationType.COMMENT, "10", "comment-55");
        int second = insert(USER_ID, NotificationType.COMMENT, "10", "comment-55");

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(notificationsOf(USER_ID)).hasSize(1);
    }

    @Test
    void storedEventIsReadBackWithItsFieldsAndStartsUnread() {
        insert(USER_ID, NotificationType.VOTE_CLOSED, "10", "vote-10-closed");

        Notification notification = notificationsOf(USER_ID).get(0);

        assertThat(notification.getId()).hasSize(36);
        assertThat(notification.getUserId()).isEqualTo(USER_ID);
        assertThat(notification.getType()).isEqualTo(NotificationType.VOTE_CLOSED);
        assertThat(notification.getContent()).isEqualTo("알림 내용");
        assertThat(notification.getTargetId()).isEqualTo("10");
        assertThat(notification.getEventId()).isEqualTo("vote-10-closed");
        assertThat(notification.isRead()).isFalse();
        assertThat(notification.getCreatedAt()).isNotNull();
    }

    @Test
    void anEmptyTargetIsStoredAsNull() {
        insert(USER_ID, NotificationType.FITTING_FAILED, "", "fitting-3-failed");

        assertThat(notificationsOf(USER_ID).get(0).getTargetId()).isNull();
    }

    @Test
    void listAndUnreadCountOnlyCoverTheUsersOwnNotifications() {
        insert(USER_ID, NotificationType.COMMENT, "10", "comment-1");
        insert(USER_ID, NotificationType.COMMENT, "10", "comment-2");
        insert(USER_ID, NotificationType.FITTING_DONE, "3", "fitting-3-done");
        insert(OTHER_ID, NotificationType.COMMENT, "11", "comment-3");

        Page<Notification> firstPage = notificationRepository.findByUserId(USER_ID, PageRequest.of(0, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(notificationRepository.countByUserIdAndReadFalse(USER_ID)).isEqualTo(3);
        assertThat(notificationRepository.countByUserIdAndReadFalse(OTHER_ID)).isEqualTo(1);
    }

    @Test
    void markingANotificationReadLowersTheUnreadCount() {
        insert(USER_ID, NotificationType.COMMENT, "10", "comment-1");
        insert(USER_ID, NotificationType.COMMENT, "10", "comment-2");

        notificationsOf(USER_ID).get(0).markRead();
        notificationRepository.flush();

        assertThat(notificationRepository.countByUserIdAndReadFalse(USER_ID)).isEqualTo(1);
    }

    private int insert(Long userId, NotificationType type, String targetId, String eventId) {
        return notificationRepository.insertIfAbsent(
                UUID.randomUUID().toString(), userId, type.name(), "알림 내용", targetId, eventId);
    }

    private List<Notification> notificationsOf(Long userId) {
        return notificationRepository.findByUserId(userId, PageRequest.of(0, 20)).getContent();
    }
}
