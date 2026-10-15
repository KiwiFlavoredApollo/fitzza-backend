package com.fitzza.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FirebaseFcmSenderTest {

    private static final PushMessage MESSAGE =
            new PushMessage("FITZZA", "새 댓글이 달렸습니다.", Map.of("notificationId", "n-1", "type", "COMMENT"));

    private final FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
    private final FirebaseFcmSender sender = new FirebaseFcmSender(firebaseMessaging);

    @Test
    void onlyTokensFcmCallsUnusableAreReturned() throws Exception {
        BatchResponse batch = batchOf(
                success(),
                failure(MessagingErrorCode.UNREGISTERED),
                failure(MessagingErrorCode.SENDER_ID_MISMATCH),
                failure(MessagingErrorCode.INVALID_ARGUMENT),
                failure(MessagingErrorCode.UNAVAILABLE));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batch);

        var stale = sender.send(List.of("ok", "gone", "other-project", "bad-message", "busy"), MESSAGE);

        assertThat(stale).containsExactlyInAnyOrder("gone", "other-project");
    }

    @Test
    void moreTokensThanOneRequestAllowsAreSentInChunksAndMatchedToTheRightToken() throws Exception {
        int total = FirebaseFcmSender.MAX_TOKENS_PER_REQUEST + 1;
        List<String> tokens = IntStream.range(0, total).mapToObj(index -> "token-" + index).toList();
        List<SendResponse> firstChunk = new ArrayList<>();
        for (int index = 0; index < FirebaseFcmSender.MAX_TOKENS_PER_REQUEST; index++) {
            firstChunk.add(success());
        }
        BatchResponse first = mock(BatchResponse.class);
        when(first.getResponses()).thenReturn(firstChunk);
        BatchResponse second = batchOf(failure(MessagingErrorCode.UNREGISTERED));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(first, second);

        var stale = sender.send(tokens, MESSAGE);

        verify(firebaseMessaging, times(2)).sendEachForMulticast(any(MulticastMessage.class));
        assertThat(stale).containsExactly("token-" + FirebaseFcmSender.MAX_TOKENS_PER_REQUEST);
    }

    @Test
    void aWholeRequestFailureIsReportedToTheCaller() throws Exception {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNAVAILABLE);
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenThrow(exception);

        assertThatThrownBy(() -> sender.send(List.of("token-a"), MESSAGE))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(exception);
    }

    private static BatchResponse batchOf(SendResponse... responses) {
        BatchResponse batch = mock(BatchResponse.class);
        when(batch.getResponses()).thenReturn(List.of(responses));
        return batch;
    }

    private static SendResponse success() {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(true);
        return response;
    }

    private static SendResponse failure(MessagingErrorCode errorCode) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(errorCode);
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getException()).thenReturn(exception);
        return response;
    }
}
