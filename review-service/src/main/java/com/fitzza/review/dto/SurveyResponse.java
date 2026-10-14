package com.fitzza.review.dto;

import com.fitzza.review.domain.SizeFeedback;
import com.fitzza.review.domain.SizeSurvey;

public record SurveyResponse(Long surveyId, Long orderItemId, SizeFeedback sizeFeedback) {

    public static SurveyResponse from(SizeSurvey survey) {
        return new SurveyResponse(survey.getId(), survey.getOrderItemId(), survey.getSizeFeedback());
    }
}
