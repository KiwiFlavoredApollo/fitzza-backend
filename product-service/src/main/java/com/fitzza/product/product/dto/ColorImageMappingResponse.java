package com.fitzza.product.product.dto;

import com.fitzza.product.product.entity.ColorImageMappingValue;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 색상과 대표 이미지 매핑")
public record ColorImageMappingResponse(
        @Schema(description = "이 색에 해당하는 상품 ID", example = "3086667") Long productId,
        @Schema(description = "색상 원문", example = "베이지") String colorRaw,
        @Schema(description = "색상 대표 이미지 URL") String imageUrl) {

    public static ColorImageMappingResponse from(ColorImageMappingValue value) {
        return new ColorImageMappingResponse(value.productId(), value.colorRaw(), value.imageUrl());
    }
}
