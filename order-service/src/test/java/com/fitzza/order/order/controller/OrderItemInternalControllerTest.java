package com.fitzza.order.order.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.order.global.constant.ApiHeaders;
import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import com.fitzza.order.global.security.ServiceTokenSecurityConfig;
import com.fitzza.order.order.dto.OrderItemInternalResponse;
import com.fitzza.order.order.entity.OrderStatus;
import com.fitzza.order.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = OrderItemInternalController.class,
        properties = {
                "spring.cloud.config.enabled=false",
                "fitzza.service-tokens.review=test-only-review-service-token-32bytes"
        })
@Import(ServiceTokenSecurityConfig.class)
class OrderItemInternalControllerTest {

    private static final String REVIEW_TOKEN = "test-only-review-service-token-32bytes";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    void getOrderItemReturnsOwnedItem() throws Exception {
        when(orderService.findOrderItem(31L, 7L))
                .thenReturn(new OrderItemInternalResponse(true, 55L, 501L, OrderStatus.COMPLETED));

        mockMvc.perform(get("/order-items/31")
                        .param("userId", "7")
                        .header(ApiHeaders.SERVICE_TOKEN, REVIEW_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owned").value(true))
                .andExpect(jsonPath("$.productId").value(55))
                .andExpect(jsonPath("$.optionId").value(501))
                .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));
    }

    @Test
    void getOrderItemRequiresReviewServiceToken() throws Exception {
        mockMvc.perform(get("/order-items/31").param("userId", "7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void getOrderItemRequiresUserId() throws Exception {
        mockMvc.perform(get("/order-items/31").header(ApiHeaders.SERVICE_TOKEN, REVIEW_TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrderItemMapsMissingItemToNotFound() throws Exception {
        when(orderService.findOrderItem(404L, 7L)).thenThrow(new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND));

        mockMvc.perform(get("/order-items/404")
                        .param("userId", "7")
                        .header(ApiHeaders.SERVICE_TOKEN, REVIEW_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_ITEM_NOT_FOUND"));
    }
}
