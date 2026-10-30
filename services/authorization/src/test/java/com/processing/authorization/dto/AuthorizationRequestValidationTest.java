package com.processing.authorization.dto;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.RollbackRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-AUTH-28, TC-P3-20 (часть Authorization): правила Bean Validation на входных DTO,
 * проверяемые {@link Validator} без поднятия Spring.
 */
class AuthorizationRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void shouldHaveNoViolationsWhenRequestIsValid() {
        // Act
        Set<ConstraintViolation<AuthorizationRequest>> violations = validator.validate(requestWithAmount("1000"));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "0.01"})
    void shouldAcceptZeroOrPositiveAmount(String amount) {
        // Act
        Set<ConstraintViolation<AuthorizationRequest>> violations = validator.validate(requestWithAmount(amount));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"-1", "-0.01"})
    void shouldRejectNegativeAmountWithSingleViolationOnAmount(String amount) {
        // Act
        Set<ConstraintViolation<AuthorizationRequest>> violations = validator.validate(requestWithAmount(amount));

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("amount");
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"400000123456789", "40000012345678901"})
    void shouldRejectPanWhenLengthIsNotSixteen(String pan) {
        // Act
        Set<ConstraintViolation<AuthorizationRequest>> violations = validator.validate(requestWithPan(pan));

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("pan");
    }

    @ParameterizedTest(name = "rrn={0}")
    @ValueSource(strings = {"61234567890", "6123456789012", "61234567890A"})
    void shouldRejectRollbackWhenRrnIsNotTwelveDigits(String rrn) {
        // Act
        Set<ConstraintViolation<RollbackRequest>> violations =
                validator.validate(new RollbackRequest(rrn, "4000001234567890", BigDecimal.ONE));

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("rrn");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "-1"})
    void shouldRejectRollbackWhenAmountIsNotPositive(String amount) {
        // Act
        Set<ConstraintViolation<RollbackRequest>> violations =
                validator.validate(new RollbackRequest("612345678901", "4000001234567890", new BigDecimal(amount)));

        // Assert
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("amount");
    }

    private static AuthorizationRequest requestWithAmount(String amount) {
        return request("4000001234567890", new BigDecimal(amount));
    }

    private static AuthorizationRequest requestWithPan(String pan) {
        return request(pan, new BigDecimal("1000"));
    }

    private static AuthorizationRequest request(String pan, BigDecimal amount) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000", amount, "643",
                Instant.parse("2026-10-30T10:00:00Z"), "TERM0001", "POS", "MERCH0000000002",
                "5045", "ACQ002", "ISS001");
    }
}
