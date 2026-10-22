package com.fitzza.notification.push;

import java.util.Map;

// 기기에 보낼 푸시 한 건. data는 FE가 알림을 눌렀을 때 이동할 화면을 정하는 데 쓴다(값은 모두 문자열).
public record PushMessage(String title, String body, Map<String, String> data) {

    public PushMessage {
        data = Map.copyOf(data);
    }
}
