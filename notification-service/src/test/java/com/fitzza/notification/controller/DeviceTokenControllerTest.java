package com.fitzza.notification.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.notification.exception.GlobalExceptionHandler;
import com.fitzza.notification.service.DeviceTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeviceTokenControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String TOKENS = "/api/notifications/tokens";

    private final DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceTokenController(deviceTokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void registerStoresTheTokenForTheLoggedInUser() throws Exception {
        mockMvc.perform(post(TOKENS)
                        .header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token-a\"}"))
                .andExpect(status().isNoContent());

        verify(deviceTokenService).register(7L, "token-a");
    }

    @Test
    void unregisterRemovesTheTokenForTheLoggedInUser() throws Exception {
        mockMvc.perform(delete(TOKENS)
                        .header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token-a\"}"))
                .andExpect(status().isNoContent());

        verify(deviceTokenService).unregister(7L, "token-a");
    }

    @Test
    void aBlankTokenIsRejected() throws Exception {
        mockMvc.perform(post(TOKENS)
                        .header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(deviceTokenService);
    }

    @Test
    void aTokenLongerThanTheColumnIsRejected() throws Exception {
        String tooLong = "a".repeat(513);

        mockMvc.perform(post(TOKENS)
                        .header(USER_ID_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(deviceTokenService);
    }

    @Test
    void requestsWithoutALoggedInUserAreRejected() throws Exception {
        mockMvc.perform(post(TOKENS).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"token-a\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(deviceTokenService);
    }
}
