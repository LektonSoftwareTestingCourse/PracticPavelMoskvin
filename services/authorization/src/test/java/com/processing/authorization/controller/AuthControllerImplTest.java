package com.processing.authorization.controller;

import com.processing.authorization.services.AuthServiceImpl;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Маппинг решения Authorization в HTTP-статус (контракт TC-P3-08): тело всегда AuthorizationResponse,
 * статус зависит от declineReason.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerImplTest {

    private static final String PAN = "4000001234567890";
    private static final String RRN = "612345678901";

    @Mock
    private AuthServiceImpl authService;

    private AuthControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new AuthControllerImpl(authService);
    }

    @Test
    void shouldRespond200WithBodyWhenAuthorizationIsApproved() {
        // Arrange
        AuthorizationRequest request = request();
        AuthorizationResponse approved = new AuthorizationResponse(
                "0100", "000001", RRN, "A1B2C3", "00", "APPROVED", null, 5);
        when(authService.authorize(eq(request), any(Instant.class))).thenReturn(approved);

        // Act
        ResponseEntity<AuthorizationResponse> response = controller.authorize(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(approved);
    }

    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({
        "CARD_NOT_FOUND,       404",
        "SERVICE_UNAVAILABLE,  503",
        "RESERVATION_FAILED,   503",
        "INSUFFICIENT_FUNDS,   422",
        "CARD_EXPIRED,         403",
        "CARD_BLOCKED,         403",
        "CARD_INACTIVE,        403",
        "EXCEEDS_AMOUNT_LIMIT, 400",
        "DB_UNAVAILABLE,       400",
        "UNKNOWN_REASON,       400"
    })
    void shouldMapAuthorizationDeclineReasonToHttpStatus(String declineReason, int expectedHttpStatus) {
        // Arrange
        AuthorizationRequest request = request();
        AuthorizationResponse declined = new AuthorizationResponse(
                "0100", "000001", null, null, "05", "DECLINED", declineReason, 5);
        when(authService.authorize(eq(request), any(Instant.class))).thenReturn(declined);

        // Act
        ResponseEntity<AuthorizationResponse> response = controller.authorize(request);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(expectedHttpStatus);
        assertThat(response.getBody()).isSameAs(declined);
    }

    @Test
    void shouldRespond200WhenRollbackIsApproved() {
        // Arrange
        RollbackRequest request = new RollbackRequest(RRN, PAN, new BigDecimal("1000"));
        RollbackResponse approved = new RollbackResponse(RRN, "00", "APPROVED", null, 5);
        when(authService.rollback(eq(request), any(Instant.class))).thenReturn(approved);

        // Act
        ResponseEntity<RollbackResponse> response = controller.rollback(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(approved);
    }

    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({
        "TRANSACTION_NOT_FOUND, 404",
        "CARD_NOT_FOUND,        404",
        "ALREADY_ROLLED_BACK,   409",
        "SERVICE_UNAVAILABLE,   503",
        "ROLLBACK_FAILED,       503",
        "UNKNOWN_REASON,        400"
    })
    void shouldMapRollbackDeclineReasonToHttpStatus(String declineReason, int expectedHttpStatus) {
        // Arrange
        RollbackRequest request = new RollbackRequest(RRN, PAN, new BigDecimal("1000"));
        RollbackResponse declined = new RollbackResponse(RRN, "05", "DECLINED", declineReason, 5);
        when(authService.rollback(eq(request), any(Instant.class))).thenReturn(declined);

        // Act
        ResponseEntity<RollbackResponse> response = controller.rollback(request);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(expectedHttpStatus);
    }

    private static AuthorizationRequest request() {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000", new BigDecimal("1000"), "643",
                Instant.parse("2026-10-30T10:00:00Z"), "TERM0001", "POS", "MERCH0000000002",
                "5045", "ACQ002", "ISS001");
    }
}
