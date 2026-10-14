package com.fitzza.product.product.dto;

import org.springframework.data.domain.Sort;

public enum ProductSortType {
    LATEST(Sort.by(Sort.Direction.DESC, "productId")),
    LOW_PRICE(Sort.by(Sort.Direction.ASC, "price").and(Sort.by(Sort.Direction.DESC, "productId")));

    private final Sort sort;

    ProductSortType(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }

    /** 없거나 알 수 없는 값은 최신순으로 본다. */
    public static ProductSortType from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        try {
            return ProductSortType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return LATEST;
        }
    }
}
