package com.fitzza.community.client;

import com.fitzza.community.domain.SharedItem;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import java.util.List;

// 공유 대상이 요청한 사용자 것인지 확인하고, 글에 저장할 스냅샷을 만든다.
public interface SharedTargetGateway {

    SharedTarget resolve(Long userId, SharedType type, long sharedId);

    // items는 코디·피팅 결과에 들어 있던 상품들이다. 월드컵은 비어 있다.
    record SharedTarget(SharedSnapshot snapshot, List<SharedItem> items) {

        public SharedTarget {
            items = List.copyOf(items);
        }
    }
}
