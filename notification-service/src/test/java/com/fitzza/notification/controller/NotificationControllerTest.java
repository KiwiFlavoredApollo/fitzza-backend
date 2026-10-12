package com.fitzza.notification.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.notification.domain.NotificationType;
import com.fitzza.notification.dto.NotificationPageResponse;
import com.fitzza.notification.dto.NotificationResponse;
import com.fitzza.notification.dto.UnreadCountResponse;
import com.fitzza.notification.exception.ErrorCode;
import com.fitzza.notification.exception.GlobalExceptionHandler;
import com.fitzza.notification.exception.NotificationApiException;
import com.fitzza.notification.service.NotificationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String NOTIFICATIONS = "/api/notifications";
    private static final String NOTIFICATION_ID = "0b0f6f0e-6f0c-4d51-9c35-6f5d2f1f7a11";

    private final NotificationService notificationService = mock(NotificationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationController(notificationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listUsesTheDefaultPageAndReturnsTheFieldsTheScreenNeeds() throws Exception {
        NotificationResponse notification = new NotificationResponse(
                NOTIFICATION_ID,
                NotificationType.COMMENT,
                "새 댓글이 달렸습니다.",
                "10",
                false,
                Instant.parse("2026-10-12T00:00:00Z"));
        when(notificationService.getNotifications(7L, 0, 20))
                .thenReturn(new NotificationPageResponse(List.of(notification), 0, 1));

        mockMvc.perform(get(NOTIFICATIONS).header(USER_ID_HEADER, "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].notificationId").value(NOTIFICATION_ID))
                .andExpect(jsonPath("$.content[0].type").value("COMMENT"))
                .andExpect(jsonPath("$.content[0].content").value("새 댓글이 달렸습니다."))
                .andExpect(jsonPath("$.content[0].targetId").value("10"))
                .andExpect(jsonPath("$.content[0].isRead").value(false))
                .andExpect(jsonPath("$.content[0].createdAt").exists());
    }

    @Test
    void listPassesTheRequestedPageAndSize() throws Exception {
        when(notificationService.getNotifications(7L, 2, 5))
                .thenReturn(new NotificationPageResponse(List.of(), 2, 3));

        mockMvc.perform(get(NOTIFICATIONS).header(USER_ID_HEADER, "7").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void listRequiresTheUserHeader() throws Exception {
        mockMvc.perform(get(NOTIFICATIONS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(notificationService);
    }

    @Test
    void listRejectsANonNumericPage() throws Exception {
        mockMvc.perform(get(NOTIFICATIONS).header(USER_ID_HEADER, "7").param("page", "first"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(notificationService);
    }

    @Test
    void unreadCountReturnsTheCount() throws Exception {
        when(notificationService.countUnread(7L)).thenReturn(new UnreadCountResponse(3));

        mockMvc.perform(get(NOTIFICATIONS + "/unread-count").header(USER_ID_HEADER, "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void unreadCountRequiresTheUserHeader() throws Exception {
        mockMvc.perform(get(NOTIFICATIONS + "/unread-count"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(notificationService);
    }

    @Test
    void markReadReturnsOkForTheOwner() throws Exception {
        mockMvc.perform(patch(NOTIFICATIONS + "/" + NOTIFICATION_ID + "/read").header(USER_ID_HEADER, "7"))
                .andExpect(status().isOk());

        verify(notificationService).markRead(7L, NOTIFICATION_ID);
    }

    @Test
    void markReadReturnsForbiddenForSomeoneElsesNotification() throws Exception {
        doThrow(new NotificationApiException(ErrorCode.NOT_OWNER))
                .when(notificationService)
                .markRead(8L, NOTIFICATION_ID);

        mockMvc.perform(patch(NOTIFICATIONS + "/" + NOTIFICATION_ID + "/read").header(USER_ID_HEADER, "8"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }

    @Test
    void markReadReturnsNotFoundForAnUnknownNotification() throws Exception {
        doThrow(new NotificationApiException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .when(notificationService)
                .markRead(7L, "missing");

        mockMvc.perform(patch(NOTIFICATIONS + "/missing/read").header(USER_ID_HEADER, "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }
}
