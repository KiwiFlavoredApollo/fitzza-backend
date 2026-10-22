package com.fitzza.community.domain;

// 게시글로 공유할 수 있는 대상. sharedId는 각각 월드컵 이력 ID, 추천 코디 ID, 가상 피팅 ID, 찜한 상품 ID다.
public enum SharedType {
    WORLDCUP,
    COMBO,
    FITTING,
    PRODUCT
}
