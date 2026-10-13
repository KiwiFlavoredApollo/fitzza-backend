package com.fitzza.user.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.user.dto.NicknameResponse;
import com.fitzza.user.exception.ErrorCode;
import com.fitzza.user.exception.UserApiException;
import com.fitzza.user.security.ServiceTokenSecurityConfig;
import com.fitzza.user.service.ProfileService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InternalUserController.class, properties = {
    "fitzza.service-tokens.community=" + InternalUserControllerTest.COMMUNITY_TOKEN,
    "fitzza.service-tokens.recommendation=" + InternalUserControllerTest.RECOMMENDATION_TOKEN,
    "spring.cloud.config.enabled=false"
})
class InternalUserControllerTest {

    static final String COMMUNITY_TOKEN = "test-only-community-service-token-32b";
    static final String RECOMMENDATION_TOKEN = "test-only-recommendation-token-32bytes";
    private static final String SERVICE_TOKEN = "X-Service-Token";

    @MockBean
    private ProfileService profileService;

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/users", "/users/7/body", "/users/7/body;ignored=value"})
    void missingServiceTokenIsDeniedEvenWithUserIdentityHeaders(String path) throws Exception {
        mockMvc.perform(get(path).param("userIds", "7,8")
                        .header("X-User-Id", "7")
                        .header("Authorization", "Bearer external-user-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(profileService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "wrong-token"})
    void unknownServiceTokensAreDenied(String token) throws Exception {
        for (String path : List.of("/users", "/users/7/body")) {
            mockMvc.perform(get(path).param("userIds", "7,8").header(SERVICE_TOKEN, token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(profileService);
    }

    // community 토큰으로는 닉네임만, recommendation 토큰으로는 신체 정보만 볼 수 있다.
    @Test
    void eachServiceTokenOpensOnlyTheApisAllowedForThatService() throws Exception {
        mockMvc.perform(get("/users/7/body").header(SERVICE_TOKEN, COMMUNITY_TOKEN))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/users").param("userIds", "7").header(SERVICE_TOKEN, RECOMMENDATION_TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profileService);
    }

    @Test
    void authenticationRunsBeforeRequestParameterValidation() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(profileService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "too-short"})
    void missingBlankOrShortCommunityTokenIsRejected(String token) {
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(token, RECOMMENDATION_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recommendationTokenIsOptionalButMustDifferFromCommunity() {
        new ServiceTokenSecurityConfig(COMMUNITY_TOKEN, "");
        assertThatThrownBy(() -> new ServiceTokenSecurityConfig(COMMUNITY_TOKEN, COMMUNITY_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nicknamesAreLookedUpFromCommaSeparatedIds() throws Exception {
        when(profileService.findNicknames(List.of(7L, 8L)))
                .thenReturn(List.of(new NicknameResponse(7L, "fitzza"), new NicknameResponse(8L, "newbie")));

        mockMvc.perform(get("/users").param("userIds", "7,8").header(SERVICE_TOKEN, COMMUNITY_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(7))
                .andExpect(jsonPath("$[0].nickname").value("fitzza"))
                .andExpect(jsonPath("$[1].nickname").value("newbie"));
    }

    @Test
    void nicknameLookupRejectsNonNumericId() throws Exception {
        mockMvc.perform(get("/users").param("userIds", "7,abc").header(SERVICE_TOKEN, COMMUNITY_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void bodyLookupReturnsOnlyFilledFields() throws Exception {
        when(profileService.getFilledBodyFields(7L)).thenReturn(Map.of("height", 172.5f));

        mockMvc.perform(get("/users/7/body").header(SERVICE_TOKEN, RECOMMENDATION_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.height").value(172.5))
                .andExpect(jsonPath("$.weight").doesNotExist());
    }

    @Test
    void bodyLookupReturnsNotFoundForUnknownUser() throws Exception {
        when(profileService.getFilledBodyFields(999L)).thenThrow(new UserApiException(ErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/users/999/body").header(SERVICE_TOKEN, RECOMMENDATION_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
