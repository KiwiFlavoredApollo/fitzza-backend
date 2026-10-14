package com.fitzza.community.dto;

import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.domain.VoteOptionSource;

public record VoteOptionResponse(
        Long voteOptionId,
        String label,
        VoteOptionSource source,
        VoteItemType itemType,
        Long itemId,
        String imageUrl,
        String name,
        Integer price) {

    public static VoteOptionResponse from(VoteOption option) {
        return new VoteOptionResponse(
                option.getId(),
                option.getLabel(),
                option.getSource(),
                option.getItemType(),
                toLongOrNull(option.getItemId()),
                option.getImageUrl(),
                option.getSnapshotName(),
                option.getSnapshotPrice());
    }

    // 저장은 문자열이다. 숫자 ID만 받기 전에 저장된 값이 숫자가 아니면 null로 내보낸다.
    private static Long toLongOrNull(String itemId) {
        if (itemId == null) {
            return null;
        }
        try {
            return Long.valueOf(itemId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
