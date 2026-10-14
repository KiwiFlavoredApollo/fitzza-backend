package com.fitzza.review.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void logsWithTheGatewayIdWhileHandlingTheRequestAndClearsItAfterwards() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "gateway-id-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seen.get()).isEqualTo("gateway-id-0001");
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("gateway-id-0001");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void createsAnIdWhenItIsMissingOrMalformed() {
        assertThat(UUID.fromString(RequestIdFilter.resolve(null))).isNotNull();
        assertThat(UUID.fromString(RequestIdFilter.resolve("bad id\nwith newline"))).isNotNull();
        assertThat(RequestIdFilter.resolve("gateway-id-0001")).isEqualTo("gateway-id-0001");
    }
}
