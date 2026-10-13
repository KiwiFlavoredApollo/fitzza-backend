package com.fitzza.product.worldcup.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.product.global.constant.ApiHeaders;
import com.fitzza.product.support.ServiceTokenFixture;
import com.fitzza.product.global.exception.BusinessException;
import com.fitzza.product.global.exception.ErrorCode;
import com.fitzza.product.product.dto.ProductSummaryResponse;
import com.fitzza.product.product.entity.ProductStatus;
import com.fitzza.product.worldcup.dto.WorldcupCreateRequest;
import com.fitzza.product.worldcup.dto.WorldcupInternalResponse;
import com.fitzza.product.worldcup.dto.WorldcupResultResponse;
import com.fitzza.product.worldcup.service.WorldcupService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = {WorldcupController.class, WorldcupInternalController.class},
        properties = "spring.cloud.config.enabled=false")
class WorldcupControllerTest {

    private static final ProductSummaryResponse WINNER =
            new ProductSummaryResponse(1L, "Fitzza", "셔츠", 30000, null, ProductStatus.ON_SALE);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorldcupService worldcupService;

    @Test
    void saveWorldcupReturnsCreated() throws Exception {
        when(worldcupService.saveWorldcup(eq(1L), any(WorldcupCreateRequest.class)))
                .thenReturn(new WorldcupResultResponse(10L, WINNER, LocalDateTime.of(2026, 10, 7, 12, 0)));

        mockMvc.perform(post("/api/worldcups")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winnerProductId\":1,\"candidateProductIds\":[1,2]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.worldcupHistoryId").value(10))
                .andExpect(jsonPath("$.winnerProduct.productName").value("셔츠"));
    }

    @Test
    void saveWorldcupRejectsSingleCandidate() throws Exception {
        mockMvc.perform(post("/api/worldcups")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winnerProductId\":1,\"candidateProductIds\":[1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("candidateProductIds")));
    }

    @Test
    void saveWorldcupRequiresWinnerProductId() throws Exception {
        mockMvc.perform(post("/api/worldcups")
                        .header(ApiHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProductIds\":[1,2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("winnerProductId")));
    }

    @Test
    void getLatestWorldcupReturnsNotFoundWithoutHistory() throws Exception {
        when(worldcupService.findLatestWorldcup(1L)).thenThrow(new BusinessException(ErrorCode.WORLDCUP_NOT_FOUND));

        mockMvc.perform(get("/api/worldcups/latest").header(ApiHeaders.USER_ID, "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORLDCUP_NOT_FOUND"));
    }

    @Test
    void getWorldcupHistoriesRejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/worldcups/history").header(ApiHeaders.USER_ID, "1").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getWorldcupReturnsOwnershipForInternalCalls() throws Exception {
        when(worldcupService.findWorldcup(10L, 2L)).thenReturn(WorldcupInternalResponse.notOwned(10L));

        mockMvc.perform(ServiceTokenFixture.community(get("/worldcups/10")).param("userId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owned").value(false))
                .andExpect(jsonPath("$.winnerProduct").doesNotExist());
    }

    @Test
    void getWorldcupRequiresUserId() throws Exception {
        mockMvc.perform(ServiceTokenFixture.community(get("/worldcups/10")))
                .andExpect(status().isBadRequest());
    }
}
