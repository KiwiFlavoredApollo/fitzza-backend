package com.fitzza.community.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fitzza.community.client.ComboResults.ComboResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class RecommendationServiceClientTest {

    private static final String BASE_URL = "http://recommendation-service:8000";
    private static final String SERVICE_TOKEN = "test-community-service-token";

    @Test
    void readsTheComboForTheUserWithTheServiceToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecommendationServiceClient client = new RecommendationServiceClient(builder, BASE_URL, SERVICE_TOKEN);
        server.expect(requestTo(BASE_URL + "/recommendations/combos/31?userId=7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RecommendationServiceClient.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andRespond(withSuccess(
                        "{\"owned\":true,\"items\":[{\"productId\":1,\"productName\":\"셔츠\",\"price\":39000,"
                                + "\"imageUrl\":\"https://cdn.example.com/1.png\",\"color\":\"white\"}],"
                                + "\"totalPrice\":39000,\"feedbackSummary\":\"잘 어울려요\",\"extra\":1}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.findCombo(31L, 7L)).contains(new ComboResult(
                true,
                List.of(new ResultProduct(1L, "셔츠", 39000, "https://cdn.example.com/1.png")),
                39000,
                "잘 어울려요"));
        server.verify();
    }

    @Test
    void findsNothingForAMissingComboAndPassesOtherFailuresOn() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecommendationServiceClient client = new RecommendationServiceClient(builder, BASE_URL, SERVICE_TOKEN);
        server.expect(requestTo(BASE_URL + "/recommendations/combos/404?userId=7"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE_URL + "/recommendations/combos/31?userId=7")).andRespond(withServerError());

        assertThat(client.findCombo(404L, 7L)).isEmpty();
        assertThatThrownBy(() -> client.findCombo(31L, 7L)).isInstanceOf(RestClientException.class);
        server.verify();
    }

    // 주소가 없으면 부르지 않고 일시 장애와 같이 다뤄, 호출한 쪽이 503으로 거절하게 한다.
    @Test
    void doesNotCallAnythingUntilTheAddressIsConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RecommendationServiceClient client = new RecommendationServiceClient(builder, " ", SERVICE_TOKEN);

        assertThatThrownBy(() -> client.findCombo(31L, 7L)).isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
