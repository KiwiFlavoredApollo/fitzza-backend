package com.fitzza.order.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthControllerTest {

    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);
    private final HealthController controller = new HealthController(availability);

    @Test
    void returnsUpWhenApplicationIsReady() {
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);

        ResponseEntity<HealthController.HealthResponse> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new HealthController.HealthResponse("UP", "UP", "UP"));
    }

    @Test
    void returnsServiceUnavailableWhenApplicationIsNotReady() {
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);

        ResponseEntity<HealthController.HealthResponse> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new HealthController.HealthResponse("NOT_READY", "UP", "DOWN"));
    }
}

