package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Оркестрация Switch: BIN -> Authorization -> комиссия -> публикация в Logger, rollback при сбое публикации.
 * TC-P3-04 (REQ-SW-03), REQ-SW-01, REQ-SW-02.
 */
@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612345678901";
    private static final String AUTH_CODE = "A1B2C3";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal ACQUIRING_FEE = new BigDecimal("15");
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-30T10:00:00Z");

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldReturnApprovedResponseWhenAuthorizedAndPublished() {
        // Arrange
        AuthorizationResponse approved = approvedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(ACQUIRING_FEE);
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request());

        // Assert
        assertThat(response).isSameAs(approved);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldPublishTransactionEnrichedWithIssuerFeeAndAuthorizationResultWhenApproved() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(ACQUIRING_FEE);
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        routeService.route(request());

        // Assert
        TransactionRequest published = capturePublishedTransaction();
        assertThat(published.id()).isNotNull();
        assertThat(published.pan()).isEqualTo(PAN);
        assertThat(published.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(published.acquiringFee()).isEqualByComparingTo(ACQUIRING_FEE);
        assertThat(published.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(published.rrn()).isEqualTo(RRN);
        assertThat(published.authCode()).isEqualTo(AUTH_CODE);
        assertThat(published.processingTimeMs()).isEqualTo(12);
        assertThat(published.transmissionDateTime()).isEqualTo(TRANSMISSION_TIME);
    }

    @Test
    void shouldSendNormalizedRequestWithIssuerIdToAuthorization() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declinedResponse("51", "INSUFFICIENT_FUNDS"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        routeService.route(requestWithIds("T1", "MERCH2"));

        // Assert
        ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationClient).authorize(captor.capture());
        assertThat(captor.getValue().issuerId()).isEqualTo(ISSUER_ID);
        assertThat(captor.getValue().terminalId()).isEqualTo("T1000000");
        assertThat(captor.getValue().merchantId()).isEqualTo("MERCH2000000000");
    }

    @ParameterizedTest(name = "rollback response: {0}")
    @MethodSource("rollbackOutcomes")
    void shouldRollbackAndReturnSystemErrorWhenApprovedTransactionIsNotPublished(
            String description, RollbackResponse rollbackResponse) {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), any())).thenReturn(rollbackResponse);

        // Act
        AuthorizationResponse response = routeService.route(request());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("System error");
        assertThat(response.rrn()).isNull();
        ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationClient).rollback(captor.capture(), eq(RRN));
        assertThat(captor.getValue().issuerId()).isEqualTo(ISSUER_ID);
    }

    static Stream<Arguments> rollbackOutcomes() {
        return Stream.of(
                Arguments.of("approved", new RollbackResponse(RRN, "00", "APPROVED", null, 5)),
                Arguments.of("declined", new RollbackResponse(RRN, "14", "DECLINED", "TRANSACTION_NOT_FOUND", 5)),
                Arguments.of("no response", null));
    }

    @Test
    void shouldReturnOriginalDeclineWithoutRollbackWhenDeclinedTransactionIsNotPublished() {
        // Arrange
        AuthorizationResponse declined = declinedResponse("51", "INSUFFICIENT_FUNDS");
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declined);
        when(loggerClient.log(any())).thenReturn(false);

        // Act
        AuthorizationResponse response = routeService.route(request());

        // Assert
        assertThat(response).isSameAs(declined);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldDeclineWithCode14WithoutCallingAuthorizationWhenBinIsUnknown() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(response.stan()).isEqualTo(STAN);
        verify(authorizationClient, never()).authorize(any());
    }

    @Test
    void shouldPublishDeclinedTransactionWithoutIssuerWhenBinIsUnknown() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        routeService.route(request());

        // Assert
        TransactionRequest published = capturePublishedTransaction();
        assertThat(published.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(published.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(published.issuerId()).isNull();
        assertThat(published.acquiringFee()).isNull();
    }

    @ParameterizedTest(name = "pan=''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = "40000")
    void shouldDeclineAsUnknownBinWhenPanIsNullOrTooShort(String pan) {
        // Arrange
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(requestWithPan(pan));

        // Assert
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verify(authorizationClient, never()).authorize(any());
    }

    @Test
    void shouldDeclineWithCode05WhenAuthorizationIsUnavailable() {
        // Arrange
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenThrow(new AuthorizationException(STAN, 3, "timeout"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = routeService.route(request());

        // Assert
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
        verify(acquiringFeeClient, never()).fetchAcquiringFee(any(), any(), any(), any(), any());
    }

    @Test
    void shouldPublishZeroProcessingTimeWhenAuthorizationResponseHasNoProcessingTime() {
        // Arrange
        AuthorizationResponse withoutTime = new AuthorizationResponse(
                "0110", STAN, null, null, "05", "DECLINED", "CARD_BLOCKED", null);
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(withoutTime);
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        routeService.route(request());

        // Assert
        assertThat(capturePublishedTransaction().processingTimeMs()).isZero();
    }

    private TransactionRequest capturePublishedTransaction() {
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(captor.capture());
        return captor.getValue();
    }

    private static AuthorizationRequest request() {
        return requestWithIds("TERM0001", "MERCH0000000002");
    }

    private static AuthorizationRequest requestWithPan(String pan) {
        return new AuthorizationRequest(
                "0100", STAN, pan, "000000", AMOUNT, "643", TRANSMISSION_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", null);
    }

    private static AuthorizationRequest requestWithIds(String terminalId, String merchantId) {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000", AMOUNT, "643", TRANSMISSION_TIME,
                terminalId, "POS", merchantId, "5045", "ACQ002", null);
    }

    private static AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse("0100", STAN, RRN, AUTH_CODE, "00", "APPROVED", null, 12);
    }

    private static AuthorizationResponse declinedResponse(String code, String reason) {
        return new AuthorizationResponse("0100", STAN, null, null, code, "DECLINED", reason, 7);
    }
}
