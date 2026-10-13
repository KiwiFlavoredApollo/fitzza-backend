package com.fitzza.product.worldcup.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitzza.product.product.entity.BrandEntity;
import com.fitzza.product.product.entity.ProductEntity;
import com.fitzza.product.product.entity.ProductStatus;
import com.fitzza.product.worldcup.entity.WorldcupHistoryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest(properties = "spring.cloud.config.enabled=false")
class WorldcupHistoryRepositoryTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private WorldcupHistoryRepository worldcupHistoryRepository;

    private ProductEntity firstWinner;
    private ProductEntity secondWinner;

    @BeforeEach
    void setUp() {
        BrandEntity brand = entityManager.persist(BrandEntity.builder().brandName("Fitzza").build());
        firstWinner = persistProduct(brand, "첫 우승");
        secondWinner = persistProduct(brand, "두 번째 우승");
    }

    @Test
    void findFirstReturnsMostRecentHistoryOfTheUser() {
        entityManager.persist(WorldcupHistoryEntity.builder().userId(USER_ID).winnerProduct(firstWinner).build());
        WorldcupHistoryEntity latest = entityManager.persist(
                WorldcupHistoryEntity.builder().userId(USER_ID).winnerProduct(secondWinner).build());
        entityManager.persist(WorldcupHistoryEntity.builder().userId(2L).winnerProduct(firstWinner).build());
        entityManager.flush();
        entityManager.clear();

        var found = worldcupHistoryRepository.findFirstByUserIdOrderByCompletedAtDescWorldcupHistoryIdDesc(USER_ID);

        assertThat(found).isPresent();
        assertThat(found.get().getWorldcupHistoryId()).isEqualTo(latest.getWorldcupHistoryId());
        assertThat(found.get().getWinnerProduct().getBrand().getBrandName()).isEqualTo("Fitzza");
    }

    @Test
    void findAllByUserIdPaginatesOnlyTheUsersHistory() {
        entityManager.persist(WorldcupHistoryEntity.builder().userId(USER_ID).winnerProduct(firstWinner).build());
        entityManager.persist(WorldcupHistoryEntity.builder().userId(USER_ID).winnerProduct(secondWinner).build());
        entityManager.persist(WorldcupHistoryEntity.builder().userId(2L).winnerProduct(firstWinner).build());
        entityManager.flush();
        entityManager.clear();

        Page<WorldcupHistoryEntity> page = worldcupHistoryRepository.findAllByUserId(
                USER_ID, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "worldcupHistoryId")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getWinnerProduct().getProductName()).isEqualTo("두 번째 우승");
    }

    @Test
    void findWithWinnerLoadsWinnerProduct() {
        WorldcupHistoryEntity history = entityManager.persist(
                WorldcupHistoryEntity.builder().userId(USER_ID).winnerProduct(firstWinner).build());
        entityManager.flush();
        entityManager.clear();

        var found = worldcupHistoryRepository.findWithWinnerByWorldcupHistoryId(history.getWorldcupHistoryId());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(USER_ID);
        assertThat(found.get().getWinnerProduct().getProductName()).isEqualTo("첫 우승");
    }

    private ProductEntity persistProduct(BrandEntity brand, String name) {
        return entityManager.persist(ProductEntity.builder()
                .brand(brand)
                .productName(name)
                .category1("TOP")
                .price(10000)
                .status(ProductStatus.ON_SALE)
                .build());
    }
}
