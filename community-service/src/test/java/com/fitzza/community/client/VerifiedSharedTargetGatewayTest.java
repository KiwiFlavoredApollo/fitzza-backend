package com.fitzza.community.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fitzza.community.client.WorldcupResults.WinnerProduct;
import com.fitzza.community.client.WorldcupResults.WorldcupResult;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import com.fitzza.community.exception.CommunityApiException;
import com.fitzza.community.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class VerifiedSharedTargetGatewayTest {

    private static final WinnerProduct WINNER =
            new WinnerProduct(55L, "Fitzza", "오버핏 셔츠", 39000, "https://cdn.example.com/55.png");

    private final WorldcupResults worldcupResults = mock(WorldcupResults.class);
    private final VerifiedSharedTargetGateway gateway = new VerifiedSharedTargetGateway(worldcupResults);

    @Test
    void myWorldcupResultBecomesASnapshotOfTheWinner() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenReturn(Optional.of(new WorldcupResult(9L, true, WINNER)));

        SharedSnapshot snapshot = gateway.resolve(7L, SharedType.WORLDCUP, 9L);

        assertThat(snapshot.getProductId()).isEqualTo(55L);
        assertThat(snapshot.getBrandName()).isEqualTo("Fitzza");
        assertThat(snapshot.getProductName()).isEqualTo("오버핏 셔츠");
        assertThat(snapshot.getPrice()).isEqualTo(39000);
        assertThat(snapshot.getImageUrl()).isEqualTo("https://cdn.example.com/55.png");
    }

    @Test
    void someoneElsesResultIsForbidden() {
        when(worldcupResults.findWorldcup(9L, 8L)).thenReturn(Optional.of(new WorldcupResult(9L, false, null)));

        assertRejected(8L, ErrorCode.NOT_SHARED_OWNER);
    }

    @Test
    void aMissingResultIsNotFound() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenReturn(Optional.empty());

        assertRejected(7L, ErrorCode.SHARED_TARGET_NOT_FOUND);
    }

    @Test
    void anUnreachableProductServiceRefusesTheShareInsteadOfSkippingTheCheck() {
        when(worldcupResults.findWorldcup(9L, 7L)).thenThrow(new ResourceAccessException("connection refused"));

        assertRejected(7L, ErrorCode.SHARE_LOOKUP_UNAVAILABLE);
    }

    private void assertRejected(Long userId, ErrorCode expected) {
        assertThatThrownBy(() -> gateway.resolve(userId, SharedType.WORLDCUP, 9L))
                .isInstanceOf(CommunityApiException.class)
                .extracting(exception -> ((CommunityApiException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
