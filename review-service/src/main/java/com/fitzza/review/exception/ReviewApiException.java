package com.fitzza.review.exception;

public class ReviewApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ReviewApiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
