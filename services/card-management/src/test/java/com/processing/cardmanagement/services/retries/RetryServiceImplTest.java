package com.processing.cardmanagement.services.retries;

import com.processing.cardmanagement.exceptions.OutOfRetriesException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Повторы при коллизии PAN: maxRetries - число ПОВТОРОВ, всего попыток maxRetries + 1.
 */
class RetryServiceImplTest {

    private final RetryServiceImpl retryService = new RetryServiceImpl();

    @Test
    void shouldReturnResultWithoutRetriesWhenFirstAttemptSucceeds() {
        // Arrange
        AtomicInteger attempts = new AtomicInteger();

        // Act
        String result = retryService.supply(3, () -> "attempt-" + attempts.incrementAndGet());

        // Assert
        assertThat(result).isEqualTo("attempt-1");
    }

    @ParameterizedTest(name = "maxRetries={0}, failures before success={1}")
    @CsvSource({
        "1, 1",
        "3, 2",
        "3, 3"
    })
    void shouldReturnResultWhenSuccessHappensWithinRetryBudget(int maxRetries, int failuresBeforeSuccess) {
        // Arrange
        AtomicInteger attempts = new AtomicInteger();

        // Act
        Integer result = retryService.supply(maxRetries, failingTimes(failuresBeforeSuccess, attempts));

        // Assert
        assertThat(result).isEqualTo(failuresBeforeSuccess + 1);
    }

    @ParameterizedTest(name = "maxRetries={0}")
    @CsvSource({"0", "1", "3"})
    void shouldThrowOutOfRetriesAfterMaxRetriesPlusOneAttempts(int maxRetries) {
        // Arrange
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException collision = new IllegalStateException("pan collision");

        // Act + Assert
        assertThatThrownBy(() -> retryService.supply(maxRetries, () -> {
            attempts.incrementAndGet();
            throw collision;
        }))
                .isInstanceOf(OutOfRetriesException.class)
                .hasMessageContaining("pan collision")
                .extracting(e -> ((OutOfRetriesException) e).getReason())
                .isSameAs(collision);
        assertThat(attempts).hasValue(maxRetries + 1);
    }

    private static Supplier<Integer> failingTimes(int failures, AtomicInteger attempts) {
        return () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= failures) {
                throw new IllegalStateException("collision #" + attempt);
            }
            return attempt;
        };
    }
}
