package com.fitzza.notification.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FirebaseFcmSender implements FcmSender {

    // FCM이 한 번에 받는 토큰 수의 상한.
    static final int MAX_TOKENS_PER_REQUEST = 500;

    // 토큰 자체가 더는 쓸 수 없다는 뜻의 오류만 지운다.
    // INVALID_ARGUMENT는 메시지 내용이 잘못돼도 나오므로, 이걸로 지우면 멀쩡한 토큰까지 모두 사라질 수 있어 제외한다.
    private static final Set<MessagingErrorCode> STALE_TOKEN_ERRORS =
            Set.of(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.SENDER_ID_MISMATCH);

    private final FirebaseMessaging firebaseMessaging;

    public FirebaseFcmSender(FirebaseMessaging firebaseMessaging) {
        this.firebaseMessaging = firebaseMessaging;
    }

    @Override
    public Set<String> send(List<String> tokens, PushMessage message) {
        Set<String> staleTokens = new HashSet<>();
        for (int start = 0; start < tokens.size(); start += MAX_TOKENS_PER_REQUEST) {
            List<String> chunk = tokens.subList(start, Math.min(start + MAX_TOKENS_PER_REQUEST, tokens.size()));
            staleTokens.addAll(sendChunk(chunk, message));
        }
        return staleTokens;
    }

    private Set<String> sendChunk(List<String> tokens, PushMessage message) {
        BatchResponse batch;
        try {
            batch = firebaseMessaging.sendEachForMulticast(toMulticast(tokens, message));
        } catch (FirebaseMessagingException exception) {
            throw new IllegalStateException("FCM 전송 요청이 실패했습니다: " + exception.getMessagingErrorCode(), exception);
        }
        // 응답은 보낸 토큰과 같은 순서로 온다.
        List<SendResponse> responses = batch.getResponses();
        Set<String> staleTokens = new HashSet<>();
        for (int index = 0; index < responses.size(); index++) {
            if (isStaleToken(responses.get(index))) {
                staleTokens.add(tokens.get(index));
            }
        }
        return staleTokens;
    }

    private static boolean isStaleToken(SendResponse response) {
        if (response.isSuccessful() || response.getException() == null) {
            return false;
        }
        return STALE_TOKEN_ERRORS.contains(response.getException().getMessagingErrorCode());
    }

    private static MulticastMessage toMulticast(List<String> tokens, PushMessage message) {
        return MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build())
                .putAllData(message.data())
                .build();
    }
}
