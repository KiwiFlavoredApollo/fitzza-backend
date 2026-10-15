package com.fitzza.review.service;

// 설문이 저장됐을 때 발행한다. 저장이 확정된 뒤 추천 서비스로 사이즈 평가를 보내는 데 쓴다.
public record SurveyCreatedEvent(Long surveyId) {
}
