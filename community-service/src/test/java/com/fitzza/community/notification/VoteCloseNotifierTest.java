package com.fitzza.community.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.repository.PostRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class VoteCloseNotifierTest {

    private static final Long AUTHOR_ID = 7L;
    private static final Long POST_ID = 10L;

    private final PostRepository postRepository = mock(PostRepository.class);
    private final NotificationSender notificationSender = mock(NotificationSender.class);
    private final VoteCloseNotifier notifier = new VoteCloseNotifier(postRepository, notificationSender);

    @Test
    void sendsTheVoteClosedNotificationToTheAuthorAndClearsTheWaitingMark() {
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(closedByServer()));
        when(notificationSender.send(any())).thenReturn(true);

        assertThat(notifier.notifyAuthor(POST_ID)).isTrue();

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationSender).send(captor.capture());
        NotificationEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("vote-closed-10");
        assertThat(event.type()).isEqualTo("VOTE_CLOSED");
        assertThat(event.userId()).isEqualTo(AUTHOR_ID);
        assertThat(event.targetId()).isEqualTo("10");
        assertThat(event.content()).isEqualTo("내 투표가 마감됐어요: 둘 중 뭐가 나아요");
        verify(postRepository).markVoteCloseNotified(POST_ID);
    }

    // 알림 서비스가 꺼져 있으면 대기 표시를 남겨 다음 실행에서 다시 보낸다.
    @Test
    void keepsTheWaitingMarkWhenTheNotificationCouldNotBeDelivered() {
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(closedByServer()));
        when(notificationSender.send(any())).thenReturn(false);

        assertThat(notifier.notifyAuthor(POST_ID)).isFalse();

        verify(postRepository, never()).markVoteCloseNotified(anyLong());
    }

    @Test
    void doesNothingForAVoteThatIsNotWaitingOrWasDeleted() {
        Post closedEarly = closedByServer();
        ReflectionTestUtils.setField(closedEarly, "voteCloseNotificationPending", false);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(closedEarly));
        when(postRepository.findByIdAndDeletedAtIsNull(11L)).thenReturn(Optional.empty());

        assertThat(notifier.notifyAuthor(POST_ID)).isFalse();
        assertThat(notifier.notifyAuthor(11L)).isFalse();

        verify(notificationSender, never()).send(any());
    }

    private static Post closedByServer() {
        Post post = Post.createVote(
                AUTHOR_ID, "둘 중 뭐가 나아요", "골라주세요", PostCategory.FASHION, Instant.now().minus(Duration.ofHours(7)));
        ReflectionTestUtils.setField(post, "id", POST_ID);
        post.closeVoteAtDeadline();
        return post;
    }
}
