package com.fitzza.review.service;

import com.fitzza.review.client.OrderItems.OrderItem;
import com.fitzza.review.domain.Review;
import com.fitzza.review.domain.ReviewSort;
import com.fitzza.review.dto.ReviewCreateRequest;
import com.fitzza.review.dto.ReviewCreatedResponse;
import com.fitzza.review.dto.ReviewPageResponse;
import com.fitzza.review.dto.ReviewResponse;
import com.fitzza.review.dto.ReviewUpdateRequest;
import com.fitzza.review.dto.ReviewUpdatedResponse;
import com.fitzza.review.exception.ErrorCode;
import com.fitzza.review.exception.ReviewApiException;
import com.fitzza.review.repository.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    static final int MAX_PAGE_SIZE = 100;

    private final ReviewRepository reviewRepository;
    private final OrderItemVerifier orderItemVerifier;

    public ReviewService(ReviewRepository reviewRepository, OrderItemVerifier orderItemVerifier) {
        this.reviewRepository = reviewRepository;
        this.orderItemVerifier = orderItemVerifier;
    }

    // 이미 쓴 리뷰는 주문 서비스를 부르기 전에 거른다. 동시에 두 번 들어오면 고유 제약이 막는다.
    @Transactional
    public ReviewCreatedResponse create(Long userId, ReviewCreateRequest request) {
        if (reviewRepository.existsByOrderItemId(request.orderItemId())) {
            throw new ReviewApiException(ErrorCode.DUPLICATE_REVIEW);
        }
        OrderItem orderItem = orderItemVerifier.requireReviewable(userId, request.orderItemId());
        if (!request.productId().equals(orderItem.productId())) {
            throw new ReviewApiException(ErrorCode.ORDER_ITEM_PRODUCT_MISMATCH);
        }
        try {
            Review review = reviewRepository.saveAndFlush(Review.create(
                    userId,
                    request.productId(),
                    request.orderItemId(),
                    request.rating(),
                    request.content(),
                    blankToNull(request.imageUrl())));
            return new ReviewCreatedResponse(review.getId());
        } catch (DataIntegrityViolationException exception) {
            throw new ReviewApiException(ErrorCode.DUPLICATE_REVIEW);
        }
    }

    @Transactional
    public ReviewUpdatedResponse update(Long userId, Long reviewId, ReviewUpdateRequest request) {
        Review review = findOwnReview(userId, reviewId);
        review.update(request.rating(), request.content(), blankToNull(request.imageUrl()));
        reviewRepository.flush();
        return ReviewUpdatedResponse.from(review);
    }

    @Transactional
    public void delete(Long userId, Long reviewId) {
        Review review = findOwnReview(userId, reviewId);
        reviewRepository.delete(review);
        reviewRepository.flush();
    }

    // 상품 상세의 리뷰 목록. 로그인 없이 볼 수 있다.
    @Transactional(readOnly = true)
    public ReviewPageResponse findByProduct(Long productId, int page, int size, ReviewSort sort) {
        if (productId == null || productId <= 0 || page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ReviewApiException(ErrorCode.INVALID_REQUEST);
        }
        return ReviewPageResponse.from(reviewRepository
                .findByProductId(productId, PageRequest.of(page, size, sort.toSort()))
                .map(ReviewResponse::from));
    }

    private Review findOwnReview(Long userId, Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ReviewApiException(ErrorCode.REVIEW_NOT_FOUND));
        if (!review.isWrittenBy(userId)) {
            throw new ReviewApiException(ErrorCode.NOT_AUTHOR);
        }
        return review;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
