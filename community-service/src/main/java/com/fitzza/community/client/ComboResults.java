package com.fitzza.community.client;

import java.util.List;
import java.util.Optional;

// 공유하거나 투표 선택지로 고른 추천 코디를 추천 서비스에서 확인하는 자리. 테스트에서는 가짜 구현으로 바꿔 끼운다.
public interface ComboResults {

    // 없는 코디면 빈 값이다. 추천 서비스가 응답하지 않거나 주소가 설정되지 않았으면 RestClientException을 던진다.
    Optional<ComboResult> findCombo(long comboId, Long userId);

    // owned: 요청한 사용자가 받은 추천인지. 남의 추천이면 나머지 값은 비어 있을 수 있다.
    record ComboResult(boolean owned, List<ResultProduct> items, Integer totalPrice, String feedbackSummary) {
    }
}
