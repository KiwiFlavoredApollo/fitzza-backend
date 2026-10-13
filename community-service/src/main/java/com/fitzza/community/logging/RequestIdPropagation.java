package com.fitzza.community.logging;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

// user·notification·product를 호출할 때 지금 처리 중인 요청의 ID를 함께 보내, 받는 서비스 로그에도 같은 ID가 찍히게 한다.
// 스케줄러처럼 요청 없이 도는 호출은 ID가 없으므로 보내지 않는다(받는 서비스가 새로 만든다).
public class RequestIdPropagation implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            request.getHeaders().set(RequestIdFilter.REQUEST_ID_HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
