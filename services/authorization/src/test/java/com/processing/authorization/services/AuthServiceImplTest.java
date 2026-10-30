package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthDeclineNoCardEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardStatusEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedFundsEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedLimitsEvent;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceDuplicateKeyEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты алгоритма авторизации {@link AuthServiceImpl} (ТЗ Authorization, п. 2).
 * Все внешние зависимости - CMS, BIN Lookup, таблица limit_usage - заменены моками.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("100000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("500000");
    private static final YearMonth EXPIRY = YearMonth.of(2027, 12);
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant REQUEST_TIME = Instant.now();
    private static final String RRN = "612345678901";

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }

    @Nested
    class HappyPath {

        @Test
        void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
            limitsAreNotExceeded();

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
            assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
            assertThat(response.declineReason()).isNull();
            assertThat(response.rrn()).matches("\\d{12}");
            assertThat(response.authCode()).matches("[A-Z0-9]{6}");
            assertThat(response.stan()).isEqualTo("000001");
            verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
        }

        @Test
        void shouldReserveAmountExactlyOnceWithSameRrnAsInResponseWhenApproved() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            limitsAreNotExceeded();

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            verify(cardManagementClient, times(1)).reserve(AMOUNT, response.rrn(), PAN);
        }

        @Test
        void shouldApproveWhenBinLookupIsUnavailable() {
            // Arrange: BinLookupClient сам гасит ошибки и отдаёт Optional.empty()
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
            limitsAreNotExceeded();

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        }

        @Test
        void shouldUpdateLimitsWithCardLimitsAndUtcTransmissionDate() {
            // Arrange: 23:30 по UTC - уже следующий день по Москве, но в limit_usage пишется дата UTC
            AuthorizationRequest lateEvening = requestAt(Instant.parse("2026-10-30T23:30:00Z"));
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            limitsAreNotExceeded();

            // Act
            authService.authorize(lateEvening, REQUEST_TIME);

            // Assert
            verify(limitUsageRepository).upsertLimitUsage(
                    PAN, LocalDate.of(2026, 10, 30), AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT);
        }
    }

    @Nested
    class CardLookup {

        @Test
        void shouldDeclineWhenCardNotFound() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.responseCode()).isEqualTo("14");
            assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
            assertThat(response.rrn()).isNull();
            assertThat(response.authCode()).isNull();
            verify(eventNotifier).notify(new AuthServiceAuthDeclineNoCardEvent(PAN));
        }

        @ParameterizedTest(name = "{0} -> {1} {2}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#getCardFailures")
        void shouldMapCardManagementFailureToDeclineWithoutReservation(
                RuntimeException failure, String expectedReason, String expectedCode) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenThrow(failure);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
            verify(cardManagementClient, never()).reserve(any(), any(), any());
            verifyNoInteractions(limitUsageRepository);
        }

    }

    static Stream<Arguments> getCardFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("404"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InvalidGetCardRequestException("400"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ResourceAccessException("timeout"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new PaymentRequiredException("402"), "CARD_BLOCKED", "05"),
                Arguments.of(new GetCardException("418"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05"));
    }

    @Nested
    class CardStatus {

        @ParameterizedTest(name = "status={0} -> {1} {2}")
        @CsvSource({
            "INACTIVE, CARD_INACTIVE, 05",
            "BLOCKED,  CARD_BLOCKED,  05",
            "EXPIRED,  CARD_EXPIRED,  54"
        })
        void shouldDeclineWithStatusSpecificReasonWhenCardIsNotActive(
                CardModelStatus status, String expectedReason, String expectedCode) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
            verify(eventNotifier).notify(new AuthServiceAuthDeclinedCardStatusEvent(PAN, status.name()));
        }

        @ParameterizedTest(name = "status={0}")
        @EnumSource(
                value = CardModelStatus.class,
                mode = EnumSource.Mode.EXCLUDE,
                names = {"ACTIVE"})
        void shouldNotTouchLimitsOrReserveWhenCardIsNotActive(CardModelStatus status) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));

            // Act
            authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            verifyNoInteractions(limitUsageRepository);
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }

        @Test
        void shouldDeclineWithUnknownReasonWhenCardStatusIsMissing() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(null));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
            assertThat(response.responseCode()).isEqualTo("05");
        }
    }

    @Nested
    class ExpiryDate {

        /** TC-P3-02 (а), TC-AUTH-08: ON-точки - карта действует до последней секунды месяца expiryDate по UTC. */
        @ParameterizedTest(name = "expiry=2026-09, transmission={0}")
        @ValueSource(strings = {"2026-09-01T00:00:00Z", "2026-09-30T23:59:59Z"})
        void shouldApproveWhenTransmissionIsWithinExpiryMonthInUtc(Instant transmissionTime) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardExpiringAt(YearMonth.of(2026, 9)));
            limitsAreNotExceeded();

            // Act
            AuthorizationResponse response = authService.authorize(requestAt(transmissionTime), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        }

        /** TC-P3-02 (б), TC-AUTH-07: OFF-точки - первая секунда следующего месяца и позже. */
        @ParameterizedTest(name = "expiry=2026-09, transmission={0}")
        @ValueSource(strings = {"2026-10-01T00:00:00Z", "2027-01-15T12:00:00Z"})
        void shouldDeclineWithCode54WithoutReservationWhenCardIsExpired(Instant transmissionTime) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardExpiringAt(YearMonth.of(2026, 9)));

            // Act
            AuthorizationResponse response = authService.authorize(requestAt(transmissionTime), REQUEST_TIME);

            // Assert
            assertThat(response.responseCode()).isEqualTo("54");
            assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
            verifyNoInteractions(limitUsageRepository);
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }
    }

    @Nested
    class FundsAndLimits {

        /** TC-AUTH-15: ON-точки границы amount <= availableBalance. */
        @ParameterizedTest(name = "balance=1000, amount={0}")
        @ValueSource(strings = {"0.01", "999.99", "1000"})
        void shouldApproveWhenAmountDoesNotExceedBalance(BigDecimal amount) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithBalance(new BigDecimal("1000")));
            limitsAreNotExceeded();

            // Act
            AuthorizationResponse response = authService.authorize(requestWithAmount(amount), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        }

        /** TC-AUTH-16: OFF-точки той же границы - отказ 51 без резервирования. */
        @ParameterizedTest(name = "balance=1000, amount={0}")
        @ValueSource(strings = {"1000.01", "1001"})
        void shouldDeclineWithCode51WithoutReservationWhenFundsAreInsufficient(BigDecimal amount) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(cardWithBalance(new BigDecimal("1000")));

            // Act
            AuthorizationResponse response = authService.authorize(requestWithAmount(amount), REQUEST_TIME);

            // Assert
            assertThat(response.responseCode()).isEqualTo("51");
            assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
            verify(cardManagementClient, never()).reserve(any(), any(), any());
            verify(eventNotifier).notify(new AuthServiceAuthDeclinedFundsEvent(PAN));
        }

        /** TC-AUTH-18: превышение лимита (upsert вернул 0) - отказ без побочных эффектов в CMS. */
        @Test
        void shouldDeclineWithCode61WithoutReservationWhenLimitIsExceeded() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.responseCode()).isEqualTo("61");
            assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
            verify(cardManagementClient, never()).reserve(any(), any(), any());
            verify(eventNotifier).notify(new AuthServiceAuthDeclinedLimitsEvent(PAN));
        }

        @Test
        void shouldRetryLimitUpdateOnceAndApproveWhenFirstAttemptHitsDuplicateKey() {
            // Arrange: гонка двух первых транзакций дня за вставку строки limit_usage
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("uk_limit_usage"))
                    .thenReturn(1);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
            verify(limitUsageRepository, times(2)).upsertLimitUsage(any(), any(), any(), any(), any());
            verify(eventNotifier).notify(new AuthServiceDuplicateKeyEvent(PAN, "uk_limit_usage"));
        }

        @Test
        void shouldDeclineByLimitWhenRetryAfterDuplicateKeyFindsLimitExceeded() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("uk_limit_usage"))
                    .thenReturn(0);

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.responseCode()).isEqualTo("61");
        }

        @Test
        void shouldDeclineWithDbUnavailableWhenRetryAfterDuplicateKeyFails() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("uk_limit_usage"))
                    .thenThrow(new DataAccessResourceFailureException("connection lost"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.responseCode()).isEqualTo("96");
            assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
            verify(cardManagementClient, never()).reserve(any(), any(), any());
        }

        @Test
        void shouldDeclineWithDbUnavailableWithoutRetryWhenLimitStorageFails() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                    .thenThrow(new DataAccessResourceFailureException("connection lost"));

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.responseCode()).isEqualTo("96");
            assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
            verify(limitUsageRepository, times(1)).upsertLimitUsage(any(), any(), any(), any(), any());
        }
    }

    @Nested
    class Reservation {

        @ParameterizedTest(name = "{0} -> {1} {2}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#reserveFailures")
        void shouldDeclineWithoutRrnWhenReservationFails(
                RuntimeException failure, String expectedReason, String expectedCode) {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            limitsAreNotExceeded();
            doThrow(failure).when(cardManagementClient).reserve(any(), anyString(), anyString());

            // Act
            AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
            assertThat(response.rrn()).isNull();
            assertThat(response.authCode()).isNull();
        }

        @Test
        void shouldNotPublishApprovedEventWhenReservationFails() {
            // Arrange
            when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
            limitsAreNotExceeded();
            doThrow(new InsufficientFundsException("402")).when(cardManagementClient)
                    .reserve(any(), anyString(), anyString());

            // Act
            authService.authorize(validRequest(), REQUEST_TIME);

            // Assert
            ArgumentCaptor<AuthorizationEvent> events = ArgumentCaptor.forClass(AuthorizationEvent.class);
            verify(eventNotifier).notify(events.capture());
            assertThat(events.getAllValues()).containsExactly(new AuthServiceAuthDeclinedFundsEvent(PAN));
        }
    }

    static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("404"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ResourceAccessException("timeout"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InvalidReserveRequestException("400"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InsufficientFundsException("402"), "INSUFFICIENT_FUNDS", "51"),
                Arguments.of(new ReserveException("409"), "RESERVATION_FAILED", "96"),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05"));
    }

    @Nested
    class Rollback {

        @Test
        void shouldApproveRollbackWhenCardManagementRollsBackReservation() {
            // Arrange
            RollbackRequest request = rollbackRequest();

            // Act
            RollbackResponse response = authService.rollback(request, REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
            assertThat(response.responseCode()).isEqualTo(RollbackResponse.CODE_SUCCESS);
            assertThat(response.rrn()).isEqualTo(RRN);
            verify(cardManagementClient).rollback(request);
            verify(eventNotifier).notify(new AuthServiceRollbackEvent(PAN));
        }

        @ParameterizedTest(name = "{0} -> {1} {2}")
        @MethodSource("com.processing.authorization.services.AuthServiceImplTest#rollbackFailures")
        void shouldDeclineRollbackWhenCardManagementFails(
                RuntimeException failure, String expectedReason, String expectedCode) {
            // Arrange
            RollbackRequest request = rollbackRequest();
            doThrow(failure).when(cardManagementClient).rollback(request);

            // Act
            RollbackResponse response = authService.rollback(request, REQUEST_TIME);

            // Assert
            assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
            assertThat(response.declineReason()).isEqualTo(expectedReason);
            assertThat(response.responseCode()).isEqualTo(expectedCode);
            assertThat(response.rrn()).isEqualTo(RRN);
        }

    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("404"), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new InvalidRollbackRequestException("400"), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ResourceAccessException("timeout"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new RollbackConflictException("409"), "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(new RollbackFailureException("418"), "ROLLBACK_FAILED", "96"),
                Arguments.of(new IllegalStateException("unexpected"), "UNKNOWN_REASON", "05"));
    }

    private void limitsAreNotExceeded() {
        when(limitUsageRepository.upsertLimitUsage(eq(PAN), any(), any(), any(), any())).thenReturn(1);
    }

    private static AuthorizationRequest validRequest() {
        return request(AMOUNT, TRANSMISSION_TIME);
    }

    private static AuthorizationRequest requestWithAmount(BigDecimal amount) {
        return request(amount, TRANSMISSION_TIME);
    }

    private static AuthorizationRequest requestAt(Instant transmissionTime) {
        return request(AMOUNT, transmissionTime);
    }

    private static AuthorizationRequest request(BigDecimal amount, Instant transmissionTime) {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                amount, "643", transmissionTime,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID);
    }

    private static RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }

    private static CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private static CardModel cardWithStatus(CardModelStatus status) {
        return card(status, EXPIRY, BALANCE, DAILY_LIMIT);
    }

    private static CardModel cardExpiringAt(YearMonth expiry) {
        return card(CardModelStatus.ACTIVE, expiry, BALANCE, DAILY_LIMIT);
    }

    private static CardModel cardWithBalance(BigDecimal balance) {
        return card(CardModelStatus.ACTIVE, EXPIRY, balance, DAILY_LIMIT);
    }

    private static CardModel card(CardModelStatus status, YearMonth expiry, BigDecimal balance, BigDecimal dailyLimit) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiry, status, "643",
                dailyLimit, MONTHLY_LIMIT, balance,
                ISSUER_ID, Instant.parse("2025-01-01T00:00:00Z"));
    }
}
