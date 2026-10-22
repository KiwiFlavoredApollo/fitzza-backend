package com.fitzza.product.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "product",
        indexes = {
                @Index(name = "idx_product_category", columnList = "category_1, category_2, subcategory"),
                @Index(name = "idx_product_status", columnList = "status")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductEntity implements Persistable<Long> {

    /**
     * 상품 PK. 무신사 수집 상품은 원천(무신사) product_id를 그대로 쓴다.
     * 애플리케이션이 할당하며 DB identity 자동발급에 의존하지 않는다. 재시드 멱등키이기도 하다.
     */
    @Id
    @Column(name = "product_id")
    private Long productId;

    /** ID를 미리 넣어도 Spring Data가 merge가 아니라 persist 하도록 한다. */
    @Transient
    private boolean isNew = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private BrandEntity brand;

    /** 원천 쇼핑몰 구분(예: musinsa). */
    @Column(name = "source", length = 30)
    private String source;

    @Column(name = "source_url", length = 300)
    private String sourceUrl;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "product_name_en", length = 200)
    private String productNameEn;

    /** 브랜드가 부여한 품번(스타일 번호). */
    @Column(name = "style_no", length = 100)
    private String styleNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 20)
    private Gender gender;

    /** 대분류 카테고리 코드. 이름이 아니라 {@link CategoryEntity#getCategoryCode()} 값을 저장한다. */
    @Column(name = "category_1", nullable = false, length = 50)
    private String category1;

    /** 중분류 카테고리 코드. */
    @Column(name = "category_2", length = 50)
    private String category2;

    /** 소분류 카테고리 코드. */
    @Column(name = "subcategory", length = 50)
    private String subcategory;

    /** 실제 판매가(원). 원천 데이터의 price.sale에 해당한다. */
    @Column(name = "price", nullable = false)
    private Integer price;

    /** 할인 전 정상가(원). 원천 데이터의 price.normal에 해당한다. */
    @Column(name = "normal_price")
    private Integer normalPrice;

    @Column(name = "discount_rate")
    private Integer discountRate;

    @Column(name = "material", length = 200)
    private String material;

    @Enumerated(EnumType.STRING)
    @Column(name = "season", length = 10)
    private Season season;

    @Enumerated(EnumType.STRING)
    @Column(name = "fit_type", length = 20)
    private FitType fitType;

    /** 상품 특징(항목명-값 목록). 예: {"깊이": ["깊음"]}. 상품마다 항목이 달라 JSON으로 저장한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features")
    private Map<String, List<String>> features;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 대표(썸네일) 이미지 URL. 상품 이미지 목록의 첫 번째 항목이다. */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    private ProductEntity(
            Long productId,
            BrandEntity brand,
            String source,
            String sourceUrl,
            String productName,
            String productNameEn,
            String styleNo,
            Gender gender,
            String category1,
            String category2,
            String subcategory,
            Integer price,
            Integer normalPrice,
            Integer discountRate,
            String material,
            Season season,
            FitType fitType,
            Map<String, List<String>> features,
            String description,
            String imageUrl,
            ProductStatus status) {
        this.productId = productId;
        this.brand = brand;
        this.source = source;
        this.sourceUrl = sourceUrl;
        this.productName = productName;
        this.productNameEn = productNameEn;
        this.styleNo = styleNo;
        this.gender = gender;
        this.category1 = category1;
        this.category2 = category2;
        this.subcategory = subcategory;
        this.price = price;
        this.normalPrice = normalPrice;
        this.discountRate = discountRate;
        this.material = material;
        this.season = season;
        this.fitType = fitType;
        this.features = features == null ? new LinkedHashMap<>() : features;
        this.description = description;
        this.imageUrl = imageUrl;
        this.status = status;
    }

    /** 가장 하위 단계의 카테고리 코드를 반환한다. */
    public String getCategoryCode() {
        if (subcategory != null) {
            return subcategory;
        }
        return category2 != null ? category2 : category1;
    }

    @Override
    public Long getId() {
        return productId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
