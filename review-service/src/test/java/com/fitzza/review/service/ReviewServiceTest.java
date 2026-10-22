package com.fitzza.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.review.client.OrderItems.OrderItem;
import com.fitzza.review.domain.Review;
import com.fitzza.review.domain.ReviewSort;
import com.fitzza.review.dto.ReviewCreateRequest;
import com.fitzza.review.dto.ReviewPageResponse;
import com.fitzza.review.dto.ReviewUpdateRequest;
import com.fitzza.review.dto.ReviewUpdatedResponse;
import com.fitzza.review.exception.ErrorCode;
import com.fitzza.review.exception.ReviewApiException;
import com.fitzza.review.repository.ReviewRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

class ReviewServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final Long PRODUCT_ID = 30L;
    private static final long ORDER_ITEM_ID = 500L;

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final OrderItemVerifier orderItemVerifier = mock(OrderItemVerifier.class);
    private final ReviewService reviewService = new ReviewService(reviewRepository, orderItemVerifier);

    @Test
    void createStoresTheReviewForAVerifiedOrderItem() {
        when(orderItemVerifier.requireReviewable(USER_ID, ORDER_ITEM_ID))
                .thenReturn(new OrderItem(true, PRODUCT_ID, 3L, "COMPLETED"));
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            ReflectionTestUtils.setField(review, "id", 11L);
            return review;
        });

        Long reviewId = reviewService
                .create(USER_ID, new ReviewCreateRequest(PRODUCT_ID, ORDER_ITEM_ID, 4, "  잘 맞아요 ", "  "))
                .reviewId();

        ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).saveAndFlush(saved.capture());
        assertThat(reviewId).isEqualTo(11L);
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getValue().getOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        assertThat(saved.getValue().getRating()).isEqualTo(4);
        assertThat(saved.getValue().getImageUrl()).isNull();
    }

    @Test
    void createRejectsAnAlreadyReviewedOrderItemBeforeAskingTheOrderService() {
        when(reviewRepository.existsByOrderItemId(ORDER_ITEM_ID)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.create(USER_ID, request()))
                .isInstanceOfSatisfying(ReviewApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_REVIEW));
        verifyNoInteractions(orderItemVerifier);
    }

    @Test
    void createRejectsAProductThatIsNotTheOrderedOne() {
        when(orderItemVerifier.requireReviewable(USER_ID, ORDER_ITEM_ID))
                .thenReturn(new OrderItem(true, 99L, 3L, "COMPLETED"));

        assertThatThrownBy(() -> reviewService.create(USER_ID, request()))
                .isInstanceOfSatisfying(ReviewApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_ITEM_PRODUCT_MISMATCH));
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void createPassesOnTheOrderCheckFailure() {
        when(orderItemVerifier.requireReviewable(USER_ID, ORDER_ITEM_ID))
                .thenThrow(new ReviewApiException(ErrorCode.NOT_ORDER_OWNER));

        assertThatThrownBy(() -> reviewService.create(USER_ID, request()))
                .isInstanceOfSatisfying(ReviewApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_ORDER_OWNER));
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void createTurnsAConcurrentDuplicateIntoDuplicateReview() {
        when(orderItemVerifier.requireReviewable(USER_ID, ORDER_ITEM_ID))
                .thenReturn(new OrderItem(true, PRODUCT_ID, 3L, "COMPLETED"));
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DataIntegrityViolationException("uk_reviews_order_item"));

        assertThatThrownBy(() -> reviewService.create(USER_ID, request()))
                .isInstanceOfSatisfying(ReviewApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_REVIEW));
    }

    @Test
    void updateReplacesRatingContentAndImageForTheAuthor() {
        Review review = review(USER_ID);
        when(reviewRepository.findById(11L)).thenReturn(Optional.of(review));

        ReviewUpdatedResponse response =
                reviewService.update(USER_ID, 11L, new ReviewUpdateRequest(2, "조금 작아요", null));

        assertThat(response.reviewId()).isEqualTo(11L);
        assertThat(response.rating()).isEqualTo(2);
        assertThat(response.content()).isEqualTo("조금 작아요");
        assertThat(response.imageUrl()).isNull();
        verify(reviewRepository).flush();
    }

    @Test
    void onlyTheAuthorCanUpdateOrDelete() {
        when(reviewRepository.findById(11L)).thenReturn(Optional.of(review(USER_ID)));

        assertThatThrownBy(() -> reviewService.update(OTHER_ID, 11L, new ReviewUpdateRequest(1, "남의 리뷰", null)))
                .isInstanceOfSatisfying(ReviewApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_AUTHOR));
        assertThatThrownBy(() -> reviewService.delete(OTHER_ID, 11L))
                .isInstanceOfSatisfying(ReviewApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_AUTHOR));
        verify(reviewRepository, never()).delete(any());
    }

    @Test
    void missingReviewIsNotFound() {
        when(reviewRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.delete(USER_ID, 404L))
                .isInstanceOfSatisfying(ReviewApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REVIEW_NOT_FOUND));
    }

    @Test
    void deleteRemovesTheAuthorsReview() {
        Review review = review(USER_ID);
        when(reviewRepository.findById(11L)).thenReturn(Optional.of(review));

        reviewService.delete(USER_ID, 11L);

        verify(reviewRepository).delete(review);
    }

    @Test
    void listUsesTheRequestedPageAndSort() {
        when(reviewRepository.findByProductId(any(), any()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(review(USER_ID)), invocation.getArgument(1), 21));

        ReviewPageResponse response = reviewService.findByProduct(PRODUCT_ID, 1, 10, ReviewSort.RATING_DESC);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findByProductId(org.mockito.ArgumentMatchers.eq(PRODUCT_ID), pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(1, 10, ReviewSort.RATING_DESC.toSort()));
        assertThat(pageable.getValue().getSort().getOrderFor("rating").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.content()).extracting("reviewId").containsExactly(11L);
    }

    @Test
    void listRejectsBadPaging() {
        assertThatThrownBy(() -> reviewService.findByProduct(PRODUCT_ID, -1, 10, ReviewSort.LATEST))
                .isInstanceOf(ReviewApiException.class);
        assertThatThrownBy(() -> reviewService.findByProduct(PRODUCT_ID, 10_001, 10, ReviewSort.LATEST))
                .isInstanceOf(ReviewApiException.class);
        assertThatThrownBy(() -> reviewService.findByProduct(PRODUCT_ID, 0, 101, ReviewSort.LATEST))
                .isInstanceOf(ReviewApiException.class);
        assertThatThrownBy(() -> reviewService.findByProduct(0L, 0, 10, ReviewSort.LATEST))
                .isInstanceOf(ReviewApiException.class);
        verifyNoInteractions(reviewRepository);
    }

    private static ReviewCreateRequest request() {
        return new ReviewCreateRequest(PRODUCT_ID, ORDER_ITEM_ID, 5, "좋아요", null);
    }

    private static Review review(Long authorId) {
        Review review = Review.create(authorId, PRODUCT_ID, ORDER_ITEM_ID, 5, "좋아요", "https://cdn.example.com/a.png");
        ReflectionTestUtils.setField(review, "id", 11L);
        return review;
    }
}
