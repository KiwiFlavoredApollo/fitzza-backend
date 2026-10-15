package com.fitzza.community.dto;

import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.SharedItem;
import com.fitzza.community.domain.SharedSnapshot;
import com.fitzza.community.domain.SharedType;
import java.util.List;

// 공유 글의 공유 대상. snapshot과 items는 공유한 순간의 모습이다.
// WORLDCUP: snapshot에 우승 상품(productId·brandName·productName·price·imageUrl), items는 빈 목록.
// COMBO: snapshot에 총 가격(price)·첫 상품 이미지(imageUrl)·조화도 요약(summary), items에 코디 상품들.
// FITTING: snapshot에 결과 이미지(imageUrl), items에 입어 본 상품들.
public record SharedResponse(SharedType type, Long id, Snapshot snapshot, List<Item> items) {

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
                                snapshot.getImageUrl(),
                                snapshot.getSummary()),
                post.getSharedItems().stream().map(Item::from).toList());
    }

    public record Snapshot(
            Long productId, String brandName, String productName, Integer price, String imageUrl, String summary) {
    }

    public record Item(Long productId, String productName, Integer price, String imageUrl) {

        static Item from(SharedItem item) {
            return new Item(item.getProductId(), item.getProductName(), item.getPrice(), item.getImageUrl());
        }
    }
}
