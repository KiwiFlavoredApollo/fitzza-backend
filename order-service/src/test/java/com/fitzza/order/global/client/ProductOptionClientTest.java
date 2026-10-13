package com.fitzza.order.global.client;

import static com.fitzza.order.support.TestFixtures.assertErrorCode;
import static com.fitzza.order.support.TestFixtures.feignException;
import static com.fitzza.order.support.TestFixtures.option;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.order.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductOptionClientTest {

    @Mock
    private ProductFeignClient productFeignClient;

    @InjectMocks
    private ProductOptionClient productOptionClient;

    @Test
    void findOptionsRequestsDistinctIdsAndIndexesByOptionId() {
        when(productFeignClient.getOptions(List.of(10L, 11L)))
                .thenReturn(List.of(option(10L, 30000, "ON_SALE"), option(11L, 45000, "SOLD_OUT")));

        Map<Long, ProductOptionResponse> options = productOptionClient.findOptions(List.of(10L, 10L, 11L));

        assertThat(options).containsOnlyKeys(10L, 11L);
        assertThat(options.get(10L).onSale()).isTrue();
        assertThat(options.get(11L).onSale()).isFalse();
    }

    @Test
    void findOptionsSkipsRemoteCallForEmptyInput() {
        assertThat(productOptionClient.findOptions(List.of())).isEmpty();

        verifyNoInteractions(productFeignClient);
    }

    @Test
    void findOptionsMapsRemoteFailureToServiceUnavailable() {
        when(productFeignClient.getOptions(List.of(10L))).thenThrow(feignException(500));

        assertErrorCode(() -> productOptionClient.findOptions(List.of(10L)), ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
    }

    @Test
    void findOptionsRequestsIdsInBatchesOfOneHundred() {
        List<Long> optionIds = LongStream.rangeClosed(1, 101).boxed().toList();
        when(productFeignClient.getOptions(anyList())).thenAnswer(call -> {
            List<Long> requestedIds = call.getArgument(0);
            return requestedIds.stream().map(id -> option(id, 1000, "ON_SALE")).toList();
        });

        Map<Long, ProductOptionResponse> options = productOptionClient.findOptions(optionIds);

        assertThat(options).hasSize(101);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(productFeignClient, times(2)).getOptions(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(100, 1);
    }
}
