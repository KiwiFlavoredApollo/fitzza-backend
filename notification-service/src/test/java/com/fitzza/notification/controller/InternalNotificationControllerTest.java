package com.fitzza.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.notification.domain.NotificationType;
import com.fitzza.notification.dto.NotificationEventRequest;
import com.fitzza.notification.security.ServiceTokenSecurityConfig;
import com.fitzza.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InternalNotificationController.class, properties = {
    "fitzza.service-tokens.community=" + InternalNotificationControllerTest.COMMUNITY_TOKEN,
    "fitzza.service-tokens.vton=" + InternalNotificationControllerTest.VTON_TOKEN,
    "fitzza.service-tokens.recommendation=" + InternalNotificationControllerTest.RECOMMENDATION_TOKEN,
    "spring.cloud.config.enabled=false"
})
class InternalNotificationControllerTest {

    static final String COMMUNITY_TOKEN = "test-only-community-service-token-32b";
    static final String VTON_TOKEN = "test-only-vton-service-token-32-bytes";
    static final String RECOMMENDATION_TOKEN = "test-only-recommendation-token-32-bytes";
    private static final String SERVICE_TOKEN = "X-Service-Token";
    private static final String EVENTS = "/notifications/events";
    private static final String COMMENT_EVENT = "{\"eventId\":\"comment-55\",\"type\":\"COMMENT\",\"userId\":7,"
            + "\"targetId\":\"10\",\"content\":\"새 댓글이 달렸습니다.\"}";

    @MockBean
    private NotificationService notificationService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsAnEventFromATrustedService() throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header(SERVICE_TOKEN, COMMUNITY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMMENT_EVENT))
                .andExpect(status().isAccepted());

        ArgumentCaptor<NotificationEventRequest> captor = ArgumentCaptor.forClass(NotificationEventRequest.class);
        verify(notificationService).receive(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("comment-55");
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.COMMENT);
        assertThat(captor.getValue().userId()).isEqualTo(7L);
        assertThat(captor.getValue().targetId()).isEqualTo("10");
        assertThat(captor.getValue().content()).isEqualTo("새 댓글이 달렸습니다.");
    }

    @Test
    void missingServiceTokenIsDeniedEvenWithUserIdentityHeaders() throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header("X-User-Id", "7")
                        .header("Authorization", "Bearer external-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMMENT_EVENT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(notificationService);
    }

    // 추천 서비스는 추천 결과가 나오면 자기 토큰으로 RECOMMENDATION_DONE 알림을 보낸다.
    @Test
    void acceptsARecommendationResultFromTheRecommendationService() throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header(SERVICE_TOKEN, RECOMMENDATION_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"recommendation-req-1-done\",\"type\":\"RECOMMENDATION_DONE\","
                                + "\"userId\":7,\"targetId\":\"req-1\",\"content\":\"추천 결과가 나왔어요.\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<NotificationEventRequest> captor = ArgumentCaptor.forClass(NotificationEventRequest.class);
        verify(notificationService).receive(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.RECOMMENDATION_DONE);
        assertThat(captor.getValue().targetId()).isEqualTo("req-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "wrong-token"})
    void unknownServiceTokensAreDenied(String token) throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header(SERVICE_TOKEN, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMMENT_EVENT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(notificationService);
    }

    @Test
    void authenticationRunsBeforeBodyValidation() throws Exception {
        mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notificationService);
    }

    // 피팅 서비스(vton)도 자기 토큰으로 알림을 보낼 수 있다.
    @Test
    void acceptsAnEventFromTheFittingServiceWithItsOwnToken() throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header(SERVICE_TOKEN, VTON_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMMENT_EVENT))
                .andExpect(status().isAccepted());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "too-short"})
    void missingBlankOrShortCommunityTokenIsRejected(String token) {
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(token, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoServicesCannotShareOneToken() {
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(COMMUNITY_TOKEN, "", COMMUNITY_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"COMMENT\",\"userId\":7,\"content\":\"새 댓글이 달렸습니다.\"}",
        "{\"eventId\":\" \",\"type\":\"COMMENT\",\"userId\":7,\"content\":\"새 댓글이 달렸습니다.\"}",
        "{\"eventId\":\"comment-55\",\"userId\":7,\"content\":\"새 댓글이 달렸습니다.\"}",
        "{\"eventId\":\"comment-55\",\"type\":\"COMMENT\",\"content\":\"새 댓글이 달렸습니다.\"}",
        "{\"eventId\":\"comment-55\",\"type\":\"COMMENT\",\"userId\":0,\"content\":\"새 댓글이 달렸습니다.\"}",
        "{\"eventId\":\"comment-55\",\"type\":\"COMMENT\",\"userId\":7,\"content\":\" \"}",
        "{\"eventId\":\"comment-55\",\"type\":\"LIKE\",\"userId\":7,\"content\":\"새 댓글이 달렸습니다.\"}"
    })
    void rejectsAnEventWithMissingOrInvalidFields(String body) throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header(SERVICE_TOKEN, COMMUNITY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectsContentLongerThanTheLimit() throws Exception {
        String body = "{\"eventId\":\"comment-55\",\"type\":\"COMMENT\",\"userId\":7,\"content\":\""
                + "가".repeat(501) + "\"}";

        mockMvc.perform(post(EVENTS)
                        .header(SERVICE_TOKEN, COMMUNITY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(notificationService);
    }
}
