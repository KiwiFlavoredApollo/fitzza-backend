package com.fitzza.user.exception;

import com.fitzza.user.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String UNSUPPORTED_REQUEST_MESSAGE = "처리할 수 없는 요청입니다.";

    /**
     * 사용자 서비스의 업무 오류를 해당 HTTP 상태와 공통 오류 응답으로 변환한다.
     */
    @ExceptionHandler(UserApiException.class)
    public ResponseEntity<ErrorResponse> handleUserApiException(UserApiException exception) {
        return toResponse(exception.getErrorCode(), exception.getMessage());
    }

    // 게이트웨이를 거치지 않았거나 토큰 없이 들어온 요청에는 사용자 헤더가 없다.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingUserHeader() {
        return toResponse(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.getMessage());
    }

    /**
     * 요청 본문 검증 실패를 첫 필드 오류 메시지와 함께 HTTP 400으로 반환한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    /**
     * 필수 쿼리 매개변수 누락, 읽을 수 없는 본문, 형식이 맞지 않는 경로·쿼리 값을 INVALID_REQUEST 응답으로 변환한다.
     */
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest() {
        return toResponse(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage());
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

    /**
     * 오류 코드의 HTTP 상태와 지정된 메시지를 사용해 공통 응답을 구성한다.
     */
    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getStatus()).body(new ErrorResponse(
                errorCode.getStatus().value(), errorCode.name(), message));
    }
}
