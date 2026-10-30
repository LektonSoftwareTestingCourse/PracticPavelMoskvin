package com.processing.exception;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сообщения исключений Switch попадают в логи - проверяем, что в них есть STAN и причина.
 */
class SwitchExceptionsTest {

    @Test
    void shouldIncludeStanAttemptsAndLastErrorWhenAuthorizationIsUnavailable() {
        // Act
        AuthorizationException exception = new AuthorizationException("000001", 3, "Read timed out");

        // Assert
        assertThat(exception.getStan()).isEqualTo("000001");
        assertThat(exception).hasMessage(
                "Authorization service unavailable for STAN=000001 after 3 attempts. Last error: Read timed out");
    }

    @Test
    void shouldUseUnknownAsLastErrorWhenCauseHasNoMessage() {
        // Act
        AuthorizationException exception = new AuthorizationException("000001", 3, null);

        // Assert
        assertThat(exception).hasMessageEndingWith("Last error: unknown");
    }

    @Test
    void shouldIncludeStanAndTransactionIdWhenLoggerIsUnavailable() {
        // Arrange
        UUID transactionId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Act
        LoggerException exception = new LoggerException("000001", transactionId, 3);

        // Assert
        assertThat(exception.getStan()).isEqualTo("000001");
        assertThat(exception).hasMessage(
                "Logger unavailable for TX 000001 (id=00000000-0000-0000-0000-000000000001) after 3 attempts");
    }

    @Test
    void shouldIncludeBinWhenBinIsUnknown() {
        // Act
        UnknownBinException exception = new UnknownBinException("999999");

        // Assert
        assertThat(exception).hasMessage("Unknown BIN '999999'");
    }
}
