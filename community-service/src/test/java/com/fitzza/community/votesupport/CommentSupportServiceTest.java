package com.fitzza.community.votesupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.community.domain.Comment;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.domain.VoteOptionSource;
import com.fitzza.community.repository.CommentRepository;
import com.fitzza.community.repository.PostRepository;
import com.fitzza.community.repository.VoteOptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CommentSupportServiceTest {

    private static final Long POST_ID = 10L;
    private static final String TITLE = "어떤 게 나아요?";

    private final CommentRepository commentRepository = mock(CommentRepository.class);
    private final PostRepository postRepository = mock(PostRepository.class);
    private final VoteOptionRepository voteOptionRepository = mock(VoteOptionRepository.class);
    private final VoteSupportJudge judge = mock(VoteSupportJudge.class);
    private final CommentSupportService service =
            new CommentSupportService(commentRepository, postRepository, voteOptionRepository, judge);

    private final List<VoteOption> options = List.of(option(100L, 1), option(101L, 2));

    @Test
    void savesTheJudgedOptionOnlyForTheVersionThatWasJudged() {
        Comment comment = comment(1L, "A가 나아요");
        ReflectionTestUtils.setField(comment, "version", 3L);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(votePost()));
        when(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).thenReturn(options);
        when(judge.judge(TITLE, "A가 나아요", options)).thenReturn(Optional.of(100L));

        service.judgeComment(1L);

        verify(commentRepository).updateSupport(1L, 3L, 100L);
    }

    @Test
    void anUnsureJudgementSavesNothing() {
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment(1L, "글쎄요")));
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(votePost()));
        when(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).thenReturn(options);
        when(judge.judge(any(), any(), any())).thenReturn(Optional.empty());

        service.judgeComment(1L);

        verify(commentRepository, never()).updateSupport(anyLong(), anyLong(), any());
    }

    @Test
    void commentsOnNormalPostsAndDeletedCommentsAreNotJudged() {
        Comment deleted = comment(2L, "A요");
        deleted.delete();
        when(commentRepository.findById(1L)).thenReturn(Optional.of(comment(1L, "예뻐요")));
        when(commentRepository.findById(2L)).thenReturn(Optional.of(deleted));
        Post normal = Post.createNormal(7L, "제목", "내용", PostCategory.DAILY, null);
        ReflectionTestUtils.setField(normal, "id", POST_ID);
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(normal));

        service.judgeComment(1L);
        service.judgeComment(2L);
        service.judgeComment(3L);

        verifyNoInteractions(judge);
    }

    @Test
    void replacedOptionsRejudgeEveryRemainingCommentOfThePost() {
        Comment first = comment(1L, "A요");
        Comment second = comment(2L, "B요");
        Comment deleted = comment(3L, "A!");
        deleted.delete();
        when(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).thenReturn(Optional.of(votePost()));
        when(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).thenReturn(options);
        when(commentRepository.findByPostIdOrderByCreatedAtAscIdAsc(POST_ID))
                .thenReturn(List.of(first, second, deleted));
        when(judge.judge(TITLE, "A요", options)).thenReturn(Optional.of(100L));
        when(judge.judge(TITLE, "B요", options)).thenReturn(Optional.of(101L));

        service.judgePost(POST_ID);

        verify(commentRepository).updateSupport(1L, 0L, 100L);
        verify(commentRepository).updateSupport(2L, 0L, 101L);
        verify(commentRepository, never()).updateSupport(eq(3L), anyLong(), any());
    }

    private static Post votePost() {
        Post post = Post.createVote(7L, TITLE, "골라 주세요", PostCategory.COORDI_QUESTION, Instant.now());
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }

    private static Comment comment(Long id, String content) {
        Comment comment = Comment.create(POST_ID, 8L, content);
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private static VoteOption option(Long id, int order) {
        VoteOption option = VoteOption.of(POST_ID, order, VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null,
                "https://cdn.example.com/" + id + ".png", null, null);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }
}
