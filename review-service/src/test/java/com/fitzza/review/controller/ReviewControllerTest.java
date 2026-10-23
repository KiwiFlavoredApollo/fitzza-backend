package com.fitzza.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fitzza.review.domain.ReviewSort;
import com.fitzza.review.dto.ReviewCreateRequest;
import com.fitzza.review.dto.ReviewCreatedResponse;
import com.fitzza.review.dto.ReviewPageResponse;
import com.fitzza.review.dto.ReviewResponse;
import com.fitzza.review.dto.ReviewUpdateRequest;
import com.fitzza.review.dto.ReviewUpdatedResponse;
import com.fitzza.review.exception.ErrorCode;
import com.fitzza.review.exception.GlobalExceptionHandler;
import com.fitzza.review.exception.ReviewApiException;
import com.fitzza.review.service.ReviewService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String REVIEWS = "/api/reviews";

    private final ReviewService reviewService = mock(ReviewService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .build()))
                .build();
    }

    @Test
    void listIsPublicAndDefaultsToTheFirstPageOfLatestReviews() throws Exception {
        ReviewResponse review = new ReviewResponse(11L, 30L, 7L, "FITZZA", 4, "잘 맞아요", null, null);
        when(reviewService.findByProduct(30L, 0, 20, ReviewSort.LATEST))
                .thenReturn(new ReviewPageResponse(List.of(review), 0, 20, 1, 1, false));

        mockMvc.perform(get(REVIEWS).param("productId", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reviewId").value(11))
                .andExpect(jsonPath("$.content[0].source").value("FITZZA"))
                .andExpect(jsonPath("$.content[0].rating").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listPassesPageSizeAndSort() throws Exception {
        when(reviewService.findByProduct(30L, 2, 5, ReviewSort.RATING_ASC))
                .thenReturn(new ReviewPageResponse(List.of(), 2, 5, 0, 0, false));

        mockMvc.perform(get(REVIEWS).param("productId", "30").param("page", "2").param("size", "5")
                        .param("sort", "RATING_ASC"))
                .andExpect(status().isOk());
        verify(reviewService).findByProduct(30L, 2, 5, ReviewSort.RATING_ASC);
    }

    @Test
    void listRejectsAnUnknownSortOrMissingProduct() throws Exception {
        mockMvc.perform(get(REVIEWS).param("productId", "30").param("sort", "POPULAR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(REVIEWS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(reviewService);
    }

    @Test
    void createNeedsLogin() throws Exception {
        mockMvc.perform(post(REVIEWS).contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(reviewService);
    }

    @Test
    void createReturnsTheNewReviewId() throws Exception {
        when(reviewService.create(eq(7L), any(ReviewCreateRequest.class))).thenReturn(new ReviewCreatedResponse(11L));

        mockMvc.perform(post(REVIEWS).header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(11));
        verify(reviewService).create(7L, new ReviewCreateRequest(30L, 500L, 5, "좋아요", null));
    }

    @Test
    void createRejectsARatingOutsideOneToFive() throws Exception {
        String body = "{\"productId\":30,\"orderItemId\":500,\"rating\":6,\"content\":\"좋아요\"}";

        mockMvc.perform(post(REVIEWS).header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("별점은 1~5점입니다."));
        verifyNoInteractions(reviewService);
    }

    // application.yml의 spring.jackson.deserialization.accept-float-as-int=false와 같은 설정으로 확인한다.
    @Test
    void createRejectsAFractionalRating() throws Exception {
        String body = "{\"productId\":30,\"orderItemId\":500,\"rating\":4.5,\"content\":\"좋아요\"}";

        mockMvc.perform(post(REVIEWS).header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(reviewService);
    }

    @Test
    void createReportsAnOrderCheckFailure() throws Exception {
        when(reviewService.create(eq(7L), any(ReviewCreateRequest.class)))
                .thenThrow(new ReviewApiException(ErrorCode.NOT_ORDER_OWNER));

        mockMvc.perform(post(REVIEWS).header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ORDER_OWNER"));
    }

    @Test
    void updateReturnsTheChangedReview() throws Exception {
        when(reviewService.update(eq(7L), eq(11L), any(ReviewUpdateRequest.class)))
                .thenReturn(new ReviewUpdatedResponse(11L, 3, "보통이에요", null, null));

        mockMvc.perform(patch(REVIEWS + "/11").header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":3,\"content\":\"보통이에요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(11))
                .andExpect(jsonPath("$.rating").value(3));
        verify(reviewService).update(7L, 11L, new ReviewUpdateRequest(3, "보통이에요", null));
    }

    @Test
    void deleteReturnsNoContentAndReportsNonAuthors() throws Exception {
        mockMvc.perform(delete(REVIEWS + "/11").header(USER_ID_HEADER, "7"))
                .andExpect(status().isNoContent());
        verify(reviewService).delete(7L, 11L);

        doThrow(new ReviewApiException(ErrorCode.NOT_AUTHOR)).when(reviewService).delete(8L, 11L);
        mockMvc.perform(delete(REVIEWS + "/11").header(USER_ID_HEADER, "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_AUTHOR"));
    }

    private static String validBody() {
        return "{\"productId\":30,\"orderItemId\":500,\"rating\":5,\"content\":\"좋아요\"}";
    }
}
