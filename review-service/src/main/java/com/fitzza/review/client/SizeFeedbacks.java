package com.fitzza.review.client;

import com.fitzza.review.domain.SizeFeedback;

// 사이즈 설문 결과를 추천 서비스로 보내는 자리. 테스트에서는 가짜 구현으로 바꿔 끼운다.
public interface SizeFeedbacks {

    // 추천 서비스 주소나 이 서비스의 토큰이 없으면 false다. 그동안 설문은 '보내지 않음'으로 남아 있다가 설정 뒤 보내진다.
    boolean isEnabled();

    // 실패하면 RestClientException을 던진다.
    void send(SizeFeedbackRequest request);

    record SizeFeedbackRequest(Long userId, Long productId, Long optionId, SizeFeedback sizeFeedback) {
    }
}
