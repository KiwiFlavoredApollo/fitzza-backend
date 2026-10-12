package com.fitzza.community.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VoteClosedNotificationListenerTest {

    private final NotificationSender notificationSender = mock(NotificationSender.class);
    private final VoteClosedNotificationListener listener = new VoteClosedNotificationListener(notificationSender);

    @Test
    void sendsAVoteClosedNotificationToThePostAuthorKeyedByThePost() {
        listener.onVoteClosed(new VoteClosedEvent(10L, 7L, "둘 중 뭐가 나아요"));

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationSender).send(captor.capture());
        NotificationEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("vote-closed-10");
        assertThat(event.type()).isEqualTo("VOTE_CLOSED");
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.targetId()).isEqualTo("10");
        assertThat(event.content()).isEqualTo("내 투표가 마감됐어요: 둘 중 뭐가 나아요");
    }
}
