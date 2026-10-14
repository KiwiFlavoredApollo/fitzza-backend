package com.fitzza.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.review.domain.SizeFeedback;
import com.fitzza.review.dto.SurveyCreateRequest;
import com.fitzza.review.dto.SurveyCreatedResponse;
import com.fitzza.review.dto.SurveyResponse;
import com.fitzza.review.exception.ErrorCode;
import com.fitzza.review.exception.GlobalExceptionHandler;
import com.fitzza.review.exception.ReviewApiException;
import com.fitzza.review.service.SurveyService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SurveyControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String SURVEYS = "/api/surveys";

    private final SurveyService surveyService = mock(SurveyService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SurveyController(surveyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturnsTheNewSurveyId() throws Exception {
        when(surveyService.create(eq(7L), any(SurveyCreateRequest.class))).thenReturn(new SurveyCreatedResponse(21L));

        mockMvc.perform(post(SURVEYS).header(USER_ID_HEADER, "7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":500,\"sizeFeedback\":\"SMALL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.surveyId").value(21));
        verify(surveyService).create(7L, new SurveyCreateRequest(500L, SizeFeedback.SMALL));
    }

    @Test
    void createNeedsLogin() throws Exception {
        mockMvc.perform(post(SURVEYS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":500,\"sizeFeedback\":\"FIT\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(surveyService);
    }

    @Test
    void createRejectsAnUnknownFeedback() throws Exception {
        mockMvc.perform(post(SURVEYS).header(USER_ID_HEADER, "7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":500,\"sizeFeedback\":\"HUGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(surveyService);
    }

    @Test
    void createReportsADuplicate() throws Exception {
        when(surveyService.create(eq(7L), any(SurveyCreateRequest.class)))
                .thenThrow(new ReviewApiException(ErrorCode.DUPLICATE_SURVEY));

        mockMvc.perform(post(SURVEYS).header(USER_ID_HEADER, "7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":500,\"sizeFeedback\":\"BIG\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SURVEY"));
    }

    @Test
    void findMineReadsCommaSeparatedOrderItemIds() throws Exception {
        when(surveyService.findMine(7L, List.of(500L, 501L)))
                .thenReturn(List.of(new SurveyResponse(21L, 500L, SizeFeedback.FIT)));

        mockMvc.perform(get(SURVEYS + "/me").header(USER_ID_HEADER, "7").param("orderItemIds", "500,501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].surveyId").value(21))
                .andExpect(jsonPath("$[0].orderItemId").value(500))
                .andExpect(jsonPath("$[0].sizeFeedback").value("FIT"));
    }

    @Test
    void findMineNeedsLoginAndIds() throws Exception {
        mockMvc.perform(get(SURVEYS + "/me").param("orderItemIds", "500"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(SURVEYS + "/me").header(USER_ID_HEADER, "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(surveyService);
    }
}
