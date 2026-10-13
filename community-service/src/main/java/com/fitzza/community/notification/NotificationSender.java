package com.fitzza.community.notification;

public interface NotificationSender {

    // 알림 전송에 실패해도 예외를 던지지 않는다. 알림 때문에 댓글 작성 같은 본 기능이 실패하면 안 된다.
    // 전달됐으면 true, 실패했으면 false를 돌려준다. 재시도가 필요한 쪽만 결과를 본다.
    boolean send(NotificationEvent event);
}
