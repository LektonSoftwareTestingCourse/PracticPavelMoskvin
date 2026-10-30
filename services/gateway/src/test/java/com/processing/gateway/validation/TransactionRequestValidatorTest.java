package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationRequest.AuthorizationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-P3-07 (REQ-GW-01): Gateway отклоняет невалидный запрос до отправки в Switch.
 * Эталонный запрос валиден; каждая копия нарушает ровно одно правило.
 */
class TransactionRequestValidatorTest {

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    void shouldAcceptReferenceRequest() {
        // Act + Assert
        assertThatCode(() -> validator.validate(validRequest().build())).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptRequestWithoutOptionalTerminalTypeAndIssuerId() {
        // Arrange: issuerId проставляет Switch, terminalType не обязателен
        AuthorizationRequest request = validRequest().terminalType(null).issuerId(null).build();

        // Act + Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMissingBody() {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @ParameterizedTest(name = "{0} = ''{1}''")
    @MethodSource("missingRequiredFields")
    void shouldRejectRequestWhenRequiredFieldIsMissingOrBlank(
            String field, String value, UnaryOperator<AuthorizationRequestBuilder> mutation) {
        // Arrange
        AuthorizationRequest request = mutation.apply(validRequest()).build();

        // Act + Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + field + "' is required");
    }

    static Stream<Arguments> missingRequiredFields() {
        return Stream.of(
                missing("mti", null, b -> b.mti(null)),
                missing("mti", " ", b -> b.mti(" ")),
                missing("stan", null, b -> b.stan(null)),
                missing("pan", "", b -> b.pan("")),
                missing("processingCode", null, b -> b.processingCode(null)),
                missing("currencyCode", null, b -> b.currencyCode(null)),
                missing("transmissionDateTime", null, b -> b.transmissionDateTime(null)),
                missing("terminalId", null, b -> b.terminalId(null)),
                missing("merchantId", " ", b -> b.merchantId(" ")),
                missing("mcc", null, b -> b.mcc(null)),
                missing("acquirerId", null, b -> b.acquirerId(null)),
                missing("amount", null, b -> b.amount(null)));
    }

    @ParameterizedTest(name = "mti={0}")
    @ValueSource(strings = {"0400", "0110", "100"})
    void shouldRejectMtiOtherThanAuthorizationRequest(String mti) {
        // Arrange: в том числе reversal 0400 - через Gateway он невозможен
        AuthorizationRequest request = validRequest().mti(mti).build();

        // Act + Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"400000123456789", "40000012345678901", "400000123456789A", "4000 0012345678"})
    void shouldRejectPanWhenItIsNotSixteenDigits(String pan) {
        // Arrange
        AuthorizationRequest request = validRequest().pan(pan).build();

        // Act + Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "0.00", "-0.01", "-1000"})
    void shouldRejectAmountWhenItIsNotPositive(String amount) {
        // Arrange
        AuthorizationRequest request = validRequest().amount(new BigDecimal(amount)).build();

        // Act + Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0.01", "1", "999999999.99"})
    void shouldAcceptPositiveAmount(String amount) {
        // Arrange
        AuthorizationRequest request = validRequest().amount(new BigDecimal(amount)).build();

        // Act + Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "currencyCode={0}")
    @ValueSource(strings = {"64", "6430"})
    void shouldRejectCurrencyCodeWhenLengthIsNotThree(String currencyCode) {
        // Arrange
        AuthorizationRequest request = validRequest().currencyCode(currencyCode).build();

        // Act + Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc={0}")
    @ValueSource(strings = {"54A1", "541", "54111"})
    void shouldRejectMccWhenItIsNotFourDigits(String mcc) {
        // Arrange
        AuthorizationRequest request = validRequest().mcc(mcc).build();

        // Act + Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    private static Arguments missing(String field, String value, UnaryOperator<AuthorizationRequestBuilder> mutation) {
        return Arguments.of(field, value, mutation);
    }

    private static AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan("4000001234567890")
                .processingCode("000000")
                .amount(new BigDecimal("1000"))
                .currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-10-30T10:00:00Z"))
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH0000000002")
                .mcc("5045")
                .acquirerId("ACQ002")
                .issuerId("ISS001");
    }
}
