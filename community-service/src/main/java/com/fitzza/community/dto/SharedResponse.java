package com.fitzza.community.dto;

import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;

// 공유 글의 공유 대상. snapshot은 공유한 순간의 우승 상품 정보다.
public record SharedResponse(SharedType type, Long id, Snapshot snapshot) {

    public static SharedResponse of(Post post) {
        if (!post.isShared()) {
            return null;
        }
        SharedSnapshot snapshot = post.getSharedSnapshot();
        return new SharedResponse(
                post.getSharedType(),
                post.getSharedId(),
                snapshot == null
                        ? null
                        : new Snapshot(
                                snapshot.getProductId(),
                                snapshot.getBrandName(),
                                snapshot.getProductName(),
                                snapshot.getPrice(),
                                snapshot.getImageUrl()));
    }

    public record Snapshot(Long productId, String brandName, String productName, Integer price, String imageUrl) {
    }
}
