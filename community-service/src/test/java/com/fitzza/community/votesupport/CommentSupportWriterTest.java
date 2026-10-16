package com.fitzza.community.votesupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.repository.CommentRepository;
import com.fitzza.community.repository.PostRepository;
import com.fitzza.community.repository.VoteOptionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CommentSupportWriterTest {

    private static final Long POST_ID = 10L;

    private final PostRepository postRepository = mock(PostRepository.class);
    private final VoteOptionRepository voteOptionRepository = mock(VoteOptionRepository.class);
    private final CommentRepository commentRepository = mock(CommentRepository.class);
    private final CommentSupportWriter writer =
            new CommentSupportWriter(postRepository, voteOptionRepository, commentRepository);

    @Test
    void locksThePostChecksTheOptionStillBelongsToItThenSaves() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(votePost()));
        when(voteOptionRepository.existsByIdAndPostId(100L, POST_ID)).thenReturn(true);
        when(commentRepository.updateSupport(1L, 2L, 100L)).thenReturn(1);

        assertThat(writer.save(POST_ID, 1L, 2L, 100L)).isTrue();

        InOrder order = inOrder(postRepository, voteOptionRepository, commentRepository);
        order.verify(postRepository).findVisibleForUpdate(POST_ID);
        order.verify(voteOptionRepository).existsByIdAndPostId(100L, POST_ID);
        order.verify(commentRepository).updateSupport(1L, 2L, 100L);
    }

    // 판정하는 동안 선택지가 바뀌어 판정한 선택지가 지워졌으면 저장하지 않는다.
    @Test
    void aJudgementForAReplacedOptionIsDropped() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.of(votePost()));
        when(voteOptionRepository.existsByIdAndPostId(100L, POST_ID)).thenReturn(false);

        assertThat(writer.save(POST_ID, 1L, 2L, 100L)).isFalse();
        verify(commentRepository, never()).updateSupport(anyLong(), anyLong(), any());
    }

    @Test
    void aJudgementForADeletedPostIsDropped() {
        when(postRepository.findVisibleForUpdate(POST_ID)).thenReturn(Optional.empty());

        assertThat(writer.save(POST_ID, 1L, 2L, 100L)).isFalse();
        verify(commentRepository, never()).updateSupport(anyLong(), anyLong(), any());
    }

    private static Post votePost() {
        return Post.createVote(7L, "제목", "내용", PostCategory.COORDI_QUESTION, Instant.now());
    }
}
