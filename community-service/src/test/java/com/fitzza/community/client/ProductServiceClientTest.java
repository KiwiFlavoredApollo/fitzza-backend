package com.fitzza.community.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fitzza.community.client.ProductCatalog.ProductSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class ProductServiceClientTest {

    private static final String BASE_URL = "http://product-service";
    private static final String INTERNAL_TOKEN = "test-internal-token";

    private MockRestServiceServer server;
    private ProductServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProductServiceClient(builder, BASE_URL, INTERNAL_TOKEN);
    }

    @Test
    void asksTheProductServiceWhetherTheUserWishlistedTheProduct() {
        server.expect(requestTo(BASE_URL + "/internal/wishlists/check?userId=7&itemId=55"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(ProductServiceClient.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andRespond(withSuccess("{\"wishlisted\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/wishlists/check?userId=8&itemId=55"))
                .andRespond(withSuccess("{\"wishlisted\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.isWishlisted(7L, 55L)).isTrue();
        assertThat(client.isWishlisted(8L, 55L)).isFalse();
        server.verify();
    }

    @Test
    void readsTheProductSummaryAndIgnoresFieldsItDoesNotUse() {
        server.expect(requestTo(BASE_URL + "/internal/products?productIds=55"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(ProductServiceClient.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andRespond(withSuccess(
                        "[{\"productId\":55,\"brandName\":\"Fitzza\",\"productName\":\"오버핏 셔츠\","
                                + "\"price\":39000,\"imageUrl\":\"https://cdn.example.com/55.png\",\"status\":\"ON_SALE\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.findProduct(55L))
                .contains(new ProductSummary(55L, "오버핏 셔츠", 39000, "https://cdn.example.com/55.png", "ON_SALE"));
        server.verify();
    }

    @Test
    void findsNothingWhenTheProductDoesNotExist() {
        server.expect(requestTo(BASE_URL + "/internal/products?productIds=404"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findProduct(404L)).isEmpty();
        server.verify();
    }

    @Test
    void passesTheFailureOnWhenTheProductServiceIsDown() {
        server.expect(requestTo(BASE_URL + "/internal/wishlists/check?userId=7&itemId=55"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.isWishlisted(7L, 55L)).isInstanceOf(RestClientException.class);
        server.verify();
    }
}
