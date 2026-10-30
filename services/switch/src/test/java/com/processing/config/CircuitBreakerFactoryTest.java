package com.processing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Circuit breaker Switch -> Authorization собирается из секции {@code switch.circuit-breaker.authorization}.
 */
class CircuitBreakerFactoryTest {

    @Test
    void shouldApplyConfiguredThresholdsWhenCreatingCircuitBreaker() {
        // Act
        CircuitBreaker circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties(2));

        // Assert
        CircuitBreakerConfig config = circuitBreaker.getCircuitBreakerConfig();
        assertThat(circuitBreaker.getName()).isEqualTo("authorization");
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getSlidingWindowSize()).isEqualTo(4);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(2);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(30).toMillis());
    }

    @Test
    void shouldOpenWhenFailureRateReachesThresholdAfterMinimumCalls() {
        // Arrange
        CircuitBreaker circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties(2));

        // Act
        circuitBreaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("down"));
        circuitBreaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("down"));

        // Assert
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void shouldStayClosedWhenFailuresAreBelowMinimumNumberOfCalls() {
        // Arrange
        CircuitBreaker circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties(2));

        // Act
        circuitBreaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("down"));

        // Assert
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private static SwitchProperties properties(int minimumNumberOfCalls) {
        return new SwitchProperties(
                "1.0.0", null, null, null, null, null, null,
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 4, minimumNumberOfCalls, 30_000)));
    }
}
