package com.processing.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Rate limit на {@code POST /api/transactions}: сверх лимита - 429 с Retry-After, остальные пути не ограничиваются.
 */
class TransactionRateLimitFilterTest {

    private static final String CLIENT_IP = "203.0.113.7";

    private final AtomicInteger forwarded = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.incrementAndGet();
        return Mono.empty();
    };

    private SimpleMeterRegistry meterRegistry;
    private TransactionRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofMinutes(10), 100, () -> 0L);
        filter = new TransactionRateLimitFilter(
                limiter, new ClientIpResolver(), new ObjectMapper(), new GatewayMetrics(meterRegistry));
    }

    @Test
    void shouldForwardTransactionWhenWithinLimit() {
        // Arrange
        MockServerWebExchange exchange = transactionFrom(CLIENT_IP);

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded).hasValue(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldRespond429WithRetryAfterWhenLimitIsExceeded() {
        // Arrange
        filter.filter(transactionFrom(CLIENT_IP), chain).block();
        MockServerWebExchange second = transactionFrom(CLIENT_IP);

        // Act
        filter.filter(second, chain).block();

        // Assert
        assertThat(forwarded).hasValue(1);
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(second.getResponse().getBodyAsString().block()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(meterRegistry.get("gateway.requests.rejected").tag("reason", "rate_limit").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldLimitClientsIndependently() {
        // Arrange
        filter.filter(transactionFrom(CLIENT_IP), chain).block();

        // Act
        filter.filter(transactionFrom("198.51.100.1"), chain).block();

        // Assert
        assertThat(forwarded).hasValue(2);
    }

    @Test
    void shouldNotLimitRequestsToOtherEndpoints() {
        // Arrange
        filter.filter(transactionFrom(CLIENT_IP), chain).block();
        MockServerWebExchange cards = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/cards").header("X-Forwarded-For", CLIENT_IP));

        // Act
        filter.filter(cards, chain).block();

        // Assert
        assertThat(forwarded).hasValue(2);
        assertThat(cards.getResponse().getStatusCode()).isNull();
    }

    private static MockServerWebExchange transactionFrom(String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions")
                .header("X-Forwarded-For", ip)
                .body("{}"));
    }
}
