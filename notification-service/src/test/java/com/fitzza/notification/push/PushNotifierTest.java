package com.fitzza.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.notification.domain.NotificationType;
import com.fitzza.notification.service.DeviceTokenService;
import com.fitzza.notification.service.NotificationSavedEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PushNotifierTest {

    private static final Long USER_ID = 7L;
    private static final NotificationSavedEvent COMMENT = new NotificationSavedEvent(
            "n-1", USER_ID, NotificationType.COMMENT, "새 댓글이 달렸습니다.", "10");

    private final DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);
    private final FcmSender fcmSender = mock(FcmSender.class);
    private final PushNotifier pushNotifier = new PushNotifier(deviceTokenService, fcmSender);

    @Test
    void sendsToEveryDeviceOfTheUserAndRemovesTokensFcmRejected() {
        when(deviceTokenService.findTokens(USER_ID)).thenReturn(List.of("token-a", "token-b"));
        when(fcmSender.send(any(), any())).thenReturn(Set.of("token-b"));

        pushNotifier.onNotificationSaved(COMMENT);

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(fcmSender).send(eq(List.of("token-a", "token-b")), message.capture());
        assertThat(message.getValue().title()).isEqualTo(PushNotifier.TITLE);
        assertThat(message.getValue().body()).isEqualTo("새 댓글이 달렸습니다.");
        assertThat(message.getValue().data())
                .isEqualTo(Map.of("notificationId", "n-1", "type", "COMMENT", "targetId", "10"));
        verify(deviceTokenService).removeTokens(Set.of("token-b"));
    }

    @Test
    void anEmptyTargetIsLeftOutOfTheData() {
        NotificationSavedEvent failed = new NotificationSavedEvent(
                "n-2", USER_ID, NotificationType.FITTING_FAILED, "피팅에 실패했습니다.", "");

        assertThat(PushNotifier.toMessage(failed).data())
                .isEqualTo(Map.of("notificationId", "n-2", "type", "FITTING_FAILED"));
    }

    @Test
    void doesNothingWhenTheUserHasNoDevices() {
        when(deviceTokenService.findTokens(USER_ID)).thenReturn(List.of());

        pushNotifier.onNotificationSaved(COMMENT);

        verifyNoInteractions(fcmSender);
        verify(deviceTokenService, never()).removeTokens(any());
    }

    @Test
    void aFailedSendIsSwallowedAndRemovesNothing() {
        when(deviceTokenService.findTokens(USER_ID)).thenReturn(List.of("token-a"));
        when(fcmSender.send(any(), any())).thenThrow(new IllegalStateException("FCM down"));

        assertThatCode(() -> pushNotifier.onNotificationSaved(COMMENT)).doesNotThrowAnyException();

        verify(deviceTokenService, never()).removeTokens(any());
    }

    @Test
    void aFailedTokenLookupIsSwallowed() {
        when(deviceTokenService.findTokens(USER_ID)).thenThrow(new IllegalStateException("DB down"));

        assertThatCode(() -> pushNotifier.onNotificationSaved(COMMENT)).doesNotThrowAnyException();

        verifyNoInteractions(fcmSender);
    }
}
