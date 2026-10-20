package com.fitzza.community.client;

import com.fitzza.community.client.ComboResults.ComboResult;
import com.fitzza.community.client.FittingResults.FittingResult;
import com.fitzza.community.client.WorldcupResults.WorldcupResult;
import com.fitzza.community.domain.SharedItem;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

// 제공 서비스(상품·추천·피팅)가 응답하지 않거나 5xx면 확인 없이 글을 쓰지 않고 503으로 거절한다(남의 결과가 공유되지 않게).
// 401·400 같은 4xx는 일시 장애가 아니라 토큰·요청 설정 문제이므로 그대로 던져 500으로 드러나게 한다.
@Component
public class VerifiedSharedTargetGateway implements SharedTargetGateway {

    private static final Logger log = LoggerFactory.getLogger(VerifiedSharedTargetGateway.class);

    private static final String HIDDEN_STATUS = "HIDDEN";

    private final WorldcupResults worldcupResults;
    private final ComboResults comboResults;
    private final FittingResults fittingResults;
    private final ProductCatalog productCatalog;

    public VerifiedSharedTargetGateway(
            WorldcupResults worldcupResults,
            ComboResults comboResults,
            FittingResults fittingResults,
            ProductCatalog productCatalog) {
        this.worldcupResults = worldcupResults;
        this.comboResults = comboResults;
        this.fittingResults = fittingResults;
        this.productCatalog = productCatalog;
    }

    @Override
    public SharedTarget resolve(Long userId, SharedType type, long sharedId) {
        return switch (type) {
            case WORLDCUP -> resolveWorldcup(userId, sharedId);
            case COMBO -> resolveCombo(userId, sharedId);
            case FITTING -> resolveFitting(userId, sharedId);
            case PRODUCT -> resolveProduct(userId, sharedId);
        };
    }

    private SharedTarget resolveWorldcup(Long userId, long worldcupHistoryId) {
        WorldcupResult result = lookUp("상품", () -> worldcupResults.findWorldcup(worldcupHistoryId, userId));
        if (!result.owned()) {
            throw new CommunityApiException(ErrorCode.NOT_SHARED_OWNER);
        }
        WorldcupResults.WinnerProduct winner = result.winnerProduct();
        if (winner == null) {
            throw new CommunityApiException(ErrorCode.SHARED_TARGET_NOT_FOUND);
        }
        return new SharedTarget(
                new SharedSnapshot(
                        winner.productId(), winner.brandName(), winner.productName(), winner.price(), winner.imageUrl()),
                List.of());
    }

    // 코디는 상품 여러 개라 총 가격·조화도 요약과 첫 상품 이미지(목록 썸네일용)를 스냅샷에, 상품들은 따로 담는다.
    private SharedTarget resolveCombo(Long userId, long comboId) {
        ComboResult combo = lookUp("추천", () -> comboResults.findCombo(comboId, userId));
        if (!combo.owned()) {
            throw new CommunityApiException(ErrorCode.NOT_SHARED_OWNER);
        }
        List<ResultProduct> products = combo.items() == null ? List.of() : combo.items();
        if (products.isEmpty()) {
            throw new CommunityApiException(ErrorCode.SHARED_TARGET_NOT_FOUND);
        }
        SharedSnapshot snapshot = new SharedSnapshot(
                null, null, null, combo.totalPrice(), products.get(0).imageUrl(), combo.feedbackSummary());
        return new SharedTarget(snapshot, toItems(products));
    }

    // 피팅은 완료된 결과만 공유할 수 있다. 결과 이미지를 스냅샷에, 입어 본 상품들은 따로 담는다.
    private SharedTarget resolveFitting(Long userId, long fittingId) {
        FittingResult fitting = lookUp("가상 피팅", () -> fittingResults.findFitting(fittingId, userId));
        if (!fitting.owned()) {
            throw new CommunityApiException(ErrorCode.NOT_SHARED_OWNER);
        }
        if (!FittingResults.COMPLETED.equals(fitting.status())
                || fitting.resultImageUrl() == null
                || fitting.resultImageUrl().isBlank()) {
            throw new CommunityApiException(ErrorCode.SHARED_TARGET_NOT_READY);
        }
        List<ResultProduct> products = fitting.products() == null ? List.of() : fitting.products();
        SharedSnapshot snapshot = new SharedSnapshot(null, null, null, null, fitting.resultImageUrl(), null);
        return new SharedTarget(snapshot, toItems(products));
    }

    // 내가 찜한 상품만 공유할 수 있다. 숨긴 상품은 없는 상품으로 본다. 상품 하나라 스냅샷만 채운다.
    private SharedTarget resolveProduct(Long userId, long productId) {
        ProductCatalog.ProductSummary product;
        try {
            if (!productCatalog.isWishlisted(userId, productId)) {
                throw new CommunityApiException(ErrorCode.NOT_WISHLISTED_ITEM);
            }
            product = productCatalog.findProduct(productId).orElse(null);
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            log.warn("상품 서비스에서 공유 대상을 확인하지 못해 공유 글 작성을 거부합니다: {}", exception.getMessage());
            throw new CommunityApiException(ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
        }
        if (product == null || HIDDEN_STATUS.equals(product.status())) {
            throw new CommunityApiException(ErrorCode.SHARED_TARGET_NOT_FOUND);
        }
        return new SharedTarget(
                new SharedSnapshot(
                        product.productId(),
                        product.brandName(),
                        product.productName(),
                        product.price(),
                        product.imageUrl()),
                List.of());
    }

    private static <T> T lookUp(String serviceName, Supplier<Optional<T>> lookup) {
        try {
            return lookup.get().orElseThrow(() -> new CommunityApiException(ErrorCode.SHARED_TARGET_NOT_FOUND));
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            log.warn("{} 서비스에서 공유 대상을 확인하지 못해 공유 글 작성을 거부합니다: {}", serviceName, exception.getMessage());
            throw new CommunityApiException(ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
        }
    }

    private static List<SharedItem> toItems(List<ResultProduct> products) {
        return products.stream()
                .map(product -> new SharedItem(
                        product.productId(), product.productName(), product.price(), product.imageUrl()))
                .toList();
    }
}
