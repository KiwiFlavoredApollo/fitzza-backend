package com.fitzza.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.PostType;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import com.fitzza.community.support.PostgresModeJpaTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

@PostgresModeJpaTest
class PostRepositoryTest {

    private static final Long AUTHOR_ID = 7L;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void feedLeavesOutDeletedPostsAndFiltersByCategoryAndType() {
        save(Post.createNormal(AUTHOR_ID, "셔츠 추천", "린넨 셔츠 어때요", PostCategory.FASHION, null));
        save(Post.createNormal(AUTHOR_ID, "오늘 날씨", "산책했어요", PostCategory.DAILY, null));
        save(Post.createVote(AUTHOR_ID, "둘 중 뭐가 나아요", "골라주세요", PostCategory.FASHION, Instant.now()));
        Post deleted = save(Post.createNormal(AUTHOR_ID, "지운 글", "지웠어요", PostCategory.FASHION, null));
        deleted.delete();
        entityManager.flush();

        assertThat(feedTitles(null, null, null)).containsExactlyInAnyOrder("셔츠 추천", "오늘 날씨", "둘 중 뭐가 나아요");
        assertThat(feedTitles(PostCategory.FASHION, null, null)).containsExactlyInAnyOrder("셔츠 추천", "둘 중 뭐가 나아요");
        assertThat(feedTitles(null, PostType.VOTE, null)).containsExactly("둘 중 뭐가 나아요");
        assertThat(feedTitles(PostCategory.DAILY, PostType.VOTE, null)).isEmpty();
    }

    @Test
    void keywordMatchesTitleOrContentWithoutRegardToCase() {
        save(Post.createNormal(AUTHOR_ID, "Linen Shirt", "여름에 좋아요", PostCategory.FASHION, null));
        save(Post.createNormal(AUTHOR_ID, "여름 코디", "linen 바지도 샀어요", PostCategory.FASHION, null));
        save(Post.createNormal(AUTHOR_ID, "겨울 코디", "패딩 추천", PostCategory.FASHION, null));

        assertThat(feedTitles(null, null, "LINEN")).containsExactlyInAnyOrder("Linen Shirt", "여름 코디");
        assertThat(feedTitles(null, null, "패딩")).containsExactly("겨울 코디");
        assertThat(feedTitles(null, null, "없는말")).isEmpty();
    }

    @Test
    void percentAndUnderscoreInTheKeywordAreNotWildcards() {
        save(Post.createNormal(AUTHOR_ID, "면 100% 티셔츠", "좋아요", PostCategory.FASHION, null));
        save(Post.createNormal(AUTHOR_ID, "snake_case 티셔츠", "웃겨요", PostCategory.FASHION, null));
        save(Post.createNormal(AUTHOR_ID, "평범한 글", "내용", PostCategory.FASHION, null));

        assertThat(feedTitles(null, null, "%")).containsExactly("면 100% 티셔츠");
        assertThat(feedTitles(null, null, "_")).containsExactly("snake_case 티셔츠");
    }

    @Test
    void viewCountGoesUpByOneAndDeletedPostsAreNotCounted() {
        Post post = save(Post.createNormal(AUTHOR_ID, "제목", "내용", PostCategory.DAILY, null));
        Post deleted = save(Post.createNormal(AUTHOR_ID, "지운 글", "내용", PostCategory.DAILY, null));
        deleted.delete();
        entityManager.flush();

        assertThat(postRepository.incrementViewCount(post.getId())).isEqualTo(1);
        assertThat(postRepository.incrementViewCount(post.getId())).isEqualTo(1);
        assertThat(postRepository.incrementViewCount(deleted.getId())).isZero();

        assertThat(postRepository.findById(post.getId()).orElseThrow().getViewCount()).isEqualTo(2);
        assertThat(postRepository.findById(deleted.getId()).orElseThrow().getViewCount()).isZero();
    }

