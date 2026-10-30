package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-P3-06 (REQ-CMS-05, REQ-CMS-09): инварианты доменной модели карты - резервирование, откат,
 * удаление, изменение лимитов.
 */
class CardTest {

    private static final String PAN = "4000001234567899";
    private static final String OTHER_PAN = "4000000000000002";
    private static final String RRN = "612345678901";
    private static final BigDecimal BALANCE = new BigDecimal("1000");
    private static final Instant CREATED_AT = Instant.parse("2025-01-01T00:00:00Z");

    @ParameterizedTest(name = "balance=1000, amount={0}")
    @ValueSource(strings = {"1", "999.99", "1000"})
    void shouldStartReservationWhenAmountDoesNotExceedBalance(BigDecimal amount) {
        // Arrange
        Card card = activeCard();

        // Act
        Reservation reservation = card.startReservation(amount, RRN);

        // Assert
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.id()).isNotNull();
    }

    @ParameterizedTest(name = "balance=1000, amount={0}")
    @ValueSource(strings = {"1000.01", "1001"})
    void shouldRejectReservationWhenAmountExceedsBalance(BigDecimal amount) {
        // Arrange
        Card card = activeCard();

        // Act + Assert
        assertThatThrownBy(() -> card.startReservation(amount, RRN))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        Card card = cardWithStatus(status);

        // Act + Assert
        assertThatThrownBy(() -> card.startReservation(BigDecimal.ONE, RRN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void shouldDecreaseBalanceAndKeepIdentityWhenReservationIsApplied() {
        // Arrange
        Card card = activeCard();
        Reservation reservation = card.startReservation(new BigDecimal("300"), RRN);

        // Act
        Card reserved = card.withReservation(reservation);

        // Assert
        assertThat(reserved.availableBalance()).isEqualByComparingTo("700");
        assertThat(reserved)
                .usingRecursiveComparison()
                .ignoringFields("availableBalance")
                .isEqualTo(card);
    }

    @Test
    void shouldRejectReservationOfAnotherCard() {
        // Arrange
        Card card = activeCard();
        Reservation foreign = new Reservation(OTHER_PAN, BigDecimal.ONE, RRN);

        // Act + Assert
        assertThatThrownBy(() -> card.withReservation(foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldIncreaseBalanceWhenRollbackIsApplied() {
        // Arrange
        Card card = activeCard();
        ReservationRollback rollback = rollbackOf(PAN, new BigDecimal("300"));

        // Act
        Card rolledBack = card.withRollback(rollback);

        // Assert
        assertThat(rolledBack.availableBalance()).isEqualByComparingTo("1300");
        assertThat(rolledBack.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldRejectRollbackOfAnotherCard() {
        // Arrange
        Card card = activeCard();

        // Act + Assert
        assertThatThrownBy(() -> card.withRollback(rollbackOf(OTHER_PAN, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"DELETED"})
    void shouldMarkCardAsDeletedWhenItIsNotDeletedYet(CardStatus status) {
        // Act
        Card deleted = cardWithStatus(status).deleted();

        // Assert
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
    }

    @Test
    void shouldRejectSecondDeletion() {
        // Arrange
        Card deleted = cardWithStatus(CardStatus.DELETED);

        // Act + Assert
        assertThatThrownBy(deleted::deleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Card already deleted");
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({
        "0,   0",
        "10,  10",
        "10,  11"
    })
    void shouldUpdateLimitsWhenTheyAreNonNegativeAndDailyNotAboveMonthly(BigDecimal daily, BigDecimal monthly) {
        // Act
        Card updated = activeCard().withData(CardStatus.BLOCKED, daily, monthly, BigDecimal.TEN);

        // Assert
        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo(daily);
        assertThat(updated.monthlyLimit()).isEqualByComparingTo(monthly);
        assertThat(updated.availableBalance()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(updated.pan()).isEqualTo(PAN);
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({
        "-1, 10",
        "0,  -1"
    })
    void shouldRejectNegativeLimits(BigDecimal daily, BigDecimal monthly) {
        // Act + Assert
        assertThatThrownBy(() -> activeCard().withData(CardStatus.ACTIVE, daily, monthly, BALANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Limit can not contain negative values");
    }

    @Test
    void shouldRejectDailyLimitLargerThanMonthly() {
        // Act + Assert
        assertThatThrownBy(() -> activeCard().withData(
                CardStatus.ACTIVE, BigDecimal.TEN, new BigDecimal("5"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Daily limit can not be larger than monthly limit");
    }

    @Test
    void shouldCreateCardFromDraftWithGeneratedPanIssuerAndExpiryAfterValidityPeriod() {
        // Arrange
        CardDraft draft = new CardDraft(
                "400000", "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("500"));

        // Act
        Card card = Card.fromDraft(PAN, "ISS001", 3, draft);

        // Assert
        assertThat(card.id()).isNotNull();
        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.issuerId()).isEqualTo("ISS001");
        assertThat(card.expiryDate()).isEqualTo(YearMonth.now().plusYears(3));
        assertThat(card.availableBalance()).isEqualByComparingTo("500");
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void shouldCopyCardWithNewIdAndPanWhenResolvingCollision() {
        // Arrange
        Card card = activeCard();

        // Act
        Card copy = card.copyWithPan(OTHER_PAN);

        // Assert
        assertThat(copy.pan()).isEqualTo(OTHER_PAN);
        assertThat(copy.id()).isNotEqualTo(card.id());
        assertThat(copy.bin()).isEqualTo(card.bin());
        assertThat(copy.availableBalance()).isEqualByComparingTo(card.availableBalance());
    }

    private static Card activeCard() {
        return cardWithStatus(CardStatus.ACTIVE);
    }

    private static Card cardWithStatus(CardStatus status) {
        return new Card(
                UUID.randomUUID(), PAN, "400000", "IVAN IVANOV", YearMonth.of(2028, 10), status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), BALANCE, "ISS001", CREATED_AT);
    }

    private static ReservationRollback rollbackOf(String pan, BigDecimal amount) {
        return new ReservationRollback(UUID.randomUUID(), UUID.randomUUID(), pan, amount, RRN, Instant.now());
    }
}
