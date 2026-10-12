package com.fitzza.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.notification.VoteClosedEvent;
import com.fitzza.community.repository.PostRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class VoteClosingServiceTest {

    private static final Long AUTHOR_ID = 7L;
    private static final Long POST_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final PostRepository postRepository = mock(PostRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final VoteClosingService service = new VoteClosingService(postRepository, eventPublisher);

    @Test
    void votePastItsDeadlineIsClosedAndTheAuthorIsNotified() {
        Post post = votePostCreatedAt(NOW.minus(Duration.ofHours(7)));
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(post));

        assertThat(service.closeIfDue(POST_ID, NOW)).isTrue();

        assertThat(post.isVoteClosed()).isTrue();
        verify(eventPublisher).publishEvent(new VoteClosedEvent(POST_ID, AUTHOR_ID, "둘 중 뭐가 나아요"));
    }

    @Test
    void voteTheAuthorAlreadyClosedIsLeftAloneWithoutANotification() {
        Post post = votePostCreatedAt(NOW.minus(Duration.ofHours(7)));
        post.closeVote();
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(post));

        assertThat(service.closeIfDue(POST_ID, NOW)).isFalse();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void voteStillOpenIsNotClosed() {
        Post post = votePostCreatedAt(NOW.minus(Duration.ofHours(1)));
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(post));

        assertThat(service.closeIfDue(POST_ID, NOW)).isFalse();

        assertThat(post.isVoteClosed()).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deletedPostIsSkipped() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.empty());

        assertThat(service.closeIfDue(POST_ID, NOW)).isFalse();

        verify(eventPublisher, never()).publishEvent(any());
    }

    private static Post votePostCreatedAt(Instant createdAt) {
        Post post = Post.createVote(AUTHOR_ID, "둘 중 뭐가 나아요", "골라주세요", PostCategory.FASHION, createdAt);
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }
}
