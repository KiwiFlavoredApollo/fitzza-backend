package com.fitzza.product.product.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitzza.product.product.entity.FitType;
import com.fitzza.product.product.entity.ProductEntity;
import com.fitzza.product.product.entity.ProductOptionEntity;
import com.fitzza.product.product.entity.ProductStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class MusinsaColorImageMappingTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final MusinsaProductMapper mapper = new MusinsaProductMapper();

    @Test
    void toOptionsSkipsNullColorImageMappingAndKeepsMatchedColorImage() throws Exception {
        MusinsaProductDocument document = objectMapper.readValue("""
                {
                  "options": [{"name": "컬러"}],
                  "option_items": [
                    {"id": 1, "activated": true, "values": ["BLACK"]}
                  ],
                  "color_image_mappings": [
                    null,
                    {"color_raw": "BLACK", "image_url": "https://img.example/black.jpg"}
                  ]
                }
                """, MusinsaProductDocument.class);

        ProductEntity product = ProductEntity.builder()
                .productName("셔츠")
                .category1("001")
                .price(10000)
                .status(ProductStatus.ON_SALE)
                .build();
        List<ProductOptionEntity> options = mapper.toOptions(document, product);

        assertThat(options).singleElement()
                .extracting(ProductOptionEntity::getColor, ProductOptionEntity::getImageUrl)
                .containsExactly("BLACK", "https://img.example/black.jpg");
    }

    @Test
    void toProductMapsCrawlerMaterialAndFitType() throws Exception {
        MusinsaProductDocument document = objectMapper.readValue("""
                {
                  "product_id": 123,
                  "name": "오버핏 코튼 셔츠",
                  "brand": {"name": "브랜드"},
                  "category": {"categoryDepth1Code": "001", "categoryDepth1Name": "상의"},
                  "price": {"sale": 10000},
                  "material": "면",
                  "fit_type": "OVERSIZED",
                  "features": {"핏": ["오버 사이즈"]},
                  "option_items": [{"id": 1, "activated": true, "values": ["M"]}]
                }
                """, MusinsaProductDocument.class);

        ProductEntity product = mapper.toProduct(document, mapper.toBrand(document));

        assertThat(product.getMaterial()).isEqualTo("면");
        assertThat(product.getFitType()).isEqualTo(FitType.OVERSIZED);
    }

    @Test
    void toColorImageMappingsKeepsProductColorRawAndImageUrl() throws Exception {
        MusinsaProductDocument document = objectMapper.readValue("""
                {
                  "product_id": 3086667,
                  "color_image_mappings": [
                    null,
                    {
                      "product_id": 3086667,
                      "color_raw": "베이지",
                      "image_url": "https://img.example/beige.jpg"
                    },
                    {"color_raw": "  ", "image_url": "https://img.example/blank.jpg"}
                  ]
                }
                """, MusinsaProductDocument.class);

        assertThat(mapper.toColorImageMappings(document)).singleElement()
                .extracting(
                        mapping -> mapping.productId(),
                        mapping -> mapping.colorRaw(),
                        mapping -> mapping.imageUrl())
                .containsExactly(3086667L, "베이지", "https://img.example/beige.jpg");
    }
}
