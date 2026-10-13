package com.fitzza.community.client;

import com.fitzza.community.client.WorldcupResults.WorldcupResult;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// 상품 서비스가 응답하지 않으면 확인 없이 글을 쓰지 않고 거절한다(남의 결과가 공유되지 않게).
@Component
public class VerifiedSharedTargetGateway implements SharedTargetGateway {

    private static final Logger log = LoggerFactory.getLogger(VerifiedSharedTargetGateway.class);

    private final WorldcupResults worldcupResults;

    public VerifiedSharedTargetGateway(WorldcupResults worldcupResults) {
        this.worldcupResults = worldcupResults;
    }

    @Override
    public SharedSnapshot resolve(Long userId, SharedType type, long sharedId) {
        WorldcupResult result;
        try {
            result = worldcupResults.findWorldcup(sharedId, userId)
                    .orElseThrow(() -> new CommunityApiException(ErrorCode.SHARED_TARGET_NOT_FOUND));
        } catch (RestClientException exception) {
            log.warn("상품 서비스에서 월드컵 결과를 확인하지 못해 공유 글 작성을 거부합니다: {}", exception.getMessage());
            throw new CommunityApiException(ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
        }
        if (!result.owned()) {
            throw new CommunityApiException(ErrorCode.NOT_SHARED_OWNER);
        }
        WorldcupResults.WinnerProduct winner = result.winnerProduct();
        if (winner == null) {
            throw new CommunityApiException(ErrorCode.SHARED_TARGET_NOT_FOUND);
        }
        return new SharedSnapshot(
                winner.productId(), winner.brandName(), winner.productName(), winner.price(), winner.imageUrl());
    }
}
