package com.fitzza.community.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력값을 확인해주세요."),
    INVALID_PARENT_COMMENT(HttpStatus.BAD_REQUEST, "답글을 달 수 없는 댓글입니다."),
    NOT_VOTE_POST(HttpStatus.BAD_REQUEST, "투표 게시글이 아닙니다."),
    INVALID_VOTE_OPTIONS(HttpStatus.BAD_REQUEST, "투표 선택지를 확인해주세요."),
    INVALID_VOTE_OPTION(HttpStatus.BAD_REQUEST, "이 게시글의 선택지가 아닙니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    NOT_AUTHOR(HttpStatus.FORBIDDEN, "작성자만 할 수 있습니다."),
    NOT_WISHLISTED_ITEM(HttpStatus.FORBIDDEN, "찜한 상품만 투표 선택지로 고를 수 있습니다."),
    NOT_SHARED_OWNER(HttpStatus.FORBIDDEN, "내 결과만 공유할 수 있습니다."),
    NOT_RECOMMENDED_ITEM(HttpStatus.FORBIDDEN, "내가 받은 추천만 투표 선택지로 고를 수 있습니다."),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    SHARED_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "공유할 대상을 찾을 수 없습니다."),
    SHARED_TARGET_NOT_READY(HttpStatus.CONFLICT, "아직 완료되지 않은 결과는 공유할 수 없습니다."),
    VOTE_CLOSED(HttpStatus.CONFLICT, "마감된 투표입니다."),
    VOTE_OPTIONS_LOCKED(HttpStatus.CONFLICT, "투표가 시작된 글은 선택지를 바꿀 수 없습니다."),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "다른 요청과 겹쳐 처리하지 못했습니다. 새로고침 후 다시 시도해주세요."),
    PRODUCT_LOOKUP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "상품 정보를 확인하지 못했습니다. 잠시 후 다시 시도해주세요."),
    SHARE_LOOKUP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "공유 대상을 확인하지 못했습니다. 잠시 후 다시 시도해주세요."),
    RECOMMENDATION_LOOKUP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "추천 결과를 확인하지 못했습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
