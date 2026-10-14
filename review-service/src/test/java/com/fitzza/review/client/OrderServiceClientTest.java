package com.fitzza.review.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fitzza.review.client.OrderItems.OrderItem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class OrderServiceClientTest {

    private static final String BASE_URL = "http://order-service";
    private static final String SERVICE_TOKEN = "test-review-service-token";

    @Test
    void readsTheOrderItemForTheUserWithTheServiceToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrderServiceClient client = new OrderServiceClient(builder, BASE_URL, SERVICE_TOKEN);
        server.expect(requestTo(BASE_URL + "/order-items/31?userId=7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(OrderServiceClient.SERVICE_TOKEN_HEADER, SERVICE_TOKEN))
                .andRespond(withSuccess(
                        "{\"owned\":true,\"productId\":55,\"optionId\":501,\"orderStatus\":\"COMPLETED\",\"extra\":1}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.findOrderItem(31L, 7L)).contains(new OrderItem(true, 55L, 501L, "COMPLETED"));
        server.verify();
    }

    @Test
    void findsNothingForAMissingOrderItemAndPassesOtherFailuresOn() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrderServiceClient client = new OrderServiceClient(builder, BASE_URL, SERVICE_TOKEN);
        server.expect(requestTo(BASE_URL + "/order-items/404?userId=7")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE_URL + "/order-items/31?userId=7")).andRespond(withServerError());

        assertThat(client.findOrderItem(404L, 7L)).isEmpty();
        assertThatThrownBy(() -> client.findOrderItem(31L, 7L)).isInstanceOf(RestClientException.class);
        server.verify();
    }

    // 토큰이 없으면 주문 서비스가 어차피 401로 거절하므로 부르지 않는다.
    @Test
    void doesNotCallTheOrderServiceWithoutAToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrderServiceClient client = new OrderServiceClient(builder, BASE_URL, " ");

        assertThatThrownBy(() -> client.findOrderItem(31L, 7L)).isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
