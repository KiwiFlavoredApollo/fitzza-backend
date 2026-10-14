package com.fitzza.review.domain;

import org.springframework.data.domain.Sort;

// 리뷰 목록 정렬(#65). 같은 별점끼리는 최신순, 같은 시각이면 나중에 쓴 리뷰가 먼저다.
public enum ReviewSort {
    LATEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    RATING_DESC(Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    RATING_ASC(Sort.by(Sort.Order.asc("rating"), Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final Sort sort;

    ReviewSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
