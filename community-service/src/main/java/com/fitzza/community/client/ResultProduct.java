package com.fitzza.community.client;

// 추천 코디·피팅 결과에 들어 있는 상품 하나. 공유 글 스냅샷과 투표 선택지 표시에 쓴다.
public record ResultProduct(Long productId, String productName, Integer price, String imageUrl) {
}
