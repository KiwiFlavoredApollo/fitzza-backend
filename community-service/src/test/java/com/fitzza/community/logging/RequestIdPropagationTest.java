package com.fitzza.community.logging;

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

    @Test
    void asyncTasksKeepTheIdOfTheThreadThatSubmittedThem() throws InterruptedException {
        MDC.put(RequestIdFilter.MDC_KEY, "gateway-id-0001");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = new MdcTaskDecorator().decorate(() -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));
        MDC.clear();

        Thread worker = new Thread(task);
        worker.start();
        worker.join();

        assertThat(seen.get()).isEqualTo("gateway-id-0001");
    }

    private String sentId() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://user-service/users"));
        AtomicReference<String> sent = new AtomicReference<>();
        propagation.intercept(request, new byte[0], (req, body) -> {
            sent.set(req.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
            return new MockClientHttpResponse(new byte[0], 200);
        });
        return sent.get();
    }
}
