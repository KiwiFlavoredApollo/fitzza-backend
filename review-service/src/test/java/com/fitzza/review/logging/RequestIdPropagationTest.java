package com.fitzza.review.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class RequestIdPropagationTest {

    private final RequestIdPropagation propagation = new RequestIdPropagation();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void sendsTheIdOfTheRequestBeingHandled() throws IOException {
        MDC.put(RequestIdFilter.MDC_KEY, "gateway-id-0001");

        assertThat(sentId()).isEqualTo("gateway-id-0001");
    }

    @Test
    void sendsNothingOutsideARequest() throws IOException {
        assertThat(sentId()).isNull();
    }

    private String sentId() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://order-service/order-items/1"));
        AtomicReference<String> sent = new AtomicReference<>();
        propagation.intercept(request, new byte[0], (req, body) -> {
            sent.set(req.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
            return new MockClientHttpResponse(new byte[0], 200);
        });
        return sent.get();
    }
}
