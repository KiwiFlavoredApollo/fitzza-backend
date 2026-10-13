package com.fitzza.order;

import static com.fitzza.order.support.TestFixtures.option;
import static org.hamcrest.Matchers.hasSize;
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
                .andExpect(jsonPath("$.paths['/api/orders']").doesNotExist());
    }

    @Test
    void cartFlowSumsQuantityMergesOptionsAndTotalsAvailableItems() throws Exception {
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
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_AVAILABLE"));
        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":99,\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_FOUND"));

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].cartId").value(secondCartId))
                .andExpect(jsonPath("$.totalQuantity").value(6))
                .andExpect(jsonPath("$.totalPrice").value(5 * 30000 + 45000));

        mockMvc.perform(patch("/api/carts/" + firstCartId)
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":11,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartId").value(secondCartId))
                .andExpect(jsonPath("$.quantity").value(3));
        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPrice").value(3 * 45000));

        mockMvc.perform(delete("/api/carts/" + secondCartId).header(ApiHeaders.USER_ID, OTHER_USER_ID))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/carts/" + secondCartId).header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalPrice").value(0));
    }

    @Test
    void cartViewMarksItemsAsUnavailableWhenOptionStopsSelling() throws Exception {
        addCart(10, 1);
        productOptions.put(10L, option(10L, 30000, "SOLD_OUT"));

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.totalQuantity").value(0))
                .andExpect(jsonPath("$.totalPrice").value(0));
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
}
