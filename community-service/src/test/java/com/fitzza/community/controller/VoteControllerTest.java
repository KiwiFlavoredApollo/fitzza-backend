package com.fitzza.community.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.community.dto.VoteCastResponse;
import com.fitzza.community.dto.VoteClosedResponse;
import com.fitzza.community.dto.VoteOptionResult;
import com.fitzza.community.dto.VoteResultResponse;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import com.fitzza.community.exception.GlobalExceptionHandler;
import com.fitzza.community.service.VoteService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VoteControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String POSTS = "/api/community/posts";
    private final VoteService voteService = mock(VoteService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new VoteController(voteService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void votePassesTheChosenOption() throws Exception {
        when(voteService.vote(8L, 3L, 100L)).thenReturn(new VoteCastResponse(100L));

        mockMvc.perform(post(POSTS + "/3/poll/votes")
                        .header(USER_ID_HEADER, "8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voteOptionId\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voteOptionId").value(100));
    }

    @Test
    void voteOnAClosedVoteIsAConflict() throws Exception {
        when(voteService.vote(8L, 3L, 100L)).thenThrow(new CommunityApiException(ErrorCode.VOTE_CLOSED));

        mockMvc.perform(post(POSTS + "/3/poll/votes")
                        .header(USER_ID_HEADER, "8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voteOptionId\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOTE_CLOSED"));
    }

    @Test
    void voteRequiresAnOptionAndTheUserHeader() throws Exception {
        mockMvc.perform(post(POSTS + "/3/poll/votes")
                        .header(USER_ID_HEADER, "8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(POSTS + "/3/poll/votes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"voteOptionId\":100}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(voteService);
    }

    @Test
    void resultsArePublicAndIncludeTheViewerChoiceWhenLoggedIn() throws Exception {
        when(voteService.getResults(3L, null)).thenReturn(sampleResults(null));
        when(voteService.getResults(3L, 8L)).thenReturn(sampleResults(100L));

        mockMvc.perform(get(POSTS + "/3/poll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.options[0].label").value("A"))
                .andExpect(jsonPath("$.options[0].count").value(3))
                .andExpect(jsonPath("$.myVoteOptionId").doesNotExist());
        mockMvc.perform(get(POSTS + "/3/poll").header(USER_ID_HEADER, "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myVoteOptionId").value(100));
    }

    @Test
    void closeReturnsTheClosedStateAndIsForbiddenForOthers() throws Exception {
        when(voteService.close(7L, 3L)).thenReturn(new VoteClosedResponse(true));
        when(voteService.close(8L, 3L)).thenThrow(new CommunityApiException(ErrorCode.NOT_AUTHOR));

        mockMvc.perform(closePoll("7", "{\"closed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true));
        mockMvc.perform(closePoll("8", "{\"closed\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_AUTHOR"));
    }

    @Test
    void closeRejectsReopeningAndAMissingClosedValue() throws Exception {
        mockMvc.perform(closePoll("7", "{\"closed\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(closePoll("7", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(voteService);
    }

    private static MockHttpServletRequestBuilder closePoll(String userId, String body) {
        return patch(POSTS + "/3/poll")
                .header(USER_ID_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static VoteResultResponse sampleResults(Long myVoteOptionId) {
        return new VoteResultResponse(
                List.of(new VoteOptionResult(100L, "A", 3, 1.0), new VoteOptionResult(101L, "B", 0, 0.0)),
                3,
                null,
                false,
                myVoteOptionId);
    }
}
