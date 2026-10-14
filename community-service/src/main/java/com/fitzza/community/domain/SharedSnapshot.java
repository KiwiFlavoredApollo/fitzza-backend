package com.fitzza.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

// 공유한 순간의 대상 정보를 글에 복사해 둔다. 원본 상품이 바뀌거나 내려가도 글에는 공유 당시 모습이 남는다.
@Embeddable
public class SharedSnapshot {

    public static final int IMAGE_URL_MAX_LENGTH = 500;

    @Column(name = "shared_product_id")
    private Long productId;

    @Column(name = "shared_brand_name", length = 100)
    private String brandName;

    @Column(name = "shared_product_name", length = 255)
    private String productName;

    @Column(name = "shared_price")
    private Integer price;

    @Column(name = "shared_image_url", length = IMAGE_URL_MAX_LENGTH)
    private String imageUrl;

    protected SharedSnapshot() {
    }

    public SharedSnapshot(Long productId, String brandName, String productName, Integer price, String imageUrl) {
        this.productId = productId;
        this.brandName = brandName;
        this.productName = productName;
        this.price = price;
        this.imageUrl = imageUrl;
    }

    public Long getProductId() {
        return productId;
    }

    public String getBrandName() {
        return brandName;
    }

    public String getProductName() {
        return productName;
    }

    public Integer getPrice() {
        return price;
    }

    public String getImageUrl() {
        return imageUrl;
    }
}
