package com.fitzza.product.product.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
