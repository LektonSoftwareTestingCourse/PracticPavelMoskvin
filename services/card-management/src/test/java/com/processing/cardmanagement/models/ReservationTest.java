package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Жизненный цикл резервирования: RESERVED -> ROLLED_BACK, повторный откат запрещён (REQ-AUTH-19, TC-P3-10).
 */
class ReservationTest {

    private static final String PAN = "4000001234567899";
    private static final String RRN = "612345678901";

    @Test
    void shouldCreateRollbackLinkedToReservationWhenReserved() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("100"), RRN);

        // Act
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("100"));

        // Assert
        assertThat(rollback.reservationId()).isEqualTo(reservation.id());
        assertThat(rollback.pan()).isEqualTo(PAN);
        assertThat(rollback.rrn()).isEqualTo(RRN);
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo("100");
    }

    @Test
    void shouldMarkReservationAsRolledBackWhenRollbackBelongsToIt() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("100"), RRN);
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("100"));

        // Act
        Reservation rolledBack = reservation.rolledBack(rollback);

        // Assert
        assertThat(rolledBack.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(rolledBack.id()).isEqualTo(reservation.id());
        assertThat(rolledBack.createdAt()).isEqualTo(reservation.createdAt());
    }

    @Test
    void shouldRejectSecondRollbackOfSameReservation() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("100"), RRN);
        Reservation rolledBack = reservation.rolledBack(reservation.startRollback(new BigDecimal("100")));

        // Act + Assert
        assertThatThrownBy(() -> rolledBack.startRollback(new BigDecimal("100")))
                .isInstanceOf(RollbackAlreadySatisfiedException.class)
                .hasMessageContaining(RRN);
    }

    @Test
    void shouldRejectRollbackCreatedForAnotherReservation() {
        // Arrange
        Reservation reservation = new Reservation(PAN, new BigDecimal("100"), RRN);
        ReservationRollback foreign = new ReservationRollback(
                UUID.randomUUID(), UUID.randomUUID(), PAN, new BigDecimal("100"), RRN, Instant.now());

        // Act + Assert
        assertThatThrownBy(() -> reservation.rolledBack(foreign)).isInstanceOf(IllegalArgumentException.class);
    }
}
