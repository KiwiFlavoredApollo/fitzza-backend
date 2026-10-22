package com.fitzza.order.order.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.order.global.constant.ApiHeaders;
import com.fitzza.order.global.dto.PageResponse;
import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import com.fitzza.order.order.dto.OrderCreateRequest;
import com.fitzza.order.order.dto.OrderCreateResponse;
import com.fitzza.order.order.entity.OrderStatus;
import com.fitzza.order.order.service.OrderService;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OrderController.class, properties = "spring.cloud.config.enabled=false")
class OrderControllerTest {

    private static final String VALID_BODY = """
            {
              "cartIds": [1, 2],
              "recipientName": "홍길동",
              "recipientPhone": "010-1234-5678",
              "shippingAddress": "서울특별시 강남구 테헤란로 1"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    void createOrderReturnsCreated() throws Exception {
        when(orderService.createOrder(eq(1L), any(OrderCreateRequest.class))).thenReturn(
                new OrderCreateResponse(1L, "ORD-20261007-ABCDEF1234", 105000L, OrderStatus.COMPLETED));

        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.orderNum").value("ORD-20261007-ABCDEF1234"))
                .andExpect(jsonPath("$.orderStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.totalPrice").value(105000))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.recipient").doesNotExist());
    }

    @Test
    void createOrderRequiresUserHeader() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void createOrderRejectsEmptyCartIds() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("[1, 2]", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("cartIds")));
    }

    @Test
    void createOrderAcceptsManyCartIds() throws Exception {
        when(orderService.createOrder(eq(1L), any(OrderCreateRequest.class))).thenReturn(
                new OrderCreateResponse(1L, "ORD-20261007-ABCDEF1234", 105000L, OrderStatus.COMPLETED));
        String cartIds = LongStream.rangeClosed(1, 51).mapToObj(String::valueOf).collect(Collectors.joining(","));

        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("1, 2", cartIds)))
                .andExpect(status().isCreated());
    }

    @Test
    void createOrderRejectsInvalidPhoneNumber() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("010-1234-5678", "전화번호")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("recipientPhone")));
    }

    @Test
    void createOrderRejectsBlankRecipientAndAddress() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("홍길동", " ").replace("서울특별시 강남구 테헤란로 1", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("recipientName")))
                .andExpect(jsonPath("$.message").value(containsString("shippingAddress")));
    }

    @Test
    void createOrderMapsProductServiceOutageToServiceUnavailable() throws Exception {
        when(orderService.createOrder(eq(1L), any(OrderCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

        mockMvc.perform(post("/api/orders")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void getOrdersAppliesDefaultPaging() throws Exception {
        when(orderService.findOrders(1L, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 0));

        mockMvc.perform(get("/api/orders").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.hasNext").doesNotExist());
    }

    @Test
    void getOrdersRejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/api/orders").header(ApiHeaders.USER_ID, "1").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("size")));
    }

    @Test
    void getOrderReturnsForbiddenForAnotherUsersOrder() throws Exception {
        when(orderService.findOrder(1L, 9L)).thenThrow(new BusinessException(ErrorCode.ORDER_FORBIDDEN));

        mockMvc.perform(get("/api/orders/9").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_FORBIDDEN"));
    }

    @Test
    void getOrderReturnsNotFoundForMissingOrder() throws Exception {
        when(orderService.findOrder(1L, 9L)).thenThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/orders/9").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getOrderRejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/api/orders/abc").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isBadRequest());
    }
}
