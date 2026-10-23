package com.fitzza.product.product.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 상품 색상-이미지 매핑. 무신사 color_image_mappings 한 줄에 해당한다. */
public record ColorImageMappingValue(
        @JsonProperty("productId") Long productId,
        @JsonProperty("colorRaw") String colorRaw,
        @JsonProperty("imageUrl") String imageUrl) {
}
