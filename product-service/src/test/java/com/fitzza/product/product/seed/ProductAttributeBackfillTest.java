package com.fitzza.product.product.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitzza.product.product.entity.FitType;
import com.fitzza.product.product.entity.ProductEntity;
import com.fitzza.product.product.repository.BrandRepository;
import com.fitzza.product.product.repository.OptionMeasurementRepository;
import com.fitzza.product.product.repository.ProductImageRepository;
import com.fitzza.product.product.repository.ProductOptionRepository;
import com.fitzza.product.product.repository.ProductRepository;
import com.fitzza.product.product.repository.ProductSizeGuideRepository;
import com.fitzza.product.product.service.CategoryCatalogService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class ProductAttributeBackfillTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final MusinsaProductMapper mapper = new MusinsaProductMapper();
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductSeedService productSeedService = new ProductSeedService(
            mapper,
            mock(CategoryCatalogService.class),
            mock(BrandRepository.class),
            productRepository,
            mock(ProductImageRepository.class),
            mock(ProductSizeGuideRepository.class),
            mock(ProductOptionRepository.class),
            mock(OptionMeasurementRepository.class));

    @Test
    void fillsMissingMaterialAndFitOfSavedProduct() throws Exception {
        ProductEntity saved = product(document(""));
        when(productRepository.findById(123L)).thenReturn(Optional.of(saved));

        boolean updated = productSeedService.fillMaterialAndFitIfMissing(document("""
                "material": "면 100%",
                "fit_type": "RELAXED",
                """));

        assertThat(updated).isTrue();
        assertThat(saved.getMaterial()).isEqualTo("면 100%");
        assertThat(saved.getFitType()).isEqualTo(FitType.RELAXED);
    }

    @Test
    void keepsValuesThatAreAlreadySaved() throws Exception {
        ProductEntity saved = product(document("""
                "material": "울 100%",
                "fit_type": "SLIM",
                """));
        when(productRepository.findById(123L)).thenReturn(Optional.of(saved));

        boolean updated = productSeedService.fillMaterialAndFitIfMissing(document("""
                "material": "면 100%",
                "fit_type": "RELAXED",
                """));

        assertThat(updated).isFalse();
        assertThat(saved.getMaterial()).isEqualTo("울 100%");
        assertThat(saved.getFitType()).isEqualTo(FitType.SLIM);
    }

    @Test
    void skipsLookupWhenDocumentHasNoMaterialOrFit() throws Exception {
        boolean updated = productSeedService.fillMaterialAndFitIfMissing(document("""
                "material": "  ",
                """));

        assertThat(updated).isFalse();
        verify(productRepository, never()).findById(any());
    }

    private ProductEntity product(MusinsaProductDocument document) {
        return mapper.toProduct(document, mapper.toBrand(document));
    }

    private MusinsaProductDocument document(String attributes) throws Exception {
        return objectMapper.readValue("""
                {
                  "product_id": 123,
                  "name": "코튼 셔츠",
                  "brand": {"name": "브랜드"},
                  "category": {"categoryDepth1Code": "001", "categoryDepth1Name": "상의"},
                  "price": {"sale": 10000},
                  %s
                  "option_items": [{"id": 1, "activated": true, "values": ["M"]}]
                }
                """.formatted(attributes), MusinsaProductDocument.class);
    }
}
