package com.fitzza.notification.domain;

// 알림 종류. 모두 알림 목록에 저장되고 FCM 푸시로도 보낸다(2026-10-15 결정: 댓글·투표 마감·가상 피팅·추천 결과).
public enum NotificationType {
    COMMENT,
    VOTE_CLOSED,
    FITTING_DONE,
    FITTING_FAILED,
    RECOMMENDATION_DONE
}
