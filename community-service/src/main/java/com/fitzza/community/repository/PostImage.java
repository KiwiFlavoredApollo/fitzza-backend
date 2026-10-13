package com.fitzza.community.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 목록 한 페이지의 글마다 썸네일 후보 이미지를 한 번에 가져오기 위한 조회 결과.
public record PostImage(Long postId, String imageUrl) {

    public static Map<Long, String> toMap(List<PostImage> images) {
        return images.stream()
                .collect(Collectors.toMap(PostImage::postId, PostImage::imageUrl, (first, second) -> first));
    }
}
