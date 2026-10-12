package com.fitzza.notification.dto;

// product-service와 같은 모양으로 HTTP 상태 코드를 본문에도 담는다.
public record ErrorResponse(int status, String code, String message) {
}
