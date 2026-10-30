package com.processing.gateway.shutdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graceful shutdown: после начала остановки новые запросы получают 503 с Retry-After = drain period.
 */
class GracefulShutdownFilterTest {

    private final AtomicInteger forwarded = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.incrementAndGet();
        return Mono.empty();
    };

    private GracefulShutdownState state;
    private ShutdownProperties properties;
    private GracefulShutdownFilter filter;

    @BeforeEach
    void setUp() {
        state = new GracefulShutdownState();
        properties = new ShutdownProperties();
        filter = new GracefulShutdownFilter(
                state, properties, new ObjectMapper(), new GatewayMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void shouldForwardRequestsWhileGatewayIsRunning() {
        // Arrange
        MockServerWebExchange exchange = exchange();

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded).hasValue(1);
        assertThat(state.isShuttingDown()).isFalse();
    }

    @Test
    void shouldRespond503WithoutForwardingWhenShutdownStarted() {
        // Arrange
        state.startShutdown();
        MockServerWebExchange exchange = exchange();

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded).hasValue(0);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("SERVICE_UNAVAILABLE")
                .contains("Gateway is shutting down");
    }

    @ParameterizedTest(name = "drainPeriod={0} -> Retry-After {1}")
    @CsvSource({
        "PT0S,   1",
        "PT0.5S, 1",
        "PT1S,   1",
        "PT45S,  45"
    })
    void shouldAdvertiseRetryAfterOfAtLeastOneSecond(Duration drainPeriod, long expectedRetryAfter) {
        // Arrange
        properties.setDrainPeriod(drainPeriod);

        // Act
        long retryAfter = properties.retryAfterSeconds();

        // Assert
        assertThat(retryAfter).isEqualTo(expectedRetryAfter);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").body("{}"));
    }
}
