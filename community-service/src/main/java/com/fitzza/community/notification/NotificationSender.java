package com.fitzza.community.notification;

public interface NotificationSender {

    // 알림 전송에 실패해도 예외를 던지지 않는다. 알림 때문에 댓글 작성 같은 본 기능이 실패하면 안 된다.
    void send(NotificationEvent event);
}
