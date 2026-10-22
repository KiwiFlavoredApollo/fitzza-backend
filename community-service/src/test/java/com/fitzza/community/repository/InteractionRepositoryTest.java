package com.fitzza.community.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitzza.community.domain.Comment;
import com.fitzza.community.support.PostgresModeJpaTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// 좋아요와 투표는 "한 사람당 한 번"을 DB의 충돌 무시 삽입에 맡기므로 실제 SQL로 확인한다.
@PostgresModeJpaTest
class InteractionRepositoryTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final Long POST_ID = 10L;
    private static final Long OTHER_POST_ID = 11L;
    private static final Long OPTION_A = 100L;
    private static final Long OPTION_B = 101L;

    @Autowired
    private PostLikeRepository postLikeRepository;

    @Autowired
    private CommentLikeRepository commentLikeRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Test
    void likingAPostTwiceKeepsOneLikeAndUnlikingTwiceIsHarmless() {
        assertThat(postLikeRepository.insertIfAbsent(POST_ID, USER_ID)).isEqualTo(1);
        assertThat(postLikeRepository.insertIfAbsent(POST_ID, USER_ID)).isZero();
        assertThat(postLikeRepository.insertIfAbsent(POST_ID, OTHER_ID)).isEqualTo(1);
        assertThat(postLikeRepository.countByPostId(POST_ID)).isEqualTo(2);

        assertThat(postLikeRepository.deleteLike(POST_ID, USER_ID)).isEqualTo(1);
        assertThat(postLikeRepository.deleteLike(POST_ID, USER_ID)).isZero();
        assertThat(postLikeRepository.countByPostId(POST_ID)).isEqualTo(1);
    }

    @Test
    void postLikeCountsAreGroupedPerPost() {
        postLikeRepository.insertIfAbsent(POST_ID, USER_ID);
        postLikeRepository.insertIfAbsent(POST_ID, OTHER_ID);
        postLikeRepository.insertIfAbsent(OTHER_POST_ID, USER_ID);

        Map<Long, Long> counts = IdCount.toMap(postLikeRepository.countByPostIds(List.of(POST_ID, OTHER_POST_ID, 99L)));

        assertThat(counts).containsOnly(Map.entry(POST_ID, 2L), Map.entry(OTHER_POST_ID, 1L));
    }

    @Test
    void likingACommentTwiceKeepsOneLikeAndTellsWhichCommentsTheUserLiked() {
        Long first = commentRepository.save(Comment.create(POST_ID, OTHER_ID, "첫 댓글")).getId();
        Long second = commentRepository.save(Comment.create(POST_ID, OTHER_ID, "둘째 댓글")).getId();

        assertThat(commentLikeRepository.insertIfAbsent(first, USER_ID)).isEqualTo(1);
        assertThat(commentLikeRepository.insertIfAbsent(first, USER_ID)).isZero();
        assertThat(commentLikeRepository.insertIfAbsent(first, OTHER_ID)).isEqualTo(1);

        assertThat(commentLikeRepository.countByCommentId(first)).isEqualTo(2);
        assertThat(commentLikeRepository.countByCommentId(second)).isZero();
        assertThat(IdCount.toMap(commentLikeRepository.countByCommentIds(List.of(first, second))))
                .containsOnly(Map.entry(first, 2L));
        assertThat(commentLikeRepository.findLikedCommentIds(USER_ID, List.of(first, second)))
                .containsExactly(first);

        assertThat(commentLikeRepository.deleteLike(first, USER_ID)).isEqualTo(1);
        assertThat(commentLikeRepository.deleteLike(first, USER_ID)).isZero();
        assertThat(commentLikeRepository.countByCommentId(first)).isEqualTo(1);
    }

    @Test
    void commentCountsLeaveOutDeletedComments() {
        commentRepository.save(Comment.create(POST_ID, USER_ID, "남은 댓글"));
        Comment deleted = commentRepository.save(Comment.create(POST_ID, USER_ID, "지운 댓글"));
        deleted.delete();
        commentRepository.save(Comment.create(OTHER_POST_ID, OTHER_ID, "다른 글 댓글"));
        commentRepository.flush();

        assertThat(commentRepository.countByPostIdAndDeletedFalse(POST_ID)).isEqualTo(1);
        assertThat(IdCount.toMap(commentRepository.countActiveByPostIds(List.of(POST_ID, OTHER_POST_ID))))
                .containsOnly(Map.entry(POST_ID, 1L), Map.entry(OTHER_POST_ID, 1L));
    }

    @Test
    void aSecondVoteFromTheSameUserIsIgnoredAndTheFirstChoiceStays() {
        assertThat(voteRepository.insertIfAbsent(newVoteId(), POST_ID, USER_ID, OPTION_A)).isEqualTo(1);
        assertThat(voteRepository.insertIfAbsent(newVoteId(), POST_ID, USER_ID, OPTION_B)).isZero();

        assertThat(voteRepository.findByPostIdAndUserId(POST_ID, USER_ID).orElseThrow().getVoteOptionId())
                .isEqualTo(OPTION_A);
        assertThat(voteRepository.findByPostIdAndUserId(POST_ID, OTHER_ID)).isEmpty();
    }

    @Test
    void votesAreCountedPerOptionWithinOnePost() {
        voteRepository.insertIfAbsent(newVoteId(), POST_ID, USER_ID, OPTION_A);
        voteRepository.insertIfAbsent(newVoteId(), POST_ID, OTHER_ID, OPTION_B);
        voteRepository.insertIfAbsent(newVoteId(), POST_ID, 9L, OPTION_A);
        voteRepository.insertIfAbsent(newVoteId(), OTHER_POST_ID, USER_ID, 200L);

        assertThat(IdCount.toMap(voteRepository.countByOption(POST_ID)))
                .containsOnly(Map.entry(OPTION_A, 2L), Map.entry(OPTION_B, 1L));
    }

    private static String newVoteId() {
        return UUID.randomUUID().toString();
    }

    @Test
    void aJudgementIsSavedOnlyForTheJudgedVersionAndCountedPerOption() {
        Comment first = commentRepository.saveAndFlush(Comment.create(POST_ID, USER_ID, "A가 나아요"));
        Comment second = commentRepository.saveAndFlush(Comment.create(POST_ID, OTHER_ID, "저도 A"));
        Comment third = commentRepository.saveAndFlush(Comment.create(POST_ID, OTHER_ID, "B요"));
        Comment deleted = commentRepository.saveAndFlush(Comment.create(POST_ID, OTHER_ID, "A!"));
        Comment elsewhere = commentRepository.saveAndFlush(Comment.create(OTHER_POST_ID, USER_ID, "A"));

        assertThat(commentRepository.updateSupport(first.getId(), first.getVersion(), OPTION_A)).isEqualTo(1);
        assertThat(commentRepository.updateSupport(second.getId(), second.getVersion(), OPTION_A)).isEqualTo(1);
        assertThat(commentRepository.updateSupport(third.getId(), third.getVersion() + 1, OPTION_B)).isZero();
        assertThat(commentRepository.updateSupport(elsewhere.getId(), elsewhere.getVersion(), OPTION_A)).isEqualTo(1);
        commentRepository.updateSupport(deleted.getId(), deleted.getVersion(), OPTION_A);
        commentRepository.findById(deleted.getId()).orElseThrow().delete();
        commentRepository.flush();

        assertThat(IdCount.toMap(commentRepository.countSupportByOption(POST_ID)))
                .containsOnly(Map.entry(OPTION_A, 2L));

        assertThat(commentRepository.clearSupportByPostId(POST_ID)).isEqualTo(4);
        assertThat(commentRepository.countSupportByOption(POST_ID)).isEmpty();
        assertThat(commentRepository.countSupportByOption(OTHER_POST_ID)).hasSize(1);
    }
}
