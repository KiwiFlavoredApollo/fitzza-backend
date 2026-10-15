package com.fitzza.notification.push;

import java.util.List;
import java.util.Set;

// 기기 토큰들로 푸시를 보낸다. Firebase 키가 있으면 FCM으로, 없으면 로그만 남기는 구현이 쓰인다.
public interface FcmSender {

    // 보낸 뒤 FCM이 더는 쓸 수 없다고 알려준 토큰(앱 삭제·만료 등)을 돌려준다. 호출한 쪽이 지운다.
    Set<String> send(List<String> tokens, PushMessage message);
}
