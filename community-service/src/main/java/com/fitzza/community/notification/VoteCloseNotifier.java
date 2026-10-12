package com.fitzza.community.notification;

import com.fitzza.community.domain.Post;
import com.fitzza.community.repository.PostRepository;
import org.springframework.stereotype.Component;

// 서버가 마감한 투표의 작성자에게 마감 알림을 보낸다. 실패하면 대기 표시가 남아 다음 실행에서 다시 보낸다.
@Component
public class VoteCloseNotifier {

    static final String NOTIFICATION_TYPE = "VOTE_CLOSED";
    static final String MESSAGE_PREFIX = "내 투표가 마감됐어요: ";

    private final PostRepository postRepository;
    private final NotificationSender notificationSender;

    public VoteCloseNotifier(PostRepository postRepository, NotificationSender notificationSender) {
        this.postRepository = postRepository;
        this.notificationSender = notificationSender;
    }

    // eventId를 글 ID로 정해 두어, 서버가 여러 대이거나 재시도로 두 번 보내도 알림은 한 번만 생긴다.
    public boolean notifyAuthor(Long postId) {
        Post post = postRepository.findByIdAndDeletedAtIsNull(postId).orElse(null);
        if (post == null || !post.isVoteCloseNotificationPending()) {
            return false;
        }
        boolean delivered = notificationSender.send(new NotificationEvent(
                "vote-closed-" + post.getId(),
                NOTIFICATION_TYPE,
                post.getUserId(),
                String.valueOf(post.getId()),
                MESSAGE_PREFIX + CommentNotificationListener.excerpt(post.getTitle())));
        if (delivered) {
            postRepository.markVoteCloseNotified(post.getId());
        }
        return delivered;
    }
}
