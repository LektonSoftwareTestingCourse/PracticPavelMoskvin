package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.models.CardOutboxEventData;
import com.processing.cardmanagement.models.OutboxEventDataStatus;
import com.processing.cardmanagement.options.OutboxOptions;
import com.processing.cardmanagement.repositories.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-P3-05 (REQ-CMS-11): outbox-событие публикуется не более maxRetryCount раз, затем - FAILED.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorImplTest {

    private static final int MAX_RETRY_COUNT = 3;
    private static final CardServiceReserveEvent EVENT =
            new CardServiceReserveEvent("4000001234567899", "612345678901", new BigDecimal("100"));

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OutboxEventProcessorImpl processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxEventProcessorImpl(outboxRepository, rabbitTemplate, new OutboxOptions(1000, MAX_RETRY_COUNT));
    }

    @Test
    void shouldPublishToCardEventsExchangeWithRoutingKeyByEventTypeAndMarkProcessed() {
        // Arrange
        CardOutboxEventData pending = new CardOutboxEventData(EVENT);

        // Act
        processor.processSingleEvent(pending);

        // Assert
        verify(rabbitTemplate).convertAndSend("smp.card-events", "card.CardServiceReserveEvent", EVENT);
        CardOutboxEventData saved = captureSaved();
        assertThat(saved.status()).isEqualTo(OutboxEventDataStatus.PROCESSED);
        assertThat(saved.processedAt()).isNotNull();
        assertThat(saved.id()).isEqualTo(pending.id());
    }

    @ParameterizedTest(name = "retryCount {0} -> {1}, {2}")
    @CsvSource({
        "0, 1, PENDING",
        "1, 2, PENDING",
        "2, 3, FAILED"
    })
    void shouldIncrementRetryAndFailOnlyWhenMaxRetryCountIsReached(
            int retryCountBefore, int expectedRetryCount, OutboxEventDataStatus expectedStatus) {
        // Arrange
        brokerIsDown();

        // Act
        processor.processSingleEvent(pendingWithRetries(retryCountBefore));

        // Assert
        CardOutboxEventData saved = captureSaved();
        assertThat(saved.retryCount()).isEqualTo(expectedRetryCount);
        assertThat(saved.status()).isEqualTo(expectedStatus);
        assertThat(saved.lastError()).contains("Connection refused");
        assertThat(saved.processedAt()).isNull();
    }

    @Test
    void shouldFailEventAfterThreeConsecutiveUnsuccessfulAttempts() {
        // Arrange
        brokerIsDown();
        List<CardOutboxEventData> states = new ArrayList<>();
        when(outboxRepository.save(any())).thenAnswer(invocation -> {
            states.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        // Act: планировщик каждый раз передаёт последнее сохранённое состояние
        processor.processSingleEvent(new CardOutboxEventData(EVENT));
        processor.processSingleEvent(states.get(0));
        processor.processSingleEvent(states.get(1));

        // Assert
        assertThat(states).extracting(CardOutboxEventData::status)
                .containsExactly(OutboxEventDataStatus.PENDING, OutboxEventDataStatus.PENDING, OutboxEventDataStatus.FAILED);
        assertThat(states).extracting(CardOutboxEventData::retryCount).containsExactly(1, 2, 3);
    }

    @Test
    void shouldDelegateSaveToRepository() {
        // Arrange
        CardOutboxEventData event = new CardOutboxEventData(EVENT);

        // Act
        processor.save(event);

        // Assert
        verify(outboxRepository).save(event);
    }

    private void brokerIsDown() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    private CardOutboxEventData captureSaved() {
        return captureAllSaved(1).get(0);
    }

    private List<CardOutboxEventData> captureAllSaved(int expectedSaves) {
        ArgumentCaptor<CardOutboxEventData> captor = ArgumentCaptor.forClass(CardOutboxEventData.class);
        verify(outboxRepository, times(expectedSaves)).save(captor.capture());
        return captor.getAllValues();
    }

    private static CardOutboxEventData pendingWithRetries(int retryCount) {
        return new CardOutboxEventData(
                UUID.randomUUID(), EVENT, Instant.now(), null, retryCount, null, OutboxEventDataStatus.PENDING);
    }
}
