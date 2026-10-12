package com.fitzza.notification.exception;

public class NotificationApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public NotificationApiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
