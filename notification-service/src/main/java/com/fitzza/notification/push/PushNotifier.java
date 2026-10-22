package com.fitzza.notification.push;

import com.fitzza.notification.service.DeviceTokenService;
import com.fitzza.notification.service.NotificationSavedEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PushNotifier {

    static final String TITLE = "FITZZA";

    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

    private final DeviceTokenService deviceTokenService;
    private final FcmSender fcmSender;

    public PushNotifier(DeviceTokenService deviceTokenService, FcmSender fcmSender) {
        this.deviceTokenService = deviceTokenService;
        this.fcmSender = fcmSender;
    }

    // 알림 저장이 확정된 뒤에만 보내고, 이벤트를 보낸 서비스가 FCM 응답을 기다리지 않도록 별도 스레드에서 보낸다.
    // 푸시는 부가 기능이라 실패해도 알림은 저장된 그대로 두고 알림 목록에서 볼 수 있다.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationSaved(NotificationSavedEvent event) {
        try {
            List<String> tokens = deviceTokenService.findTokens(event.userId());
            if (tokens.isEmpty()) {
                return;
            }
            Set<String> staleTokens = fcmSender.send(tokens, toMessage(event));
            deviceTokenService.removeTokens(staleTokens);
        } catch (RuntimeException exception) {
            log.warn("푸시를 보내지 못했습니다. 알림은 저장된 상태로 둡니다. notificationId={}: {}",
                    event.notificationId(), exception.getMessage());
        }
    }

    static PushMessage toMessage(NotificationSavedEvent event) {
        Map<String, String> data = new HashMap<>();
        data.put("notificationId", event.notificationId());
        data.put("type", event.type().name());
        if (!event.targetId().isEmpty()) {
            data.put("targetId", event.targetId());
        }
        return new PushMessage(TITLE, event.content(), data);
    }
}
