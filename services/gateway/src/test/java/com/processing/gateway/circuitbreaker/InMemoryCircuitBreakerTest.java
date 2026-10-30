package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Circuit breaker Gateway: CLOSED -> OPEN после threshold отказов -> HALF_OPEN с одной пробой.
 * Время управляется fake-часами.
 */
class InMemoryCircuitBreakerTest {

    private static final String SERVICE = "switch";
    private static final String OTHER_SERVICE = "cardManagement";
    private static final Duration OPEN_DURATION = Duration.ofSeconds(10);
    private static final int FAILURE_THRESHOLD = 3;

    private MutableClock clock;
    private InMemoryCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
        circuitBreaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, FAILURE_THRESHOLD, clock);
    }

    @Test
    void shouldAllowRequestsWhenNoFailuresRecorded() {
        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldStayClosedWhenFailuresAreBelowThreshold() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD - 1);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldOpenAndRejectWhenFailureThresholdIsReached() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);

        // Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepRejectingUntilOpenDurationElapses() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);

        // Act
        clock.advance(OPEN_DURATION.minusMillis(1));
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldAllowSingleProbeWhenOpenDurationElapsed() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);
        clock.advance(OPEN_DURATION);

        // Act
        boolean probe = circuitBreaker.allowRequest(SERVICE);
        boolean concurrent = circuitBreaker.allowRequest(SERVICE);

        // Assert
        assertThat(probe).isTrue();
        assertThat(concurrent).isFalse();
    }

    @Test
    void shouldCloseWhenProbeSucceeds() {
        // Arrange
        openAndStartProbe();

        // Act
        circuitBreaker.recordSuccess(SERVICE);

        // Assert
        assertThat(circuitBreaker.allowRequest(SERVICE)).isTrue();
        assertThat(circuitBreaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldReopenImmediatelyWhenProbeFails() {
        // Arrange
        openAndStartProbe();

        // Act
        circuitBreaker.recordFailure(SERVICE);

        // Assert
        assertThat(circuitBreaker.allowRequest(SERVICE)).isFalse();
    }

    @Test
    void shouldAllowAnotherProbeWhenProbeSlotIsReleased() {
        // Arrange
        openAndStartProbe();

        // Act
        circuitBreaker.releaseRequest(SERVICE);

        // Assert
        assertThat(circuitBreaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldResetFailureCounterWhenCallSucceeds() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD - 1);
        circuitBreaker.recordSuccess(SERVICE);

        // Act
        recordFailures(FAILURE_THRESHOLD - 1);

        // Assert
        assertThat(circuitBreaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldTrackEachDownstreamServiceIndependently() {
        // Arrange
        recordFailures(FAILURE_THRESHOLD);

        // Act
        boolean otherAllowed = circuitBreaker.allowRequest(OTHER_SERVICE);

        // Assert
        assertThat(otherAllowed).isTrue();
        assertThat(circuitBreaker.allowRequest(SERVICE)).isFalse();
    }

    private void recordFailures(int count) {
        IntStream.range(0, count).forEach(i -> circuitBreaker.recordFailure(SERVICE));
    }

    private void openAndStartProbe() {
        recordFailures(FAILURE_THRESHOLD);
        clock.advance(OPEN_DURATION);
        circuitBreaker.allowRequest(SERVICE);
    }

    /** Fake-часы, которые двигает тест. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
