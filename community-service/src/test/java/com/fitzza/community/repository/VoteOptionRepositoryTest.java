package com.fitzza.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.domain.VoteOptionSource;
import com.fitzza.community.support.PostgresModeJpaTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@PostgresModeJpaTest
class VoteOptionRepositoryTest {

    private static final Long POST_ID = 10L;
    private static final Long OTHER_POST_ID = 11L;
    private static final String IMAGE_URL = "https://cdn.example.com/options/1.png";

    @Autowired
    private VoteOptionRepository voteOptionRepository;

    @Test
    void optionsComeBackInDisplayOrderForTheirPostOnly() {
        voteOptionRepository.saveAll(List.of(
                product(POST_ID, 2, "56"),
                product(POST_ID, 1, "55"),
                product(OTHER_POST_ID, 1, "55")));

        assertThat(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID))
                .extracting(VoteOption::getItemId, VoteOption::getLabel)
                .containsExactly(
                        tuple("55", "A"),
                        tuple("56", "B"));
    }

    @Test
    void optionBelongsOnlyToThePostItWasCreatedFor() {
        VoteOption option = voteOptionRepository.save(product(POST_ID, 1, "55"));

        assertThat(voteOptionRepository.existsByIdAndPostId(option.getId(), POST_ID)).isTrue();
        assertThat(voteOptionRepository.existsByIdAndPostId(option.getId(), OTHER_POST_ID)).isFalse();
    }

    @Test
    void sameProductCannotBeAddedTwiceToOneVote() {
        voteOptionRepository.saveAndFlush(product(POST_ID, 1, "55"));

        assertThatThrownBy(() -> voteOptionRepository.saveAndFlush(product(POST_ID, 2, "55")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 직접 올린 사진은 item_id가 비어 있어 같은 투표에 여러 장 넣을 수 있다.
    @Test
    void severalUploadedPhotosFitInOneVote() {
        voteOptionRepository.saveAndFlush(upload(POST_ID, 1));
        voteOptionRepository.saveAndFlush(upload(POST_ID, 2));

        assertThat(voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(POST_ID)).hasSize(2);
    }

    private static VoteOption product(Long postId, int displayOrder, String itemId) {
        return VoteOption.of(
                postId, displayOrder, VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, itemId, IMAGE_URL, "셔츠", 39000);
    }

    private static VoteOption upload(Long postId, int displayOrder) {
        return VoteOption.of(
                postId, displayOrder, VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, IMAGE_URL, null, null);
    }
}
