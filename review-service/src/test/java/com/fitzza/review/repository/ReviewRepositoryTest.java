package com.fitzza.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.review.domain.Review;
import com.fitzza.review.domain.ReviewSort;
import com.fitzza.review.domain.ReviewSource;
import com.fitzza.review.support.PostgresModeJpaTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@PostgresModeJpaTest
class ReviewRepositoryTest {

    private static final Long PRODUCT_ID = 30L;

    @Autowired
    private ReviewRepository reviewRepository;

    @Test
    void listsOnlyTheProductsReviewsInTheRequestedOrder() {
        Review first = save(7L, PRODUCT_ID, 501L, 3);
        Review second = save(8L, PRODUCT_ID, 502L, 5);
        Review third = save(9L, PRODUCT_ID, 503L, 3);
        save(9L, 31L, 504L, 1);

        assertThat(ids(ReviewSort.LATEST)).containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(ids(ReviewSort.RATING_DESC)).containsExactly(second.getId(), third.getId(), first.getId());
        assertThat(ids(ReviewSort.RATING_ASC)).containsExactly(third.getId(), first.getId(), second.getId());
    }

    @Test
    void pagesThroughReviews() {
        for (long orderItemId = 600; orderItemId < 605; orderItemId++) {
            save(7L, PRODUCT_ID, orderItemId, 4);
        }

        Page<Review> page = reviewRepository.findByProductId(PRODUCT_ID, PageRequest.of(1, 2, ReviewSort.LATEST.toSort()));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    void oneReviewPerOrderItem() {
        save(7L, PRODUCT_ID, 700L, 4);

        assertThat(reviewRepository.existsByOrderItemId(700L)).isTrue();
        assertThatThrownBy(() -> save(7L, PRODUCT_ID, 700L, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storesExternalReviewsWithoutUserOrOrderAndRejectsDuplicateSourceIdentity() {
        Instant createdAt = Instant.parse("2026-10-22T03:00:00Z");
        Review external = reviewRepository.saveAndFlush(
                Review.createExternal(PRODUCT_ID, 88_328_540L, 5, "편해요", null, createdAt));

        assertThat(external.getUserId()).isNull();
        assertThat(external.getOrderItemId()).isNull();
        assertThat(external.getSource()).isEqualTo(ReviewSource.MUSINSA);
        assertThat(external.getSourceReviewId()).isEqualTo(88_328_540L);
        assertThat(external.getCreatedAt()).isEqualTo(createdAt);
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(
                        Review.createExternal(PRODUCT_ID, 88_328_540L, 4, "중복", null, createdAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<Long> ids(ReviewSort sort) {
        return reviewRepository.findByProductId(PRODUCT_ID, PageRequest.of(0, 10, sort.toSort())).stream()
                .map(Review::getId)
                .toList();
    }

    // 같은 시각에 저장돼도 id가 순서를 정한다.
    private Review save(Long userId, Long productId, Long orderItemId, int rating) {
        return reviewRepository.saveAndFlush(Review.create(userId, productId, orderItemId, rating, "후기", null));
    }
}
