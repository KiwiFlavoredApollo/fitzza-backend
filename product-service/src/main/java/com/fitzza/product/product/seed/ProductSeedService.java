package com.fitzza.product.product.seed;

import com.fitzza.product.product.entity.BrandEntity;
import com.fitzza.product.product.entity.ColorImageMappingValue;
import com.fitzza.product.product.entity.FitType;
import com.fitzza.product.product.entity.ProductEntity;
import com.fitzza.product.product.entity.ProductOptionEntity;
import com.fitzza.product.product.repository.BrandRepository;
import com.fitzza.product.product.service.CategoryCatalogService;
import com.fitzza.product.product.repository.OptionMeasurementRepository;
import com.fitzza.product.product.repository.ProductImageRepository;
import com.fitzza.product.product.repository.ProductOptionRepository;
import com.fitzza.product.product.repository.ProductRepository;
import com.fitzza.product.product.repository.ProductSizeGuideRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

/**
 * 크롤링 문서 한 건을 하나의 트랜잭션으로 저장한다. 한 상품의 저장 실패가 다른 상품에 영향을 주지 않도록
 * 호출자가 상품 단위로 이 메서드를 호출한다.
 */
@Service
@RequiredArgsConstructor
public class ProductSeedService {

    private final MusinsaProductMapper mapper;
    private final CategoryCatalogService categoryCatalogService;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductSizeGuideRepository productSizeGuideRepository;
    private final ProductOptionRepository productOptionRepository;
    private final OptionMeasurementRepository optionMeasurementRepository;

    @Transactional
    public void saveProduct(MusinsaProductDocument document) {
        mapper.validate(document);
        categoryCatalogService.registerMissing(mapper.toCategories(document));
        BrandEntity brand = findOrCreateBrand(document);
        ProductEntity product = productRepository.save(mapper.toProduct(document, brand));
        productImageRepository.saveAll(mapper.toImages(document, product));
        mapper.toSizeGuide(document, product).ifPresent(productSizeGuideRepository::save);
        List<ProductOptionEntity> options = productOptionRepository.saveAll(mapper.toOptions(document, product));
        optionMeasurementRepository.saveAll(mapper.toOptionMeasurements(document, options));
    }

    /**
     * 이미 저장된 상품에 color_image_mappings가 비어 있으면 문서 값으로 채운다.
     * 기존 500건 시드 뒤에도 한 번 재기동하면 상세 API에 색 매핑이 채워지도록 한다.
     *
     * @return 실제로 갱신했으면 true
     */
    @Transactional
    public boolean fillColorImageMappingsIfMissing(MusinsaProductDocument document) {
        if (document.productId() == null) {
            return false;
        }
        List<ColorImageMappingValue> mappings = mapper.toColorImageMappings(document);
        if (mappings.isEmpty()) {
            return false;
        }
        return productRepository.findById(document.productId())
                .filter(product -> CollectionUtils.isEmpty(product.getColorImageMappings()))
                .map(product -> {
                    product.replaceColorImageMappings(mappings);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 이미 저장된 상품의 소재·핏이 비어 있으면 문서 값으로 채운다. 이미 있는 값은 덮어쓰지 않는다.
     * 기존 500건 시드도 재기동 한 번으로 상품정보제공고시 소재와 핏이 채워지도록 한다.
     *
     * @return 실제로 갱신했으면 true
     */
    @Transactional
    public boolean fillMaterialAndFitIfMissing(MusinsaProductDocument document) {
        if (document.productId() == null) {
            return false;
        }
        String material = mapper.toMaterial(document);
        FitType fitType = document.fitType();
        if (material == null && fitType == null) {
            return false;
        }
        return productRepository.findById(document.productId())
                .map(product -> product.fillMissingMaterialAndFit(material, fitType))
                .orElse(false);
    }

    /** 브랜드명은 유일하므로 같은 이름의 브랜드가 이미 있으면 재사용한다. */
    private BrandEntity findOrCreateBrand(MusinsaProductDocument document) {
        return brandRepository.findByBrandName(document.brand().name())
                .orElseGet(() -> brandRepository.save(mapper.toBrand(document)));
    }
}
