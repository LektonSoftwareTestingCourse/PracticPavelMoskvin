package com.processing.gateway.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Маскирование чувствительных данных в логах Gateway: PAN -> первые 6 + **** + последние 4, CVV -> ***.
 */
class LogDataMaskerTest {

    private final LogDataMasker masker = new LogDataMasker();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "{\"pan\":\"4000001234567890\"}          | {\"pan\":\"400000****7890\"}",
        "{\"pan\" : \"4000001234567890\"}        | {\"pan\":\"400000****7890\"}",
        "{\"PAN\":\"4000001234567890\"}          | {\"pan\":\"400000****7890\"}",
        "{\"pan\":\"4000001234567890123\"}       | {\"pan\":\"400000****0123\"}"
    })
    void shouldMaskPanKeepingBinAndLastFourDigits(String json, String expected) {
        // Act
        String masked = masker.maskData(json);

        // Assert
        assertThat(masked).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "{\"cvv\":\"123\"}  | {\"cvv\":\"***\"}",
        "{\"CVV\":\"1234\"} | {\"cvv\":\"***\"}"
    })
    void shouldMaskCvvCompletely(String json, String expected) {
        // Act
        String masked = masker.maskData(json);

        // Assert
        assertThat(masked).isEqualTo(expected);
    }

    @Test
    void shouldMaskEveryPanAndCvvInPayload() {
        // Arrange
        String payload = "[{\"pan\":\"4000001234567890\",\"cvv\":\"123\"},{\"pan\":\"4000011111111111\"}]";

        // Act
        String masked = masker.maskData(payload);

        // Assert
        assertThat(masked)
                .isEqualTo("[{\"pan\":\"400000****7890\",\"cvv\":\"***\"},{\"pan\":\"400001****1111\"}]")
                .doesNotContain("4000001234567890", "\"123\"");
    }

    @Test
    void shouldLeavePayloadWithoutSensitiveFieldsUnchanged() {
        // Arrange
        String payload = "{\"stan\":\"000001\",\"amount\":1000,\"terminalId\":\"TERM0001\"}";

        // Act
        String masked = masker.maskData(payload);

        // Assert
        assertThat(masked).isEqualTo(payload);
    }
}
