package com.fitzza.community.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NotificationServiceClientTest {

    private static final String EVENTS_API = "http://notification-service/notifications/events";
    private static final String SERVICE_TOKEN = "test-community-service-token";
    private static final NotificationEvent EVENT =
            new NotificationEvent("comment-100", "COMMENT", 7L, "10", "내 게시글에 새 댓글이 달렸어요: 잘 어울려요");

    private MockRestServiceServer server;
    private NotificationServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NotificationServiceClient(builder, "http://notification-service", SERVICE_TOKEN);
    }

    @Test
    void postsTheEventWithTheInternalToken() {
        server.expect(requestTo(EVENTS_API))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(NotificationServiceClient.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"eventId\":\"comment-100\",\"type\":\"COMMENT\",\"userId\":7,"
                        + "\"targetId\":\"10\",\"content\":\"내 게시글에 새 댓글이 달렸어요: 잘 어울려요\"}"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(client.send(EVENT)).isTrue();

        server.verify();
    }

    @Test
    void aFailingNotificationServiceDoesNotThrow() {
        server.expect(requestTo(EVENTS_API)).andRespond(withServerError());

        assertThatCode(() -> assertThat(client.send(EVENT)).isFalse()).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void aMissingInternalTokenIsRejectedAtStartup() {
        assertThatThrownBy(() -> new NotificationServiceClient(
                        RestClient.builder(), "http://notification-service", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
