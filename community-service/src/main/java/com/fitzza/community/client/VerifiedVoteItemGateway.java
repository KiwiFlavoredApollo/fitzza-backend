package com.fitzza.community.client;

import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOptionSource;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

// 찜 출처는 상품 서비스에서 본인이 찜한 상품인지 확인하고, 이미지·상품명·가격을 상품 서비스 값으로 채운다.
// AI 추천 출처는 추천 서비스의 확인 API가 아직 없어서 요청에 온 이미지 주소를 그대로 쓴다.
@Component
public class VerifiedVoteItemGateway implements VoteItemGateway {

    private static final Logger log = LoggerFactory.getLogger(VerifiedVoteItemGateway.class);
    private static final String HIDDEN_STATUS = "HIDDEN";

    private final ProductCatalog productCatalog;

    public VerifiedVoteItemGateway(ProductCatalog productCatalog) {
        this.productCatalog = productCatalog;
    }

    @Override
    public VoteItemSnapshot resolve(
            Long userId, VoteOptionSource source, VoteItemType itemType, String itemId, String imageUrl) {
        if (source != VoteOptionSource.WISHLIST) {
            return new VoteItemSnapshot(imageUrl, null, null);
        }
        // 찜 목록에는 상품만 담긴다.
        if (itemType != VoteItemType.PRODUCT) {
            throw new CommunityApiException(ErrorCode.INVALID_VOTE_OPTIONS);
        }
        long productId = parseProductId(itemId);

        ProductCatalog.ProductSummary product;
        try {
            if (!productCatalog.isWishlisted(userId, productId)) {
                throw new CommunityApiException(ErrorCode.NOT_WISHLISTED_ITEM);
            }
            product = productCatalog.findProduct(productId).orElse(null);
        } catch (RestClientException exception) {
            log.warn("상품 서비스에서 찜 상품을 확인하지 못해 투표 글 작성을 거부합니다: {}", exception.getMessage());
            throw new CommunityApiException(ErrorCode.PRODUCT_LOOKUP_UNAVAILABLE);
        }
        if (product == null || HIDDEN_STATUS.equals(product.status())) {
            throw new CommunityApiException(ErrorCode.INVALID_VOTE_OPTIONS);
        }
        // 대표 이미지가 없는 상품만 요청에 온 이미지를 쓴다.
        String snapshotImage =
                product.imageUrl() == null || product.imageUrl().isBlank() ? imageUrl : product.imageUrl();
        return new VoteItemSnapshot(snapshotImage, product.productName(), product.price());
    }

    private static long parseProductId(String itemId) {
        try {
            long productId = Long.parseLong(itemId);
            if (productId > 0) {
                return productId;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 같은 오류로 거부한다.
        }
        throw new CommunityApiException(ErrorCode.INVALID_VOTE_OPTIONS);
    }
}
