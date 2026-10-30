package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token bucket Gateway для {@code POST /api/transactions}: ёмкость, пополнение и изоляция ключей.
 * Время управляется fake-тикером - тесты детерминированы и не используют sleep.
 */
class InMemoryRateLimiterTest {

    private static final String KEY = "POST /api/transactions:10.0.0.1";
    private static final String OTHER_KEY = "POST /api/transactions:10.0.0.2";
    private static final long ONE_SECOND_NANOS = Duration.ofSeconds(1).toNanos();

    private final AtomicLong nanos = new AtomicLong(ONE_SECOND_NANOS);
    private final Ticker ticker = nanos::get;

    @ParameterizedTest(name = "capacity={0}")
    @CsvSource({"1", "5", "100"})
    void shouldAllowRequestsUpToCapacityAndRejectNextOne(int capacity) {
        // Arrange
        InMemoryRateLimiter limiter = limiter(capacity, 1);

        // Act
        List<Boolean> decisions = requests(limiter, KEY, capacity + 1);

        // Assert
        assertThat(decisions.subList(0, capacity)).containsOnly(true);
        assertThat(decisions.get(capacity)).isFalse();
    }

    @Test
    void shouldRefillTokensProportionallyToElapsedTime() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(10, 4);
        requests(limiter, KEY, 10);

        // Act: 0,5 с при 4 токенах/с = 2 токена
        nanos.addAndGet(ONE_SECOND_NANOS / 2);
        List<Boolean> decisions = requests(limiter, KEY, 3);

        // Assert
        assertThat(decisions).containsExactly(true, true, false);
    }

    @Test
    void shouldNotAccumulateTokensAboveCapacityWhileIdle() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(3, 100);
        requests(limiter, KEY, 1);

        // Act
        nanos.addAndGet(60 * ONE_SECOND_NANOS);
        List<Boolean> decisions = requests(limiter, KEY, 4);

        // Assert
        assertThat(decisions).containsExactly(true, true, true, false);
    }

    @Test
    void shouldKeepSeparateBucketsForDifferentClients() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 1);
        limiter.allowRequest(KEY);

        // Act
        boolean otherClientAllowed = limiter.allowRequest(OTHER_KEY);

        // Assert
        assertThat(otherClientAllowed).isTrue();
        assertThat(limiter.allowRequest(KEY)).isFalse();
    }

    @ParameterizedTest(name = "capacity={0}")
    @CsvSource({"0", "-5"})
    void shouldRejectEveryRequestWhenCapacityIsNotPositive(int capacity) {
        // Arrange
        InMemoryRateLimiter limiter = limiter(capacity, 100);

        // Act
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldNotRefillWhenClockDoesNotMoveForward() {
        // Arrange
        InMemoryRateLimiter limiter = limiter(1, 1000);
        limiter.allowRequest(KEY);

        // Act
        nanos.addAndGet(-ONE_SECOND_NANOS);
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldStartWithFullBucketWhenPreviousBucketExpired() {
        // Arrange
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofMinutes(10), 100, ticker);
        limiter.allowRequest(KEY);

        // Act
        nanos.addAndGet(Duration.ofMinutes(11).toNanos());
        limiter.cleanUp();
        boolean allowed = limiter.allowRequest(KEY);

        // Assert
        assertThat(allowed).isTrue();
    }

    private InMemoryRateLimiter limiter(int capacity, double refillPerSecond) {
        return InMemoryRateLimiter.forTesting(capacity, refillPerSecond, Duration.ofMinutes(10), 10_000, ticker);
    }

    private static List<Boolean> requests(InMemoryRateLimiter limiter, String key, int count) {
        return IntStream.range(0, count).mapToObj(i -> limiter.allowRequest(key)).toList();
    }
}
