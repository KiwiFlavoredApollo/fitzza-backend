package com.fitzza.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitzza.product.global.constant.ApiHeaders;
import com.fitzza.product.product.entity.BrandEntity;
import com.fitzza.product.product.entity.CategoryEntity;
import com.fitzza.product.product.entity.FitType;
import com.fitzza.product.product.entity.OptionMeasurementEntity;
import com.fitzza.product.product.entity.ProductEntity;
import com.fitzza.product.product.entity.ProductOptionEntity;
import com.fitzza.product.product.entity.ProductStatus;
import com.fitzza.product.product.entity.Season;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "fitzza.seed.musinsa.enabled=false",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase
@Transactional
class ProductServiceIntegrationTest {

    private static final String USER_ID = "1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    private ProductEntity shirt;
    private ProductEntity knit;
    private ProductOptionEntity shirtOption;

    @BeforeEach
    void setUp() {
        BrandEntity brand = BrandEntity.builder().brandName("Fitzza").build();
        entityManager.persist(brand);
        shirt = persistProduct(brand, "셔츠", "TOP", "SHIRT", 30000, ProductStatus.ON_SALE);
        knit = persistProduct(brand, "니트", "TOP", "KNIT", 50000, ProductStatus.ON_SALE);
        shirtOption = ProductOptionEntity.builder().product(shirt).color("BLACK").size("M").build();
        entityManager.persist(shirtOption);
        entityManager.persist(ProductOptionEntity.builder().product(shirt).color("BLACK").size("L").build());
        entityManager.persist(OptionMeasurementEntity.builder()
                .option(shirtOption)
                .measurementSpec(Map.of("총장", 70, "어깨너비", 52))
                .build());
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void openApiDocumentExposesEveryEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/categories']").exists())
                .andExpect(jsonPath("$.paths['/api/products']").exists())
                .andExpect(jsonPath("$.paths['/api/products/{productId}']").exists())
                .andExpect(jsonPath("$.paths['/internal/v1/products']").exists())
                .andExpect(jsonPath("$.paths['/internal/v1/products/measurements']").exists())
                .andExpect(jsonPath("$.paths['/internal/product-options']").exists())
                .andExpect(jsonPath("$.paths['/internal/v1/options']").doesNotExist())
                .andExpect(jsonPath("$.paths['/internal/options']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/products'].post").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/wishlists'].get").exists())
                .andExpect(jsonPath("$.paths['/api/wishlists'].post").exists())
                .andExpect(jsonPath("$.paths['/api/wishlists'].delete").exists())
                .andExpect(jsonPath("$.paths['/internal/v1/wishlists/check']").exists())
                .andExpect(jsonPath("$.paths['/api/worldcups/candidates']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/worldcups'].post").exists())
                .andExpect(jsonPath("$.paths['/api/worldcups/latest']").exists())
                .andExpect(jsonPath("$.paths['/api/worldcups/history']").exists())
                .andExpect(jsonPath("$.paths['/internal/v1/worldcups/{worldcupHistoryId}']").exists());
    }

    @Test
    void productApisReturnCategoriesListDetailAndInternalData() throws Exception {
        persistCategory("TOP", "상의", null, 1);
        persistCategory("SHIRT", "셔츠", "TOP", 2);
        persistCategory("KNIT", "니트", "TOP", 2);
        entityManager.flush();

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("TOP"))
                .andExpect(jsonPath("$[0].name").value("상의"))
                .andExpect(jsonPath("$[0].children.length()").value(2))
                .andExpect(jsonPath("$[0].children[0].code").value("KNIT"))
                .andExpect(jsonPath("$[0].children[0].name").value("니트"));

        mockMvc.perform(get("/api/products").param("category", "TOP").param("sort", "PRICE_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].productName").value("니트"));
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get("/api/products").param("category", "TO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/products/" + shirt.getProductId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandName").value("Fitzza"))
                .andExpect(jsonPath("$.options.length()").value(2))
                .andExpect(jsonPath("$.options[0].price").value(30000))
                .andExpect(jsonPath("$.sizeGuide.measurements.length()").value(1))
                .andExpect(jsonPath("$.sizeGuide.measurements[0].size").value("M"))
                .andExpect(jsonPath("$.sizeGuide.measurements[0].values['총장']").value(70));

        mockMvc.perform(get("/internal/v1/products").param("productIds", knit.getProductId() + "," + shirt.getProductId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("니트"))
                .andExpect(jsonPath("$[1].productName").value("셔츠"));

        mockMvc.perform(get("/internal/v1/products/measurements")
                        .param("productIds", shirt.getProductId() + "," + knit.getProductId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].options.length()").value(1))
                .andExpect(jsonPath("$[0].options[0].size").value("M"))
                .andExpect(jsonPath("$[1].options.length()").value(0));
    }

    @Test
    void categoryTreeExcludesProductsWhoseCategoryIsNotInCategoryTable() throws Exception {
        persistCategory("TOP", "상의", null, 1);
        persistCategory("SHIRT", "셔츠", "TOP", 2);
        persistProduct(entityManager.find(BrandEntity.class, brandId()), "모자 상품", "모자", "캡/야구모자", 10000, ProductStatus.ON_SALE);
        entityManager.flush();

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("TOP"))
                .andExpect(jsonPath("$[0].name").value("상의"))
                .andExpect(jsonPath("$[0].children.length()").value(1))
                .andExpect(jsonPath("$[0].children[0].code").value("SHIRT"));
    }

    private void persistCategory(String code, String name, String parentCode, int depth) {
        entityManager.persist(CategoryEntity.builder()
                .categoryCode(code).categoryName(name).parentCode(parentCode).depth(depth).build());
    }

    private Long brandId() {
        return shirt.getBrand().getBrandId();
    }

    @Test
    void productOptionsApiReturnsExistingOptionsWithAvailabilityAndSkipsMissingIds() throws Exception {
        ProductEntity soldOut = persistProduct(
                entityManager.find(BrandEntity.class, shirt.getBrand().getBrandId()),
                "품절 셔츠", "TOP", "SHIRT", 20000, ProductStatus.SOLD_OUT);
        ProductOptionEntity soldOutOption = ProductOptionEntity.builder()
                .product(soldOut).color("WHITE").size("S").additionalPrice(1500).build();
        entityManager.persist(soldOutOption);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/internal/product-options")
                        .param("optionIds", soldOutOption.getOptionId() + ",999999," + shirtOption.getOptionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].optionId").value(shirtOption.getOptionId()))
                .andExpect(jsonPath("$[0].productId").value(shirt.getProductId()))
                .andExpect(jsonPath("$[0].brandName").value("Fitzza"))
                .andExpect(jsonPath("$[0].productName").value("셔츠"))
                .andExpect(jsonPath("$[0].price").value(30000))
                .andExpect(jsonPath("$[0].color").value("BLACK"))
                .andExpect(jsonPath("$[0].size").value("M"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$[1].price").value(21500))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[1].status").value("SOLD_OUT"));

        mockMvc.perform(get("/internal/product-options").param("optionIds", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void wishlistFlowCoversProductItemsIdempotently() throws Exception {
        String itemId = String.valueOf(shirt.getProductId());
        String created = mockMvc.perform(post("/api/wishlists")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + itemId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wishlistId").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String existing = mockMvc.perform(post("/api/wishlists")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + itemId + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(existing).isEqualTo(created);

        mockMvc.perform(get("/api/wishlists").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("셔츠"))
                .andExpect(jsonPath("$.content[0].price").value(30000))
                .andExpect(jsonPath("$.content[0].categoryL1").value("TOP"));

        mockMvc.perform(get("/internal/v1/wishlists/check")
                        .param("userId", USER_ID)
                        .param("itemId", itemId))
                .andExpect(jsonPath("$.wishlisted").value(true));

        mockMvc.perform(delete("/api/wishlists")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .param("itemId", itemId))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/wishlists")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .param("itemId", itemId))
                .andExpect(status().isNoContent());
    }

    @Test
    void worldcupFlowSavesAndReadsResults() throws Exception {
        addWishlist(shirt.getProductId());
        addWishlist(knit.getProductId());

        String body = "{\"winnerProductId\":" + knit.getProductId()
                + ",\"candidateProductIds\":[" + shirt.getProductId() + "," + knit.getProductId() + "]}";
        String response = mockMvc.perform(post("/api/worldcups")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.winnerProduct.productName").value("니트"))
                .andReturn().getResponse().getContentAsString();
        String historyId = com.jayway.jsonpath.JsonPath.read(response, "$.worldcupHistoryId").toString();

        mockMvc.perform(get("/api/worldcups/latest").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldcupHistoryId").value(Integer.valueOf(historyId)));
        mockMvc.perform(get("/api/worldcups/history").header(ApiHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/internal/v1/worldcups/" + historyId).param("userId", USER_ID))
                .andExpect(jsonPath("$.owned").value(true))
                .andExpect(jsonPath("$.winnerProduct.productName").value("니트"));
        mockMvc.perform(get("/internal/v1/worldcups/" + historyId).param("userId", "2"))
                .andExpect(jsonPath("$.owned").value(false));
        mockMvc.perform(get("/api/worldcups/latest").header(ApiHeaders.USER_ID, "2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void worldcupRejectsCandidatesThatAreNotWishlisted() throws Exception {
        addWishlist(shirt.getProductId());
        String body = "{\"winnerProductId\":" + shirt.getProductId()
                + ",\"candidateProductIds\":[" + shirt.getProductId() + "," + knit.getProductId() + "]}";

        mockMvc.perform(post("/api/worldcups")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORLDCUP_INVALID_CANDIDATES"));
    }

    private void addWishlist(Long productId) throws Exception {
        mockMvc.perform(post("/api/wishlists")
                        .header(ApiHeaders.USER_ID, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + productId + "\"}"))
                .andExpect(status().isOk());
    }

    private ProductEntity persistProduct(
            BrandEntity brand, String name, String category1, String category2, int price, ProductStatus status) {
        ProductEntity product = ProductEntity.builder()
                .brand(brand)
                .productName(name)
                .category1(category1)
                .category2(category2)
                .price(price)
                .season(Season.SPRING)
                .fitType(FitType.REGULAR)
                .status(status)
                .build();
        entityManager.persist(product);
        return product;
    }
}
