package com.fitzza.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.PostType;
import com.fitzza.community.support.PostgresModeJpaTest;
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
