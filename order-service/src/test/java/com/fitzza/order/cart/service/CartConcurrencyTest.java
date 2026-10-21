package com.fitzza.order.cart.service;

import static com.fitzza.order.support.TestFixtures.option;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.fitzza.order.cart.dto.CartCreateRequest;
import com.fitzza.order.cart.repository.CartRepository;
import com.fitzza.order.global.client.ProductFeignClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase
class CartConcurrencyTest {

    private static final Long USER_ID = 1L;
    private static final Long OPTION_ID = 10L;
    private static final int CONCURRENT_ADDS = 8;

    @Autowired
    private CartService cartService;

    @Autowired
    private CartRepository cartRepository;

    @MockBean
    private ProductFeignClient productFeignClient;

    @BeforeEach
    void setUp() {
        when(productFeignClient.getOptions(anyList())).thenReturn(List.of(option(OPTION_ID, 30000, "ON_SALE")));
    }

    @AfterEach
    void deleteCarts() {
        cartRepository.deleteAll();
    }

    @Test
    void concurrentAddsOfTheSameOptionKeepEveryIncrement() throws Exception {
        cartService.addCart(USER_ID, new CartCreateRequest(OPTION_ID, 1));
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ADDS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_ADDS; i++) {
                futures.add(executor.submit(() ->
                        cartService.addCart(USER_ID, new CartCreateRequest(OPTION_ID, 1))));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        int quantity = cartRepository.findAllByUserIdOrderByCartIdDesc(USER_ID).get(0).getQuantity();
        assertThat(quantity).isEqualTo(1 + CONCURRENT_ADDS);
    }
}
