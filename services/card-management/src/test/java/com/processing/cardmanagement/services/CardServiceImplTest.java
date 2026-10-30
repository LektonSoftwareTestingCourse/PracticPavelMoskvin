package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.events.CardsAmountUpdatedEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.MassiveCardCreationCollisionException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationRollback;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Бизнес-логика CMS {@link CardServiceImpl}: резервирование, откат, CRUD, пагинация, массовые операции.
 * Репозитории - моки; {@link TransactionRunner} заменён fake-реализацией, выполняющей операцию сразу.
 */
@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000001234567899";
    private static final String NEW_PAN = "4000000000000002";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612345678901";
    private static final BigDecimal BALANCE = new BigDecimal("1000");
    private static final int MAX_PAGE_LIMIT = 10_000;
    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final long DEFAULT_PAGE_OFFSET = 0L;
    private static final int VALIDITY_YEARS = 3;
    private static final int MAX_CREATION_RETRIES = 2;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                new TestSettings(),
                new TestDefaults(),
                panGenerator,
                eventNotifier,
                binIssuerService,
                new RetryServiceImpl(),
                new ImmediateTransactionRunner());
    }

    @Nested
    class Reserve {

        @Test
        void shouldSaveReservationAndDecreaseBalanceWhenRrnIsNew() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
            when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
            when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Card card = cardService.reserve(PAN, new BigDecimal("100"), RRN);

            // Assert
            assertThat(card.availableBalance()).isEqualByComparingTo("900");
            ArgumentCaptor<Reservation> reservation = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(reservation.capture());
            assertThat(reservation.getValue().rrn()).isEqualTo(RRN);
            assertThat(reservation.getValue().status()).isEqualTo(ReservationStatus.RESERVED);
            verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, new BigDecimal("100")));
        }

        /** TC-P3-21: повторный rrn - 409 без побочных эффектов. */
        @Test
        void shouldRejectDuplicateRrnWithoutSavingOrNotifying() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
            when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

            // Act + Assert
            assertThatThrownBy(() -> cardService.reserve(PAN, new BigDecimal("100"), RRN))
                    .isInstanceOf(ReservationAlreadyExistsException.class);
            verify(reservationRepository, never()).save(any());
            verify(cardRepository, never()).update(any());
            verifyNoInteractions(eventNotifier);
        }

        @Test
        void shouldRejectReservationAboveBalanceWithoutCheckingRrn() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

            // Act + Assert
            assertThatThrownBy(() -> cardService.reserve(PAN, new BigDecimal("1001"), RRN))
                    .isInstanceOf(InsufficientFundsException.class);
            verifyNoInteractions(reservationRepository);
            verify(cardRepository, never()).update(any());
        }

        @Test
        void shouldRejectReservationOnBlockedCard() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithStatus(CardStatus.BLOCKED)));

            // Act + Assert
            assertThatThrownBy(() -> cardService.reserve(PAN, BigDecimal.ONE, RRN))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(reservationRepository);
        }

        @Test
        void shouldThrowCardNotFoundWhenReservingOnUnknownPan() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> cardService.reserve(PAN, BigDecimal.ONE, RRN))
                    .isInstanceOf(CardNotFoundException.class)
                    .hasMessageNotContaining(PAN);
        }
    }

    @Nested
    class Rollback {

        @Test
        void shouldReturnFundsAndMarkReservationRolledBackWhenReservationExists() {
            // Arrange
            Reservation reservation = new Reservation(PAN, new BigDecimal("100"), RRN);
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
            when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
            when(reservationRollbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Card card = cardService.rollback(PAN, new BigDecimal("100"), RRN);

            // Assert
            assertThat(card.availableBalance()).isEqualByComparingTo("1100");
            ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(saved.capture());
            assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
            ArgumentCaptor<ReservationRollback> rollback = ArgumentCaptor.forClass(ReservationRollback.class);
            verify(reservationRollbackRepository).save(rollback.capture());
            assertThat(rollback.getValue().reservationId()).isEqualTo(reservation.id());
            verify(eventNotifier).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, new BigDecimal("100")));
        }

        @Test
        void shouldThrowReservationNotFoundWhenRrnIsUnknown() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
            when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> cardService.rollback(PAN, BigDecimal.ONE, RRN))
                    .isInstanceOf(ReservationNotFoundException.class);
            verifyNoInteractions(reservationRollbackRepository);
            verify(cardRepository, never()).update(any());
        }

        @Test
        void shouldRejectSecondRollbackWithoutChangingBalance() {
            // Arrange
            Reservation reservation = new Reservation(PAN, new BigDecimal("100"), RRN);
            Reservation rolledBack = reservation.rolledBack(reservation.startRollback(new BigDecimal("100")));
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
            when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(rolledBack));

            // Act + Assert
            assertThatThrownBy(() -> cardService.rollback(PAN, new BigDecimal("100"), RRN))
                    .isInstanceOf(RollbackAlreadySatisfiedException.class);
            verify(cardRepository, never()).update(any());
            verifyNoInteractions(eventNotifier);
        }
    }

    @Nested
    class Queries {

        @Test
        void shouldReturnCardWhenPanExists() {
            // Arrange
            Card card = activeCard();
            when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

            // Act
            Card found = cardService.getCard(PAN);

            // Assert
            assertThat(found).isSameAs(card);
        }

        @Test
        void shouldThrowCardNotFoundWhenPanIsUnknown() {
            // Arrange
            when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> cardService.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
        }

        @Test
        void shouldUseDefaultPaginationWhenLimitAndOffsetAreMissing() {
            // Act
            cardService.getCards(null, null, CardStatus.BLOCKED, BIN, null, null, null);

            // Assert
            verify(cardRepository).findCards(
                    DEFAULT_PAGE_LIMIT, DEFAULT_PAGE_OFFSET, CardStatus.BLOCKED, BIN, null, null, null);
        }

        /** TC-CMS-16 / TC-CMS-18: ON-точки верхней границы limit. */
        @ParameterizedTest(name = "limit={0}")
        @ValueSource(ints = {1, MAX_PAGE_LIMIT})
        void shouldPassExplicitPaginationWhenLimitIsWithinMaximum(int limit) {
            // Act
            cardService.getCards(limit, 5L, null, null, null, null, null);

            // Assert
            verify(cardRepository).findCards(limit, 5L, null, null, null, null, null);
        }

        /** TC-CMS-18: OFF-точка - limit = max-page-limit + 1. */
        @Test
        void shouldRejectLimitAboveMaximumWithoutQueryingRepository() {
            // Act + Assert
            assertThatThrownBy(() -> cardService.getCards(MAX_PAGE_LIMIT + 1, null, null, null, null, null, null))
                    .isInstanceOf(TooLargeLimitException.class)
                    .hasMessageContaining(String.valueOf(MAX_PAGE_LIMIT));
            verifyNoInteractions(cardRepository);
        }

        @Test
        void shouldPublishCardsAmountWhenCountingAllCards() {
            // Arrange
            when(cardRepository.countAllCards()).thenReturn(42L);

            // Act
            long count = cardService.countAllCardsAndUpdate();

            // Assert
            assertThat(count).isEqualTo(42L);
            verify(eventNotifier).notifyListeners(new CardsAmountUpdatedEvent(42L));
        }

        @Test
        void shouldDelegateFilteredCountToRepository() {
            // Arrange
            when(cardRepository.countCardsFiltered(CardStatus.ACTIVE, BIN, ISSUER_ID, null, null)).thenReturn(7L);

            // Act
            long count = cardService.countCardsFiltered(CardStatus.ACTIVE, BIN, ISSUER_ID, null, null);

            // Assert
            assertThat(count).isEqualTo(7L);
        }
    }

    @Nested
    class Modification {

        @Test
        void shouldKeepCurrentValuesWhenPatchFieldsAreMissing() {
            // Arrange
            Card card = activeCard();
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
            when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Card patched = cardService.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

            // Assert
            assertThat(patched.status()).isEqualTo(CardStatus.BLOCKED);
            assertThat(patched)
                    .usingRecursiveComparison()
                    .ignoringFields("status")
                    .isEqualTo(card);
            verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
        }

        @Test
        void shouldApplyAllPatchFieldsWhenTheyArePresent() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
            when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Card patched = cardService.patchCard(
                    PAN, CardStatus.ACTIVE, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30"));

            // Assert
            assertThat(patched.dailyLimit()).isEqualByComparingTo("10");
            assertThat(patched.monthlyLimit()).isEqualByComparingTo("20");
            assertThat(patched.availableBalance()).isEqualByComparingTo("30");
        }

        @Test
        void shouldRejectPatchWithNegativeDailyLimitWithoutSaving() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

            // Act + Assert
            assertThatThrownBy(() -> cardService.patchCard(PAN, null, new BigDecimal("-1"), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(cardRepository, never()).update(any());
        }

        @Test
        void shouldSoftDeleteCardAndNotifyWhenDeleting() {
            // Arrange
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

            // Act
            cardService.deleteCard(PAN);

            // Assert
            ArgumentCaptor<Card> updated = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).update(updated.capture());
            assertThat(updated.getValue().status()).isEqualTo(CardStatus.DELETED);
            verify(eventNotifier).notifyListeners(new CardServiceDeletionEvent(PAN));
        }

        @Test
        void shouldThrowCardNotFoundWhenDeletingUnknownCard() {
            // Arrange: удалённая карта скрыта @SQLRestriction и для репозитория не существует
            when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> cardService.deleteCard(PAN)).isInstanceOf(CardNotFoundException.class);
            verifyNoInteractions(eventNotifier);
        }
    }

    @Nested
    class Creation {

        @Test
        void shouldCreateActiveCardWithGeneratedPanAndIssuerOfBin() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(panGenerator.generatePan(BIN)).thenReturn(PAN);
            when(cardRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Card card = cardService.createCard(
                    BIN, "IVAN IVANOV", "643", new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("500"));

            // Assert
            assertThat(card.pan()).isEqualTo(PAN);
            assertThat(card.issuerId()).isEqualTo(ISSUER_ID);
            assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
            assertThat(card.expiryDate()).isEqualTo(YearMonth.now().plusYears(VALIDITY_YEARS));
            assertThat(card.availableBalance()).isEqualByComparingTo("500");
            verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
        }

        @Test
        void shouldRegeneratePanAndRetryWhenPanCollides() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(panGenerator.generatePan(BIN)).thenReturn(PAN, NEW_PAN);
            when(cardRepository.create(any()))
                    .thenThrow(new IllegalStateException("uk_cards_pan"))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            Card card = cardService.createCard(
                    BIN, "IVAN IVANOV", "643", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

            // Assert
            assertThat(card.pan()).isEqualTo(NEW_PAN);
            verify(cardRepository, times(2)).create(any());
        }

        @Test
        void shouldSaveBatchAtOnceWhenThereIsNoCollision() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(panGenerator.generatePan(BIN)).thenReturn(PAN, NEW_PAN);
            when(cardRepository.createAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            List<Card> cards = cardService.createCards(List.of(draft(), draft()));

            // Assert
            assertThat(cards).extracting(Card::pan).containsExactly(PAN, NEW_PAN);
            verify(cardRepository, never()).create(any());
            verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
        }

        @Test
        void shouldFallBackToOneByOneCreationWithNewPansWhenBatchCollides() {
            // Arrange
            when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
            when(panGenerator.generatePan(BIN)).thenReturn(PAN, NEW_PAN, "4111111111111111", "5500005555555559");
            when(cardRepository.createAll(anyList())).thenThrow(new MassiveCardCreationCollisionException());
            when(cardRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            List<Card> cards = cardService.createCards(List.of(draft(), draft()));

            // Assert
            assertThat(cards).extracting(Card::pan).containsExactly("4111111111111111", "5500005555555559");
            verify(cardRepository, times(2)).create(any());
            verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
        }
    }

    @Nested
    class BulkUpdate {

        @Test
        void shouldRejectBulkUpdateWhenNeitherBinsNorPansAreGiven() {
            // Act + Assert
            assertThatThrownBy(() -> cardService.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Either bin or pans must be provided");
            verifyNoInteractions(cardRepository);
        }

        @Test
        void shouldRejectBulkUpdateWhenBothBinsAndPansAreGiven() {
            // Act + Assert
            assertThatThrownBy(() -> cardService.bulkUpdateStatus(List.of(BIN), List.of(PAN), CardStatus.BLOCKED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Only one of bin or pans must be provided");
            verifyNoInteractions(cardRepository);
        }

        @Test
        void shouldUpdateStatusOfEveryCardOfGivenBins() {
            // Arrange
            when(cardRepository.findCardsByBinsForUpdate(List.of(BIN)))
                    .thenReturn(List.of(activeCard(), cardWithStatus(CardStatus.INACTIVE)));

            // Act
            int updated = cardService.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

            // Assert
            assertThat(updated).isEqualTo(2);
            ArgumentCaptor<Card> cards = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository, times(2)).update(cards.capture());
            assertThat(cards.getAllValues()).extracting(Card::status).containsOnly(CardStatus.BLOCKED);
        }

        @Test
        void shouldUpdateStatusOfGivenPans() {
            // Arrange
            when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(activeCard()));

            // Act
            int updated = cardService.bulkUpdateStatus(null, List.of(PAN), CardStatus.EXPIRED);

            // Assert
            assertThat(updated).isEqualTo(1);
            verify(cardRepository, never()).findCardsByBinsForUpdate(any());
        }
    }

    private static Card activeCard() {
        return cardWithStatus(CardStatus.ACTIVE);
    }

    private static Card cardWithStatus(CardStatus status) {
        return new Card(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV", YearMonth.of(2028, 10), status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), BALANCE, ISSUER_ID,
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    private static CardDraft draft() {
        return new CardDraft(
                BIN, "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("500"));
    }

    /** Fake: выполняет операцию сразу, без транзакции. */
    private static final class ImmediateTransactionRunner implements TransactionRunner {

        @Override
        public void run(Runnable operation) {
            operation.run();
        }

        @Override
        public <T> T runSupplier(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> supplier) {
            return supplier.apply(this);
        }
    }

    private static final class TestSettings implements CardServiceSettings {

        @Override
        public int cardValidityPeriod() {
            return VALIDITY_YEARS;
        }

        @Override
        public int maxPageLimit() {
            return MAX_PAGE_LIMIT;
        }

        @Override
        public int maxCardCreationRetries() {
            return MAX_CREATION_RETRIES;
        }
    }

    private static final class TestDefaults implements CardServiceDefaults {

        @Override
        public int pageLimit() {
            return DEFAULT_PAGE_LIMIT;
        }

        @Override
        public long pageOffset() {
            return DEFAULT_PAGE_OFFSET;
        }

        @Override
        public String currencyCode() {
            return "643";
        }

        @Override
        public BigDecimal dailyLimit() {
            return new BigDecimal("15000000");
        }

        @Override
        public BigDecimal monthlyLimit() {
            return new BigDecimal("300000000");
        }

        @Override
        public BigDecimal balance() {
            return BigDecimal.ZERO;
        }
    }
}
