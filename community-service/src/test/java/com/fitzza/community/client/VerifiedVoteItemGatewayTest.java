package com.fitzza.community.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.community.client.ProductCatalog.ProductSummary;
import com.fitzza.community.client.VoteItemGateway.VoteItemSnapshot;
import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOptionSource;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class VerifiedVoteItemGatewayTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final String REQUEST_IMAGE = "https://cdn.example.com/request.png";
    private static final String PRODUCT_IMAGE = "https://cdn.example.com/55.png";

    private final FakeProductCatalog catalog = new FakeProductCatalog();
    private final VerifiedVoteItemGateway gateway = new VerifiedVoteItemGateway(catalog);

    @Test
    void wishlistedProductIsStoredWithTheProductServiceImageNameAndPrice() {
        catalog.wishlist(USER_ID, 55L);
        catalog.product(new ProductSummary(55L, "오버핏 셔츠", 39000, PRODUCT_IMAGE, "ON_SALE"));

        VoteItemSnapshot snapshot = resolveWishlist(USER_ID, "55");

        assertThat(snapshot).isEqualTo(new VoteItemSnapshot(PRODUCT_IMAGE, "오버핏 셔츠", 39000));
    }

    @Test
    void soldOutProductCanStillBeAVoteOption() {
        catalog.wishlist(USER_ID, 55L);
        catalog.product(new ProductSummary(55L, "오버핏 셔츠", 39000, PRODUCT_IMAGE, "SOLD_OUT"));

        assertThat(resolveWishlist(USER_ID, "55").name()).isEqualTo("오버핏 셔츠");
    }

    @Test
    void productWithoutAnImageKeepsTheRequestImage() {
        catalog.wishlist(USER_ID, 55L);
        catalog.product(new ProductSummary(55L, "오버핏 셔츠", 39000, null, "ON_SALE"));

        assertThat(resolveWishlist(USER_ID, "55").imageUrl()).isEqualTo(REQUEST_IMAGE);
    }

    @Test
    void someoneElsesWishlistIsRejected() {
        catalog.wishlist(USER_ID, 55L);
        catalog.product(new ProductSummary(55L, "오버핏 셔츠", 39000, PRODUCT_IMAGE, "ON_SALE"));

        assertRejectedWith(() -> resolveWishlist(OTHER_USER_ID, "55"), ErrorCode.NOT_WISHLISTED_ITEM);
    }

    @Test
    void hiddenOrMissingProductsAreRejected() {
        catalog.wishlist(USER_ID, 55L);
        catalog.wishlist(USER_ID, 56L);
        catalog.product(new ProductSummary(55L, "숨긴 상품", 39000, PRODUCT_IMAGE, "HIDDEN"));

        assertRejectedWith(() -> resolveWishlist(USER_ID, "55"), ErrorCode.INVALID_VOTE_OPTIONS);
        assertRejectedWith(() -> resolveWishlist(USER_ID, "56"), ErrorCode.INVALID_VOTE_OPTIONS);
    }

    @Test
    void wishlistOptionsMustBeProductsWithANumericId() {
        assertRejectedWith(
                () -> gateway.resolve(USER_ID, VoteOptionSource.WISHLIST, VoteItemType.COMBO, "55", REQUEST_IMAGE),
                ErrorCode.INVALID_VOTE_OPTIONS);
        assertRejectedWith(() -> resolveWishlist(USER_ID, "abc"), ErrorCode.INVALID_VOTE_OPTIONS);
        assertRejectedWith(() -> resolveWishlist(USER_ID, "0"), ErrorCode.INVALID_VOTE_OPTIONS);
        assertThat(catalog.calls).isZero();
    }

    // "55"와 "055"를 함께 보내 같은 상품을 두 선택지로 넣는 것을 막는다.
    @Test
    void productIdsWrittenDifferentlyFromTheirNumberAreRejected() {
        catalog.wishlist(USER_ID, 55L);
        catalog.product(new ProductSummary(55L, "오버핏 셔츠", 39000, PRODUCT_IMAGE, "ON_SALE"));

        assertThat(resolveWishlist(USER_ID, "55").name()).isEqualTo("오버핏 셔츠");
        assertRejectedWith(() -> resolveWishlist(USER_ID, "055"), ErrorCode.INVALID_VOTE_OPTIONS);
        assertRejectedWith(() -> resolveWishlist(USER_ID, "+55"), ErrorCode.INVALID_VOTE_OPTIONS);
    }

    @Test
    void voteIsRejectedInsteadOfTrustedWhenTheProductServiceIsDown() {
        catalog.down = true;

        assertRejectedWith(() -> resolveWishlist(USER_ID, "55"), ErrorCode.PRODUCT_LOOKUP_UNAVAILABLE);
    }

    @Test
    void uploadsAndAiRecommendationsAreNotCheckedYet() {
        VoteItemSnapshot upload =
                gateway.resolve(USER_ID, VoteOptionSource.UPLOAD, VoteItemType.IMAGE, null, REQUEST_IMAGE);
        VoteItemSnapshot recommended =
                gateway.resolve(USER_ID, VoteOptionSource.AI_RECOMMEND, VoteItemType.COMBO, "combo-1", REQUEST_IMAGE);

        assertThat(upload).isEqualTo(new VoteItemSnapshot(REQUEST_IMAGE, null, null));
        assertThat(recommended).isEqualTo(new VoteItemSnapshot(REQUEST_IMAGE, null, null));
        assertThat(catalog.calls).isZero();
    }

    private VoteItemSnapshot resolveWishlist(Long userId, String itemId) {
        return gateway.resolve(userId, VoteOptionSource.WISHLIST, VoteItemType.PRODUCT, itemId, REQUEST_IMAGE);
    }

    private static void assertRejectedWith(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(CommunityApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static final class FakeProductCatalog implements ProductCatalog {

        private final Set<String> wishlists = new HashSet<>();
        private final Map<Long, ProductSummary> products = new HashMap<>();
        private boolean down;
        private int calls;

        void wishlist(Long userId, long productId) {
            wishlists.add(userId + ":" + productId);
        }

        void product(ProductSummary product) {
            products.put(product.productId(), product);
        }

        @Override
        public boolean isWishlisted(Long userId, long productId) {
            calls++;
            if (down) {
                throw new ResourceAccessException("product-service is down");
            }
            return wishlists.contains(userId + ":" + productId);
        }

        @Override
        public Optional<ProductSummary> findProduct(long productId) {
            calls++;
            if (down) {
                throw new ResourceAccessException("product-service is down");
            }
            return Optional.ofNullable(products.get(productId));
        }
    }
}
