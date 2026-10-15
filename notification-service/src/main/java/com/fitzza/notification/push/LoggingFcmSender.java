package com.fitzza.notification.push;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// FIREBASE_CREDENTIALS가 없을 때 쓰인다. 키 없이도 서비스가 뜨고, 푸시를 보낼 시점이 맞는지 로그로 확인할 수 있다.
public class LoggingFcmSender implements FcmSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingFcmSender.class);

    @Override
    public Set<String> send(List<String> tokens, PushMessage message) {
        // 토큰 값은 남기지 않는다.
        log.info("Firebase 키가 없어 푸시를 보내지 않습니다. 기기 {}개, type={}", tokens.size(), message.data().get("type"));
        return Set.of();
    }
}
