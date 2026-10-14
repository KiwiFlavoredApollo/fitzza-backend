package com.fitzza.review.service;

import com.fitzza.review.client.OrderItems.OrderItem;
import com.fitzza.review.domain.SizeSurvey;
import com.fitzza.review.dto.SurveyCreateRequest;
import com.fitzza.review.dto.SurveyCreatedResponse;
import com.fitzza.review.dto.SurveyResponse;
import com.fitzza.review.exception.ErrorCode;
import com.fitzza.review.exception.ReviewApiException;
import com.fitzza.review.repository.SizeSurveyRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SurveyService {

    static final int MAX_LOOKUP_SIZE = 100;

    private final SizeSurveyRepository sizeSurveyRepository;
    private final OrderItemVerifier orderItemVerifier;

    public SurveyService(SizeSurveyRepository sizeSurveyRepository, OrderItemVerifier orderItemVerifier) {
        this.sizeSurveyRepository = sizeSurveyRepository;
        this.orderItemVerifier = orderItemVerifier;
    }

    // 이미 쓴 설문은 주문 서비스를 부르기 전에 거른다. 동시에 두 번 들어오면 고유 제약이 막는다.
    @Transactional
    public SurveyCreatedResponse create(Long userId, SurveyCreateRequest request) {
        if (sizeSurveyRepository.existsByUserIdAndOrderItemId(userId, request.orderItemId())) {
            throw new ReviewApiException(ErrorCode.DUPLICATE_SURVEY);
        }
        OrderItem orderItem = orderItemVerifier.requireReviewable(userId, request.orderItemId());
        try {
            SizeSurvey survey = sizeSurveyRepository.saveAndFlush(SizeSurvey.of(
                    userId, orderItem.optionId(), request.orderItemId(), request.sizeFeedback()));
            return new SurveyCreatedResponse(survey.getId());
        } catch (DataIntegrityViolationException exception) {
            throw new ReviewApiException(ErrorCode.DUPLICATE_SURVEY);
        }
    }

    // 주문 내역 화면에서 주문 상품마다 설문을 썼는지 표시하는 데 쓴다. 내가 쓴 설문만 돌려준다.
    @Transactional(readOnly = true)
    public List<SurveyResponse> findMine(Long userId, List<Long> orderItemIds) {
        if (orderItemIds == null || orderItemIds.isEmpty() || orderItemIds.size() > MAX_LOOKUP_SIZE) {
            throw new ReviewApiException(ErrorCode.INVALID_REQUEST);
        }
        return sizeSurveyRepository.findByUserIdAndOrderItemIdInOrderByOrderItemIdAsc(userId, orderItemIds).stream()
                .map(SurveyResponse::from)
                .toList();
    }
}
