package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardsBatchGeneratedEvent;
import com.processing.cardmanagement.exceptions.CardGenerationLimitException;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardGeneratorOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * TC-CMS-24, TC-CMS-27: генерация тестовых карт - равномерно по BIN, статусы 95 / 3 / 2 %, ограничение max-count.
 */
@ExtendWith(MockitoExtension.class)
class CardGeneratorServiceTest {

    private static final int MAX_COUNT = 200;
    private static final List<String> BINS = List.of("400000", "400001", "400002", "400003", "400004");

    @Mock
    private CardService cardService;

    @Mock
    private CardEventNotifier eventNotifier;

    private CardGeneratorService generatorService;

    @BeforeEach
    void setUp() {
        CardGeneratorOptions options = new CardGeneratorOptions(
                new BigDecimal("1000"), new BigDecimal("5000"),
                new BigDecimal("100"), new BigDecimal("200"),
                "643", MAX_COUNT);
        generatorService = new CardGeneratorService(cardService, options, eventNotifier);
    }

    @Test
    void shouldDistributeHundredCardsEvenlyAcrossFiveBins() {
        // Act
        generatorService.generate(100, BINS);

        // Assert
        Map<String, Long> perBin = generatedDrafts().stream()
                .collect(Collectors.groupingBy(CardDraft::bin, Collectors.counting()));
        assertThat(perBin).isEqualTo(BINS.stream().collect(Collectors.toMap(Function.identity(), bin -> 20L)));
    }

    @ParameterizedTest(name = "count={0} -> ACTIVE={1}, INACTIVE={2}, BLOCKED={3}")
    @CsvSource({
        "100, 95, 3, 2",
        "1,   0,  0, 1",
        "34,  32, 1, 1"
    })
    void shouldAssignStatusesInProportion95To3To2(int count, long active, long inactive, long blocked) {
        // Act
        generatorService.generate(count, BINS);

        // Assert
        Map<CardStatus, Long> statuses = generatedDrafts().stream()
                .collect(Collectors.groupingBy(CardDraft::status, Collectors.counting()));
        assertThat(statuses.getOrDefault(CardStatus.ACTIVE, 0L)).isEqualTo(active);
        assertThat(statuses.getOrDefault(CardStatus.INACTIVE, 0L)).isEqualTo(inactive);
        assertThat(statuses.getOrDefault(CardStatus.BLOCKED, 0L)).isEqualTo(blocked);
    }

    @Test
    void shouldGenerateDraftsWithinConfiguredRangesAndMonthlyLimitOfThirtyDays() {
        // Act
        generatorService.generate(50, BINS);

        // Assert
        assertThat(generatedDrafts()).allSatisfy(draft -> {
            assertThat(draft.initialBalance()).isBetween(new BigDecimal("1000"), new BigDecimal("5000"));
            assertThat(draft.dailyLimit()).isBetween(new BigDecimal("100"), new BigDecimal("200"));
            assertThat(draft.monthlyLimit()).isEqualByComparingTo(draft.dailyLimit().multiply(BigDecimal.valueOf(30)));
            assertThat(draft.currencyCode()).isEqualTo("643");
            assertThat(draft.cardholderName()).isUpperCase();
        });
    }

    @Test
    void shouldPublishBatchEventWithStatusCounts() {
        // Act
        generatorService.generate(100, BINS);

        // Assert
        verify(eventNotifier).notifyListeners(new CardsBatchGeneratedEvent(Map.of(
                CardStatus.ACTIVE, 95L, CardStatus.INACTIVE, 3L, CardStatus.BLOCKED, 2L)));
    }

    @Test
    void shouldAcceptCountEqualToMaximum() {
        // Act
        generatorService.generate(MAX_COUNT, BINS);

        // Assert
        assertThat(generatedDrafts()).hasSize(MAX_COUNT);
    }

    @Test
    void shouldRejectCountAboveMaximumWithoutCreatingCards() {
        // Act + Assert
        assertThatThrownBy(() -> generatorService.generate(MAX_COUNT + 1, BINS))
                .isInstanceOf(CardGenerationLimitException.class)
                .hasMessageContaining(String.valueOf(MAX_COUNT));
        verifyNoInteractions(cardService, eventNotifier);
    }

    @SuppressWarnings("unchecked")
    private List<CardDraft> generatedDrafts() {
        ArgumentCaptor<List<CardDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(cardService).createCards(captor.capture());
        return captor.getValue();
    }
}