    @Test
    void deletedPostsAreInvisibleToTheVisibleOnlyLookups() {
        Post post = save(Post.createNormal(AUTHOR_ID, "제목", "내용", PostCategory.DAILY, null));
        Post deleted = save(Post.createNormal(AUTHOR_ID, "지운 글", "내용", PostCategory.DAILY, null));
        deleted.delete();
        entityManager.flush();
        entityManager.clear();

        assertThat(postRepository.findByIdAndDeletedAtIsNull(post.getId())).isPresent();
        assertThat(postRepository.findByIdAndDeletedAtIsNull(deleted.getId())).isEmpty();
        assertThat(postRepository.existsByIdAndDeletedAtIsNull(deleted.getId())).isFalse();
        assertThat(postRepository.findVisibleForUpdate(post.getId())).isPresent();
        assertThat(postRepository.findVisibleForUpdate(deleted.getId())).isEmpty();
    }

    // 삭제된 글이 늦게 도착한 수정으로 되살아나지 않는지 확인한다.
    @Test
    void savingAStaleCopyFailsInsteadOfOverwritingANewerChange() {
        Post post = save(Post.createNormal(AUTHOR_ID, "제목", "내용", PostCategory.DAILY, null));
        entityManager.flush();
        entityManager.clear();

        Post stale = postRepository.findById(post.getId()).orElseThrow();
        entityManager.detach(stale);
        Post fresh = postRepository.findById(post.getId()).orElseThrow();
        fresh.delete();
        postRepository.saveAndFlush(fresh);

        stale.changeTitle("늦게 도착한 수정");

        assertThatThrownBy(() -> postRepository.saveAndFlush(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void onlyOpenUndeletedVotesPastTheirDeadlineAreDueToClose() {
        Instant now = Instant.now();
        Instant sevenHoursAgo = now.minus(Duration.ofHours(7));
        Post due = save(Post.createVote(AUTHOR_ID, "마감 지남", "골라주세요", PostCategory.FASHION, sevenHoursAgo));
        Post dueEarlier = save(Post.createVote(
                AUTHOR_ID, "더 먼저 마감", "골라주세요", PostCategory.FASHION, sevenHoursAgo.minus(Duration.ofHours(1))));
        save(Post.createVote(AUTHOR_ID, "아직 진행 중", "골라주세요", PostCategory.FASHION, now));
        Post closedEarly = save(Post.createVote(AUTHOR_ID, "조기 종료", "골라주세요", PostCategory.FASHION, sevenHoursAgo));
        closedEarly.closeVote();
        Post deleted = save(Post.createVote(AUTHOR_ID, "지운 투표", "골라주세요", PostCategory.FASHION, sevenHoursAgo));
        deleted.delete();
        save(Post.createNormal(AUTHOR_ID, "일반 글", "내용", PostCategory.DAILY, null));
        entityManager.flush();

        assertThat(postRepository.findVotePostIdsDueToClose(now, PageRequest.of(0, 100)))
                .containsExactly(dueEarlier.getId(), due.getId());
        assertThat(postRepository.findVotePostIdsDueToClose(now, PageRequest.of(0, 1)))
                .containsExactly(dueEarlier.getId());
    }

    @Test
    void onlyVotesClosedAtTheDeadlineWaitForTheAuthorNotificationUntilMarked() {
        Instant sevenHoursAgo = Instant.now().minus(Duration.ofHours(7));
        Post closedByServer = save(Post.createVote(AUTHOR_ID, "서버가 마감", "골라주세요", PostCategory.FASHION, sevenHoursAgo));
        closedByServer.closeVoteAtDeadline();
        Post closedEarly = save(Post.createVote(AUTHOR_ID, "조기 종료", "골라주세요", PostCategory.FASHION, sevenHoursAgo));
        closedEarly.closeVote();
        Post deleted = save(Post.createVote(AUTHOR_ID, "지운 투표", "골라주세요", PostCategory.FASHION, sevenHoursAgo));
        deleted.closeVoteAtDeadline();
        deleted.delete();
        entityManager.flush();

        assertThat(postRepository.findPostIdsAwaitingVoteCloseNotification(PageRequest.of(0, 100)))
                .containsExactly(closedByServer.getId());

        assertThat(postRepository.markVoteCloseNotified(closedByServer.getId())).isEqualTo(1);

        assertThat(postRepository.findPostIdsAwaitingVoteCloseNotification(PageRequest.of(0, 100))).isEmpty();
        assertThat(postRepository.findById(closedByServer.getId()).orElseThrow().isVoteClosed()).isTrue();
    }

    // 계속 실패하는 글이 한 페이지를 차지해 새로 마감된 글의 알림을 막지 않는지 확인한다.
    @Test
    void votesWhoseNotificationFailedGoBehindVotesNotTriedYet() {
        Instant eightHoursAgo = Instant.now().minus(Duration.ofHours(8));
        Post failedLongAgo = save(Post.createVote(AUTHOR_ID, "오래전 실패", "골라주세요", PostCategory.FASHION, eightHoursAgo));
        failedLongAgo.closeVoteAtDeadline();
        Post failedRecently = save(Post.createVote(AUTHOR_ID, "방금 실패", "골라주세요", PostCategory.FASHION, eightHoursAgo));
        failedRecently.closeVoteAtDeadline();
        Post notTriedYet = save(Post.createVote(
                AUTHOR_ID, "새로 마감", "골라주세요", PostCategory.FASHION, eightHoursAgo.plus(Duration.ofHours(1))));
        notTriedYet.closeVoteAtDeadline();
        entityManager.flush();
        Instant now = Instant.now();
        postRepository.recordVoteCloseNotifyFailure(failedLongAgo.getId(), now.minus(Duration.ofMinutes(10)));
        postRepository.recordVoteCloseNotifyFailure(failedRecently.getId(), now);

        assertThat(postRepository.findPostIdsAwaitingVoteCloseNotification(PageRequest.of(0, 100)))
                .containsExactly(notTriedYet.getId(), failedLongAgo.getId(), failedRecently.getId());
        assertThat(postRepository.findPostIdsAwaitingVoteCloseNotification(PageRequest.of(0, 1)))
                .containsExactly(notTriedYet.getId());
    }

    @Test
    void aSharedPostKeepsItsTargetAndSnapshot() {
        Post post = Post.createNormal(AUTHOR_ID, "월드컵 결과", "우승 상품", PostCategory.FASHION, null);
        post.attachShared(SharedType.WORLDCUP, 9L,
                new SharedSnapshot(55L, "Fitzza", "오버핏 셔츠", 39000, "https://cdn.example.com/55.png"));
        Long postId = save(post).getId();
        Long normalId = save(Post.createNormal(AUTHOR_ID, "일반 글", "내용", PostCategory.DAILY, null)).getId();
        entityManager.flush();
        entityManager.clear();

        Post loaded = postRepository.findById(postId).orElseThrow();
        assertThat(loaded.getSharedType()).isEqualTo(SharedType.WORLDCUP);
        assertThat(loaded.getSharedId()).isEqualTo(9L);
        assertThat(loaded.getSharedSnapshot().getProductName()).isEqualTo("오버핏 셔츠");
        assertThat(loaded.getSharedSnapshot().getImageUrl()).isEqualTo("https://cdn.example.com/55.png");
        Post normal = postRepository.findById(normalId).orElseThrow();
        assertThat(normal.isShared()).isFalse();
        assertThat(normal.getSharedSnapshot()).isNull();
    }

    private Post save(Post post) {
        return postRepository.save(post);
    }

    private List<String> feedTitles(PostCategory category, PostType postType, String keyword) {
        return postRepository
                .findAll(PostSpecifications.feed(category, postType, keyword), PageRequest.of(0, 20))
                .getContent()
                .stream()
                .map(Post::getTitle)
                .toList();
    }
}
