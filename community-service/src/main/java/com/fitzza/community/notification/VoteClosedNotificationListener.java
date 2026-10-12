package com.fitzza.community.notification;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class VoteClosedNotificationListener {

    static final String NOTIFICATION_TYPE = "VOTE_CLOSED";
    static final String MESSAGE_PREFIX = "내 투표가 마감됐어요: ";

    private final NotificationSender notificationSender;

    public VoteClosedNotificationListener(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    // 마감이 DB에 확정된 뒤에만 보낸다. eventId를 글 ID로 정해 두어 서버가 여러 대라 두 번 보내도 알림은 한 번만 생긴다.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVoteClosed(VoteClosedEvent event) {
        notificationSender.send(new NotificationEvent(
                "vote-closed-" + event.postId(),
                NOTIFICATION_TYPE,
                event.postAuthorId(),
                String.valueOf(event.postId()),
                MESSAGE_PREFIX + CommentNotificationListener.excerpt(event.title())));
    }
}
