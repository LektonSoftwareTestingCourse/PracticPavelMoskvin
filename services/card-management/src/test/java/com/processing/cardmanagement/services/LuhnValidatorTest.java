package com.processing.cardmanagement.services;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Алгоритм Луна (TC-CMS-02, TC-CMS-24): проверка контрольной цифры и генерация PAN по BIN.
 */
class LuhnValidatorTest {

    private final LuhnValidator luhnValidator = new LuhnValidator();

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"4111111111111111", "4000000000000002", "4000001234567899", "5500005555555559"})
    void shouldAcceptPanWithCorrectCheckDigit(String pan) {
        // Act
        boolean valid = luhnValidator.isValid(pan);

        // Assert
        assertThat(valid).isTrue();
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"4111111111111112", "4000001234567890", "4000001234567898"})
    void shouldRejectPanWithWrongCheckDigit(String pan) {
        // Act
        boolean valid = luhnValidator.isValid(pan);

        // Assert
        assertThat(valid).isFalse();
    }

    @RepeatedTest(20)
    void shouldGenerateSixteenDigitLuhnValidPanStartingWithBin() {
        // Act
        String pan = luhnValidator.generatePan("400002");

        // Assert
        assertThat(pan).matches("\\d{16}").startsWith("400002");
        assertThat(luhnValidator.isValid(pan)).isTrue();
    }
}
