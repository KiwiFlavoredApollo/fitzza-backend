package com.fitzza.order;

import static com.fitzza.order.support.TestFixtures.feignException;
import static com.fitzza.order.support.TestFixtures.option;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.order.global.client.ProductFeignClient;
import com.fitzza.order.global.client.ProductOptionResponse;
import com.fitzza.order.global.constant.ApiHeaders;
import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@Transactional
class OrderServiceIntegrationTest {

    private static final String USER_ID = "1";
    private static final String OTHER_USER_ID = "2";
    private static final String ORDER_BODY = """
            {
              "cartIds": [%d, %d],
              "recipientName": "홍길동",
              "recipientPhone": "010-1234-5678",
              "shippingAddress": "서울특별시 강남구 테헤란로 1"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductFeignClient productFeignClient;

    private final Map<Long, ProductOptionResponse> productOptions = new HashMap<>();

    @BeforeEach
    void setUp() {
        productOptions.put(10L, option(10L, 30000, "ON_SALE"));
        productOptions.put(11L, option(11L, 45000, "ON_SALE"));
        productOptions.put(12L, option(12L, 10000, "SOLD_OUT"));
        when(productFeignClient.getOptions(anyList())).thenAnswer(call -> {
            List<Long> requestedIds = call.getArgument(0);
            return requestedIds.stream().filter(productOptions::containsKey).map(productOptions::get).toList();
        });
    }

    @Test
    void openApiDocumentExposesEveryEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/carts'].get").exists())
                .andExpect(jsonPath("$.paths['/api/carts'].post").exists())
                .andExpect(jsonPath("$.paths['/api/carts/{cartId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/carts/{cartId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/carts'].get.parameters[?(@.name=='X-User-Id')]", hasSize(1)))
                .andExpect(jsonPath("$.paths['/api/carts'].post.parameters[?(@.name=='X-User-Id')]", hasSize(1)))
                .andExpect(jsonPath("$.paths['/api/carts/{cartId}'].patch.parameters[?(@.name=='X-User-Id')]", hasSize(1)))
                .andExpect(jsonPath("$.paths['/api/carts/{cartId}'].delete.parameters[?(@.name=='X-User-Id')]", hasSize(1)))
                .andExpect(jsonPath("$.paths['/api/carts'].get.parameters[?(@.name=='X-Service-Token')]", hasSize(1)))
                .andExpect(jsonPath("$.components.securitySchemes.serviceToken.name").value("X-Service-Token"))
                .andExpect(jsonPath("$.paths['/api/orders'].get").exists())
                .andExpect(jsonPath("$.paths['/api/orders'].post").exists())
                .andExpect(jsonPath("$.paths['/api/orders/{orderId}']").exists());
    }

    @Test
    void cartFlowSumsQuantityAndMergesOptions() throws Exception {
        long firstCartId = addCart(10, 2);
        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":10,\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cartId").value(firstCartId))
                .andExpect(jsonPath("$.quantity").value(5));
        long secondCartId = addCart(11, 1);

        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":12,\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_AVAILABLE"));
        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":99,\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_FOUND"));

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cartId").value(secondCartId))
                .andExpect(jsonPath("$[0].quantity").value(1))
                .andExpect(jsonPath("$[1].quantity").value(5))
                .andExpect(jsonPath("$[1].linePrice").value(5 * 30000));

        mockMvc.perform(patch("/api/carts/" + firstCartId)
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":11,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value(secondCartId))
                .andExpect(jsonPath("$.quantity").value(3));
        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].quantity").value(3))
                .andExpect(jsonPath("$[0].linePrice").value(3 * 45000));

        mockMvc.perform(delete("/api/carts/" + secondCartId).header(ApiHeaders.USER_ID, OTHER_USER_ID))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/carts/999999").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/carts/" + secondCartId).header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cartViewMarksItemsAsUnavailableWhenOptionStopsSelling() throws Exception {
        addCart(10, 1);
        productOptions.put(10L, option(10L, 30000, "SOLD_OUT"));

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[0].linePrice").value(0));
    }

    @Test
    void orderFlowSnapshotsPricesClearsCartAndKeepsHistory() throws Exception {
        long firstCartId = addCart(10, 2);
        long secondCartId = addCart(11, 1);

        String response = mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY.formatted(firstCartId, secondCartId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNumber())
                .andExpect(jsonPath("$.orderNum").value(startsWith("ORD-")))
                .andExpect(jsonPath("$.orderStatus").value("PENDING"))
                .andExpect(jsonPath("$.totalPrice").value(2 * 30000 + 45000))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.recipient").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        int orderId = JsonPath.read(response, "$.orderId");

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(jsonPath("$.length()").value(0));

        productOptions.put(10L, option(10L, 99999, "ON_SALE"));
        mockMvc.perform(get("/api/orders/" + orderId).header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrice").value(2 * 30000 + 45000))
                .andExpect(jsonPath("$.recipient.name").value("홍길동"))
                .andExpect(jsonPath("$.recipient.phone").value("010-1234-5678"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(30000))
                .andExpect(jsonPath("$.items[0].productName").value("상품 10"))
                .andExpect(jsonPath("$.items[0].linePrice").doesNotExist())
                .andExpect(jsonPath("$.items[0].optionId").doesNotExist());

        mockMvc.perform(get("/api/orders").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.content[0].orderId").value(orderId))
                .andExpect(jsonPath("$.content[0].itemCount").value(2))
                .andExpect(jsonPath("$.content[0].thumbnailUrl").value("https://cdn.example/10.jpg"));
        mockMvc.perform(get("/api/orders/" + orderId).header(ApiHeaders.USER_ID, OTHER_USER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_FORBIDDEN"));
    }

    @Test
    void orderListIsSortedNewestFirst() throws Exception {
        long firstOrderId = placeOrderFor(addCart(10, 1), addCart(11, 1));
        long secondOrderId = placeOrderFor(addCart(10, 1), addCart(11, 1));

        mockMvc.perform(get("/api/orders").header(ApiHeaders.USER_ID, USER_ID).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.content[0].orderId").value(secondOrderId));
        mockMvc.perform(get("/api/orders").header(ApiHeaders.USER_ID, USER_ID).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(firstOrderId));
    }

    @Test
    void orderCreationRejectsCartsOfAnotherUser() throws Exception {
        long firstCartId = addCart(10, 1);
        long secondCartId = addCart(11, 1);

        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, OTHER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY.formatted(firstCartId, secondCartId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"));
    }

    @Test
    void orderCreationFailsAndKeepsCartWhenOptionIsNoLongerOnSale() throws Exception {
        long firstCartId = addCart(10, 1);
        long secondCartId = addCart(11, 1);
        productOptions.put(11L, option(11L, 45000, "SOLD_OUT"));

        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY.formatted(firstCartId, secondCartId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_AVAILABLE"));

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/orders").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void orderCreationFailsWithServiceUnavailableWhenProductServiceIsDown() throws Exception {
        long firstCartId = addCart(10, 1);
        long secondCartId = addCart(11, 1);
        when(productFeignClient.getOptions(anyList())).thenThrow(feignException(503));

        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY.formatted(firstCartId, secondCartId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void orderDetailStillReturnsSnapshotWhenProductServiceIsDown() throws Exception {
        long orderId = placeOrderFor(addCart(10, 2), addCart(11, 1));
        when(productFeignClient.getOptions(anyList())).thenThrow(feignException(503));

        mockMvc.perform(get("/api/orders/" + orderId).header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(30000))
                .andExpect(jsonPath("$.items[0].productName").value(nullValue()));
    }

    private long addCart(long optionId, int quantity) throws Exception {
        String response = mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":" + optionId + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.cartId")).longValue();
    }

    private long placeOrderFor(long firstCartId, long secondCartId) throws Exception {
        String response = mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY.formatted(firstCartId, secondCartId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.orderId")).longValue();
    }
}
