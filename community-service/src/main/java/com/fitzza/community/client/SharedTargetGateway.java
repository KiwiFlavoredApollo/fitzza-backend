package com.fitzza.community.client;

import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;

// 공유 대상이 요청한 사용자 것인지 확인하고, 글에 저장할 스냅샷을 만든다.
public interface SharedTargetGateway {

    SharedSnapshot resolve(Long userId, SharedType type, long sharedId);
}
