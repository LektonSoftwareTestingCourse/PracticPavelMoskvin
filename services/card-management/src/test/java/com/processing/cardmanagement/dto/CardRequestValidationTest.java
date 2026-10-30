package com.processing.cardmanagement.dto;

import com.processing.common.dto.cardmanagement.CreateCardRequest;
import com.processing.common.dto.cardmanagement.ReserveRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-P3-20 (часть CMS), TC-CMS-04...12, TC-CMS-31: правила Bean Validation на {@link CreateCardRequest}
 * и {@link ReserveRequest}. Эталон валиден; в каждой копии меняется ровно одно поле.
 *
 * <p>Валидаторы создаются так же, как в приложении: Spring {@code SpringConstraintValidatorFactory}
 * допускает непубличный конструктор, а у {@code DigitsOnlyValidator} он protected.</p>
 */
class CardRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.byDefaultProvider()
                .configure()
                .constraintValidatorFactory(new ReflectiveConstraintValidatorFactory())
                .buildValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void shouldHaveNoViolationsWhenCreateCardRequestIsValid() {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(validCreateRequest());

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "bin={0}")
    @ValueSource(strings = {"40000", "4000000", "40000A"})
    void shouldRejectBinWhenItIsNotExactlySixDigits(String bin) {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                bin, "IVAN IVANOV", "643", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("bin");
    }

    @ParameterizedTest(name = "cardholderName=''{0}''")
    @ValueSource(strings = {"Ivan Ivanov", "IVAN IVANOV 2", "IVAN_IVANOV"})
    void shouldRejectCardholderNameWithLowercaseDigitsOrSymbols(String cardholderName) {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                "400000", cardholderName, "643", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("cardholderName");
    }

    @ParameterizedTest(name = "cardholderName=''{0}''")
    @ValueSource(strings = {"IVAN IVANOV", "ANNE-MARIE O'NEIL", "J. R. R. TOLKIEN"})
    void shouldAcceptCardholderNameWithUppercaseLettersSpacesDotsApostrophesAndHyphens(String cardholderName) {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                "400000", cardholderName, "643", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // Assert
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldRejectBlankCardholderName() {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                "400000", "   ", "643", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("cardholderName");
    }

    @ParameterizedTest(name = "currencyCode={0}")
    @ValueSource(strings = {"64", "6430", "RUB"})
    void shouldRejectCurrencyCodeWhenItIsNotThreeDigits(String currencyCode) {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                "400000", "IVAN IVANOV", currencyCode, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("currencyCode");
    }

    @Test
    void shouldRejectNegativeDailyLimit() {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                "400000", "IVAN IVANOV", "643", new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("dailyLimit");
    }

    @Test
    void shouldRejectNegativeMonthlyLimit() {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                "400000", "IVAN IVANOV", "643", BigDecimal.ZERO, new BigDecimal("-1"), BigDecimal.ZERO));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("monthlyLimit");
    }

    /** TC-CMS-11: ON-граница 0 для лимитов и баланса. */
    @Test
    void shouldAcceptZeroLimitsAndBalance() {
        // Act
        Set<ConstraintViolation<CreateCardRequest>> violations = validator.validate(new CreateCardRequest(
                "400000", "IVAN IVANOV", "643", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        // Assert
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldAcceptReserveRequestWithTwelveDigitRrnAndPositiveAmount() {
        // Act
        Set<ConstraintViolation<ReserveRequest>> violations =
                validator.validate(new ReserveRequest(BigDecimal.ONE, "612345678901"));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "rrn={0}")
    @ValueSource(strings = {"61234567890", "6123456789012", "61234"})
    void shouldRejectReserveRequestWhenRrnIsNotTwelveDigits(String rrn) {
        // Act
        Set<ConstraintViolation<ReserveRequest>> violations = validator.validate(new ReserveRequest(BigDecimal.ONE, rrn));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("rrn");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "-1"})
    void shouldRejectReserveRequestWhenAmountIsNotPositive(String amount) {
        // Act
        Set<ConstraintViolation<ReserveRequest>> violations =
                validator.validate(new ReserveRequest(new BigDecimal(amount), "612345678901"));

        // Assert
        assertThat(violatedFields(violations)).containsExactly("amount");
    }

    private static CreateCardRequest validCreateRequest() {
        return new CreateCardRequest(
                "400000", "IVAN IVANOV", "643",
                new BigDecimal("15000000"), new BigDecimal("300000000"), new BigDecimal("1000000000"));
    }

    private static <T> List<String> violatedFields(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).toList();
    }

    /** Создаёт валидаторы через конструктор без аргументов любой видимости - как Spring. */
    private static final class ReflectiveConstraintValidatorFactory implements ConstraintValidatorFactory {

        @Override
        public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
            try {
                Constructor<T> constructor = key.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot instantiate " + key.getName(), e);
            }
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            // stateless validators - nothing to release
        }
    }
}
