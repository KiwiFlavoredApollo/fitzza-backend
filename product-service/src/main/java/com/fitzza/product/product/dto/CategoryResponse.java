package com.fitzza.product.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "카테고리 노드. 상품 목록은 이 code를 category로 조회한다. 길이 3은 대분류, 6은 중분류, 9는 소분류다.")
public record CategoryResponse(
        @Schema(description = "카테고리 코드. 코드가 없는 카테고리는 null", example = "120001") String code,
        @Schema(description = "카테고리 이름", example = "상의") String name,
        @Schema(description = "하위 카테고리 목록") List<CategoryResponse> children) {
}
