package com.fitzza.order.cart.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.order.cart.dto.CartCreateRequest;
import com.fitzza.order.cart.dto.CartItemResponse;
import com.fitzza.order.cart.dto.CartResponse;
import com.fitzza.order.cart.dto.CartUpdateRequest;
import com.fitzza.order.cart.service.CartService;
import com.fitzza.order.global.constant.ApiHeaders;
import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CartController.class, properties = "spring.cloud.config.enabled=false")
class CartControllerTest {

    private static final CartItemResponse ITEM = new CartItemResponse(
            1L, 10L, 2, true, 1L, "Fitzza", "셔츠", "BLACK", "M", null, 30000, 60000L);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @Test
    void getCartRequiresUserHeader() throws Exception {
        mockMvc.perform(get("/api/carts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getCartReturnsItemsWithTotals() throws Exception {
        when(cartService.findCart(1L)).thenReturn(CartResponse.from(List.of(ITEM)));

        mockMvc.perform(get("/api/carts").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("셔츠"))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.totalPrice").value(60000));
    }

    @Test
    void addCartReturnsCreated() throws Exception {
        when(cartService.addCart(eq(1L), any(CartCreateRequest.class))).thenReturn(ITEM);

        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":10,\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cartId").value(1));
    }

    @Test
    void addCartDefaultsQuantityToOne() throws Exception {
        when(cartService.addCart(eq(1L), any(CartCreateRequest.class))).thenReturn(ITEM);

        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":10}"))
                .andExpect(status().isCreated());

        verify(cartService).addCart(1L, new CartCreateRequest(10L, 1));
    }

    @Test
    void addCartRejectsMissingOptionId() throws Exception {
        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("optionId")));
    }

    @Test
    void addCartAcceptsQuantityAboveTheFormerLimit() throws Exception {
        when(cartService.addCart(eq(1L), any(CartCreateRequest.class))).thenReturn(ITEM);

        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":10,\"quantity\":100}"))
                .andExpect(status().isCreated());
    }

    @Test
    void addCartMapsUnavailableOptionToConflict() throws Exception {
        when(cartService.addCart(eq(1L), any(CartCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.OPTION_NOT_AVAILABLE));

        mockMvc.perform(post("/api/carts")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":10,\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTION_NOT_AVAILABLE"));
    }

    @Test
    void updateCartReturnsUpdatedItem() throws Exception {
        when(cartService.updateCart(eq(1L), eq(1L), any(CartUpdateRequest.class))).thenReturn(ITEM);

        mockMvc.perform(patch("/api/carts/1")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":10,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2));
    }

    @Test
    void updateCartRejectsZeroQuantity() throws Exception {
        mockMvc.perform(patch("/api/carts/1")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":10,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("quantity")));
    }

    @Test
    void removeCartReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/carts/1").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isNoContent());

        verify(cartService).removeCart(1L, 1L);
    }

    @Test
    void removeCartReturnsNotFoundForMissingItem() throws Exception {
        doThrow(new BusinessException(ErrorCode.CART_NOT_FOUND)).when(cartService).removeCart(1L, 9L);

        mockMvc.perform(delete("/api/carts/9").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"));
    }

    @Test
    void removeCartRejectsNonNumericId() throws Exception {
        mockMvc.perform(delete("/api/carts/abc").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
