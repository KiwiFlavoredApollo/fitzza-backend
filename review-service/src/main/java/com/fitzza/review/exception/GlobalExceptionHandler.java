package com.fitzza.review.exception;

import com.fitzza.review.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String UNSUPPORTED_REQUEST_MESSAGE = "처리할 수 없는 요청입니다.";

    @ExceptionHandler(ReviewApiException.class)
    public ResponseEntity<ErrorResponse> handleReviewApiException(ReviewApiException exception) {
        return toResponse(exception.getErrorCode(), exception.getMessage());
    }

    // 게이트웨이를 거치지 않았거나 토큰 없이 들어온 요청에는 사용자 헤더가 없다.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingUserHeader() {
        return toResponse(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        HandlerMethodValidationException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest() {
        return toResponse(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage());
    }

    // 같은 리뷰를 동시에 수정·삭제하면 늦게 끝난 쪽이 여기로 온다.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate() {
        return toResponse(ErrorCode.CONCURRENT_UPDATE, ErrorCode.CONCURRENT_UPDATE.getMessage());
    }

    // 스프링이 직접 던지는 요청 오류(없는 주소, 지원하지 않는 메서드 등)는 상태 코드를 그대로 두고 응답 모양만 맞춘다.
    // 그 밖의 예외는 원인이 응답에 드러나지 않도록 로그에만 남기고 500으로 답한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        if (exception instanceof org.springframework.web.ErrorResponse frameworkError) {
            HttpStatus status = HttpStatus.resolve(frameworkError.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                boolean invalidRequest = status == HttpStatus.BAD_REQUEST;
                return ResponseEntity.status(status)
                        .headers(frameworkError.getHeaders())
                        .body(new ErrorResponse(
                                status.value(),
                                invalidRequest ? ErrorCode.INVALID_REQUEST.name() : status.name(),
                                invalidRequest ? ErrorCode.INVALID_REQUEST.getMessage() : UNSUPPORTED_REQUEST_MESSAGE));
            }
        }
        log.error("처리되지 않은 예외가 발생했습니다.", exception);
        return toResponse(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getStatus()).body(new ErrorResponse(
                errorCode.getStatus().value(), errorCode.name(), message));
    }
}
