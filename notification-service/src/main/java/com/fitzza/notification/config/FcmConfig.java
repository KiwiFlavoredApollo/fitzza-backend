package com.fitzza.notification.config;

import com.fitzza.notification.push.FcmSender;
import com.fitzza.notification.push.FirebaseFcmSender;
import com.fitzza.notification.push.LoggingFcmSender;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// FIREBASE_CREDENTIALS(서비스 계정 키)가 있을 때만 실제 FCM으로 보낸다. 없으면 로그만 남겨 키 없이도 서비스가 뜬다.
@Configuration
public class FcmConfig {

    static final String FIREBASE_APP_NAME = "fitzza-notification";

    @Bean
    public FcmSender fcmSender(@Value("${fitzza.fcm.credentials:}") String credentials) throws IOException {
        if (credentials == null || credentials.isBlank()) {
            return new LoggingFcmSender();
        }
        GoogleCredentials googleCredentials =
                GoogleCredentials.fromStream(new ByteArrayInputStream(decodeCredentials(credentials)));
        FirebaseApp app = FirebaseApp.initializeApp(
                FirebaseOptions.builder().setCredentials(googleCredentials).build(), FIREBASE_APP_NAME);
        return new FirebaseFcmSender(FirebaseMessaging.getInstance(app));
    }

    // .env 한 줄에 JSON을 넣기 어려워 base64 값을 기본으로 받는다. 배포 비밀 저장소에는 JSON을 그대로 넣어도 된다.
    // 키 내용이 로그에 남지 않도록 오류 메시지에 값을 넣지 않는다.
    static byte[] decodeCredentials(String credentials) {
        String trimmed = credentials.trim();
        if (trimmed.startsWith("{")) {
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("FIREBASE_CREDENTIALS는 서비스 계정 키 JSON 또는 그 base64 값이어야 합니다.");
        }
    }
}
