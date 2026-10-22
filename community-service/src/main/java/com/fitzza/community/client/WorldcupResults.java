package com.fitzza.community.client;

import java.util.Optional;

// 공유하려는 월드컵 결과를 상품 서비스에서 확인하는 자리. 테스트에서는 가짜 구현으로 바꿔 끼운다.
public interface WorldcupResults {

    // 없는 결과면 빈 값이다. 상품 서비스가 응답하지 않으면 RestClientException을 던진다.
    Optional<WorldcupResult> findWorldcup(long worldcupHistoryId, Long userId);

    record WorldcupResult(Long worldcupHistoryId, boolean owned, WinnerProduct winnerProduct) {
    }

    record WinnerProduct(Long productId, String brandName, String productName, Integer price, String imageUrl) {
    }
}
