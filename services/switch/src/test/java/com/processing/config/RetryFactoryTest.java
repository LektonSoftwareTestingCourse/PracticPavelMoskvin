package com.processing.config;

import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Политика повторов Switch: число попыток и exponential backoff 1s -> 2s -> 4s из конфигурации.
 */
class RetryFactoryTest {

    private static final List<Long> BACKOFF = List.of(1000L, 2000L, 4000L);

    @ParameterizedTest(name = "failedAttempts={0} -> {1} ms")
    @CsvSource({
        "0, 1000",
        "1, 1000",
        "2, 2000",
        "3, 4000",
        "7, 4000"
    })
    void shouldPickBackoffByFailedAttemptAndClampToLastValue(int failedAttempts, long expectedDelayMs) {
        // Arrange
        Retry retry = RetryFactory.authorizationRetry(propertiesWith(3, BACKOFF));

        // Act
        long delay = retry.getRetryConfig().getIntervalFunction().apply(failedAttempts);

        // Assert
        assertThat(delay).isEqualTo(expectedDelayMs);
    }

    @ParameterizedTest(name = "backoff={0}")
    @NullAndEmptySource
    void shouldUseMinimalIntervalWhenBackoffIsNotConfigured(List<Long> backoff) {
        // Arrange
        Retry retry = RetryFactory.loggerRetry(propertiesWith(3, backoff));

        // Act
        long delay = retry.getRetryConfig().getIntervalFunction().apply(1);

        // Assert
        assertThat(delay).isEqualTo(1L);
    }

    @Test
    void shouldNotAllowZeroDelayWhenBackoffContainsZero() {
        // Arrange
        Retry retry = RetryFactory.authorizationRetry(propertiesWith(3, List.of(0L)));

        // Act
        long delay = retry.getRetryConfig().getIntervalFunction().apply(1);

        // Assert
        assertThat(delay).isEqualTo(1L);
    }

    @Test
    void shouldNameRetriesByDownstreamService() {
        // Act
        Retry authorization = RetryFactory.authorizationRetry(propertiesWith(3, BACKOFF));
        Retry logger = RetryFactory.loggerRetry(propertiesWith(3, BACKOFF));

        // Assert
        assertThat(authorization.getName()).isEqualTo("authorization");
        assertThat(logger.getName()).isEqualTo("logger");
    }

    @Test
    void shouldStopAfterConfiguredNumberOfAttemptsWhenCallKeepsFailing() {
        // Arrange
        Retry retry = RetryFactory.authorizationRetry(propertiesWith(3, List.of(1L)));
        AtomicInteger calls = new AtomicInteger();

        // Act + Assert
        assertThatThrownBy(() -> Retry.decorateCallable(retry, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        }).call()).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(3);
    }

    private static SwitchProperties propertiesWith(int maxAttempts, List<Long> backoff) {
        return new SwitchProperties(
                "1.0.0", null, null, null, null, null,
                new SwitchProperties.RetryProperties(maxAttempts, backoff), null);
    }
}
