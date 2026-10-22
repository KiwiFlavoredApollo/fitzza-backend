package com.fitzza.community.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitzza.community.client.ComboResults.ComboResult;
import com.fitzza.community.client.FittingResults.FittingResult;
import com.fitzza.community.client.ProductCatalog.ProductSummary;
import com.fitzza.community.client.SharedTargetGateway.SharedTarget;
import com.fitzza.community.client.WorldcupResults.WinnerProduct;
import com.fitzza.community.client.WorldcupResults.WorldcupResult;
import com.fitzza.community.domain.SharedItem;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class VerifiedSharedTargetGatewayTest {

    private static final WinnerProduct WINNER =
            new WinnerProduct(55L, "Fitzza", "오버핏 셔츠", 39000, "https://cdn.example.com/55.png");
    private static final ResultProduct SHIRT = new ResultProduct(1L, "셔츠", 39000, "https://cdn.example.com/1.png");
    private static final ResultProduct SLACKS = new ResultProduct(2L, "슬랙스", 50000, "https://cdn.example.com/2.png");
    private static final String FITTING_IMAGE = "https://cdn.example.com/fitting-3.png";

    private final WorldcupResults worldcupResults = mock(WorldcupResults.class);
    private final ComboResults comboResults = mock(ComboResults.class);
    private final FittingResults fittingResults = mock(FittingResults.class);
    private final ProductCatalog productCatalog = mock(ProductCatalog.class);
    private final VerifiedSharedTargetGateway gateway =
            new VerifiedSharedTargetGateway(worldcupResults, comboResults, fittingResults, productCatalog);

    @Test
    void myWorldcupResultBecomesASnapshotOfTheWinner() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenReturn(Optional.of(new WorldcupResult(9L, true, WINNER)));

        SharedTarget target = gateway.resolve(7L, SharedType.WORLDCUP, 9L);

        SharedSnapshot snapshot = target.snapshot();
        assertThat(snapshot.getProductId()).isEqualTo(55L);
        assertThat(snapshot.getBrandName()).isEqualTo("Fitzza");
        assertThat(snapshot.getProductName()).isEqualTo("오버핏 셔츠");
        assertThat(snapshot.getPrice()).isEqualTo(39000);
        assertThat(snapshot.getImageUrl()).isEqualTo("https://cdn.example.com/55.png");
        assertThat(target.items()).isEmpty();
    }

    @Test
    void someoneElsesResultIsForbidden() {
        when(worldcupResults.findWorldcup(9L, 8L)).thenReturn(Optional.of(new WorldcupResult(9L, false, null)));

        assertRejected(() -> gateway.resolve(8L, SharedType.WORLDCUP, 9L), ErrorCode.NOT_SHARED_OWNER);
    }

    @Test
    void aMissingResultIsNotFound() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenReturn(Optional.empty());

        assertRejected(() -> gateway.resolve(7L, SharedType.WORLDCUP, 9L), ErrorCode.SHARED_TARGET_NOT_FOUND);
    }

    @Test
    void anUnreachableProductServiceRefusesTheShareInsteadOfSkippingTheCheck() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenThrow(new ResourceAccessException("connection refused"));

        assertRejected(() -> gateway.resolve(7L, SharedType.WORLDCUP, 9L), ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
    }

    @Test
    void aProductServiceErrorAlsoRefusesTheShare() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenThrow(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad gateway", HttpHeaders.EMPTY, null, null));

        assertRejected(() -> gateway.resolve(7L, SharedType.WORLDCUP, 9L), ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
    }

    // 서비스 토큰이 틀린 401 같은 설정 문제는 503(잠시 후 재시도)으로 감추지 않는다.
    @Test
    void aRejectedServiceTokenIsNotReportedAsATemporaryOutage() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenThrow(
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "unauthorized", HttpHeaders.EMPTY, null, null));

        assertThatThrownBy(() -> gateway.resolve(7L, SharedType.WORLDCUP, 9L))
                .isInstanceOf(HttpClientErrorException.class);
    }

    @Test
    void myComboKeepsItsTotalSummaryFirstImageAndItems() {
        when(comboResults.findCombo(31L, 7L)).thenReturn(Optional.of(
                new ComboResult(true, List.of(SHIRT, SLACKS), 89000, "톤온톤으로 잘 어울려요")));

        SharedTarget target = gateway.resolve(7L, SharedType.COMBO, 31L);

        assertThat(target.snapshot().getProductId()).isNull();
        assertThat(target.snapshot().getPrice()).isEqualTo(89000);
        assertThat(target.snapshot().getImageUrl()).isEqualTo(SHIRT.imageUrl());
        assertThat(target.snapshot().getSummary()).isEqualTo("톤온톤으로 잘 어울려요");
        assertThat(target.items()).extracting(SharedItem::getProductId).containsExactly(1L, 2L);
        assertThat(target.items()).extracting(SharedItem::getProductName).containsExactly("셔츠", "슬랙스");
    }

    @Test
    void someoneElsesComboOrAnEmptyComboIsRejected() {
        when(comboResults.findCombo(31L, 8L)).thenReturn(Optional.of(new ComboResult(false, null, null, null)));
        when(comboResults.findCombo(32L, 7L)).thenReturn(Optional.of(new ComboResult(true, List.of(), 0, null)));
        when(comboResults.findCombo(33L, 7L)).thenReturn(Optional.empty());

        assertRejected(() -> gateway.resolve(8L, SharedType.COMBO, 31L), ErrorCode.NOT_SHARED_OWNER);
        assertRejected(() -> gateway.resolve(7L, SharedType.COMBO, 32L), ErrorCode.SHARED_TARGET_NOT_FOUND);
        assertRejected(() -> gateway.resolve(7L, SharedType.COMBO, 33L), ErrorCode.SHARED_TARGET_NOT_FOUND);
    }

    // 추천 서비스 주소가 아직 없으면 클라이언트가 ResourceAccessException을 던진다.
    @Test
    void aComboShareIsRefusedUntilTheRecommendationServiceAnswers() {
        when(comboResults.findCombo(31L, 7L)).thenThrow(new ResourceAccessException("not configured"));

        assertRejected(() -> gateway.resolve(7L, SharedType.COMBO, 31L), ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
    }

    @Test
    void myCompletedFittingKeepsItsResultImageAndProducts() {
        when(fittingResults.findFitting(3L, 7L)).thenReturn(Optional.of(
                new FittingResult(true, "COMPLETED", FITTING_IMAGE, List.of(SHIRT))));

        SharedTarget target = gateway.resolve(7L, SharedType.FITTING, 3L);

        assertThat(target.snapshot().getImageUrl()).isEqualTo(FITTING_IMAGE);
        assertThat(target.snapshot().getPrice()).isNull();
        assertThat(target.items()).extracting(SharedItem::getProductName).containsExactly("셔츠");
    }

    @Test
    void aFittingWithoutProductsIsStillShared() {
        when(fittingResults.findFitting(3L, 7L)).thenReturn(Optional.of(
                new FittingResult(true, "COMPLETED", FITTING_IMAGE, null)));

        assertThat(gateway.resolve(7L, SharedType.FITTING, 3L).items()).isEmpty();
    }

    @Test
    void anUnfinishedOrSomeoneElsesFittingIsRejected() {
        when(fittingResults.findFitting(3L, 7L)).thenReturn(Optional.of(
                new FittingResult(true, "PROCESSING", null, List.of())));
        when(fittingResults.findFitting(4L, 7L)).thenReturn(Optional.of(
                new FittingResult(true, "COMPLETED", " ", List.of())));
        when(fittingResults.findFitting(3L, 8L)).thenReturn(Optional.of(
                new FittingResult(false, null, null, null)));

        assertRejected(() -> gateway.resolve(7L, SharedType.FITTING, 3L), ErrorCode.SHARED_TARGET_NOT_READY);
        assertRejected(() -> gateway.resolve(7L, SharedType.FITTING, 4L), ErrorCode.SHARED_TARGET_NOT_READY);
        assertRejected(() -> gateway.resolve(8L, SharedType.FITTING, 3L), ErrorCode.NOT_SHARED_OWNER);
    }

    @Test
    void aFittingShareIsRefusedWhenTheFittingServiceIsDown() {
        when(fittingResults.findFitting(3L, 7L)).thenThrow(new ResourceAccessException("connection refused"));

        assertRejected(() -> gateway.resolve(7L, SharedType.FITTING, 3L), ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
    }


    @Test
    void myWishlistedProductBecomesASnapshotOfThatProduct() {
        when(productCatalog.isWishlisted(7L, 55L)).thenReturn(true);
        when(productCatalog.findProduct(55L)).thenReturn(Optional.of(
                new ProductSummary(55L, "Fitzza", "오버핏 셔츠", 39000, "https://cdn.example.com/55.png", "ON_SALE")));

        SharedTarget target = gateway.resolve(7L, SharedType.PRODUCT, 55L);

        SharedSnapshot snapshot = target.snapshot();
        assertThat(snapshot.getProductId()).isEqualTo(55L);
        assertThat(snapshot.getBrandName()).isEqualTo("Fitzza");
        assertThat(snapshot.getProductName()).isEqualTo("오버핏 셔츠");
        assertThat(snapshot.getPrice()).isEqualTo(39000);
        assertThat(snapshot.getImageUrl()).isEqualTo("https://cdn.example.com/55.png");
        assertThat(target.items()).isEmpty();
    }

    @Test
    void aProductIHaveNotWishlistedIsForbidden() {
        when(productCatalog.isWishlisted(7L, 55L)).thenReturn(false);

        assertRejected(() -> gateway.resolve(7L, SharedType.PRODUCT, 55L), ErrorCode.NOT_WISHLISTED_ITEM);
    }

    @Test
    void aMissingOrHiddenProductIsNotFound() {
        when(productCatalog.isWishlisted(7L, 55L)).thenReturn(true);
        when(productCatalog.isWishlisted(7L, 56L)).thenReturn(true);
        when(productCatalog.findProduct(55L)).thenReturn(Optional.empty());
        when(productCatalog.findProduct(56L)).thenReturn(Optional.of(
                new ProductSummary(56L, "Fitzza", "숨긴 상품", 39000, "https://cdn.example.com/56.png", "HIDDEN")));

        assertRejected(() -> gateway.resolve(7L, SharedType.PRODUCT, 55L), ErrorCode.SHARED_TARGET_NOT_FOUND);
        assertRejected(() -> gateway.resolve(7L, SharedType.PRODUCT, 56L), ErrorCode.SHARED_TARGET_NOT_FOUND);
    }

    @Test
    void aProductShareIsRefusedWhenTheProductServiceIsDown() {
        when(productCatalog.isWishlisted(7L, 55L)).thenThrow(new ResourceAccessException("connection refused"));

        assertRejected(() -> gateway.resolve(7L, SharedType.PRODUCT, 55L), ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
    }

    private static void assertRejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CommunityApiException.class)
                .extracting(exception -> ((CommunityApiException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
