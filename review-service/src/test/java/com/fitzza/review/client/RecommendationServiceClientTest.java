package com.fitzza.review.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fitzza.review.client.SizeFeedbacks.SizeFeedbackRequest;
import com.fitzza.review.domain.SizeFeedback;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class RecommendationServiceClientTest {

    private static final String BASE_URL = "http://recommendation-service:8000";
    private static final String SERVICE_TOKEN = "test-review-service-token";
    private static final SizeFeedbackRequest REQUEST = new SizeFeedbackRequest(7L, 30L, 3L, SizeFeedback.BIG);

    @Test
    void postsTheSurveyResultWithTheServiceToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecommendationServiceClient client = new RecommendationServiceClient(builder, BASE_URL, SERVICE_TOKEN);
        server.expect(requestTo(BASE_URL + RecommendationServiceClient.SIZE_FEEDBACK_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(RecommendationServiceClient.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andExpect(content().json(
                        "{\"userId\":7,\"productId\":30,\"optionId\":3,\"sizeFeedback\":\"BIG\"}", true))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(client.isEnabled()).isTrue();
        client.send(REQUEST);
        server.verify();
    }

    @Test
    void aFailedResponseIsPassedToTheCaller() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecommendationServiceClient client = new RecommendationServiceClient(builder, BASE_URL, SERVICE_TOKEN);
        server.expect(requestTo(BASE_URL + RecommendationServiceClient.SIZE_FEEDBACK_PATH))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.send(REQUEST)).isInstanceOf(RestClientException.class);
        server.verify();
    }

    @Test
    void isDisabledWithoutAnAddressOrAToken() {
        assertThat(new RecommendationServiceClient(RestClient.builder(), "", SERVICE_TOKEN).isEnabled()).isFalse();
        assertThat(new RecommendationServiceClient(RestClient.builder(), BASE_URL, " ").isEnabled()).isFalse();
    }
}
