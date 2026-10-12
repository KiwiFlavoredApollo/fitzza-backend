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
import com.fitzza.notification.security.InternalCallSecurityConfig;
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
    "internal.call-token=test-only-internal-call-token",
    "spring.cloud.config.enabled=false"
})
class InternalNotificationControllerTest {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String INTERNAL_TOKEN = "test-only-internal-call-token";
    private static final String EVENTS = "/internal/notifications/events";
    private static final String COMMENT_EVENT = "{\"eventId\":\"comment-55\",\"type\":\"COMMENT\",\"userId\":7,"
            + "\"targetId\":\"10\",\"content\":\"새 댓글이 달렸습니다.\"}";

    @MockBean
    private NotificationService notificationService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsAnEventFromATrustedService() throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
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
    void missingInternalTokenIsDeniedEvenWithUserIdentityHeaders() throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header("X-User-Id", "7")
                        .header("Authorization", "Bearer external-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMMENT_EVENT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "wrong-token"})
    void invalidInternalTokensAreDenied(String token) throws Exception {
        mockMvc.perform(post(EVENTS)
                        .header(INTERNAL_TOKEN_HEADER, token)
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingOrBlankConfiguredTokenIsRejected(String token) {
        assertThatThrownBy(() -> new InternalCallSecurityConfig(token))
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
                        .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
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
                        .header(INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(notificationService);
    }
}
