package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * REQ-SW-02: публикация транзакции в {@code smp.transactions} с ключом {@code transaction.log}.
 */
@ExtendWith(MockitoExtension.class)
class LoggerClientTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    void shouldPublishToTransactionsExchangeWithLogRoutingKeyWhenBrokerAccepts() {
        // Arrange
        TransactionRequest transaction = transaction();

        // Act
        boolean published = loggerClient.log(transaction);

        // Assert
        assertThat(published).isTrue();
        verify(rabbitTemplate).convertAndSend("smp.transactions", "transaction.log", transaction);
    }

    @Test
    void shouldUseTopologyConstantsSharedWithLogger() {
        // Assert: Logger объявляет ту же топологию - расхождение имён ломает асинхронную доставку
        assertThat(RabbitMQConfig.EXCHANGE).isEqualTo("smp.transactions");
        assertThat(RabbitMQConfig.ROUTING_KEY).isEqualTo("transaction.log");
        assertThat(RabbitMQConfig.QUEUE).isEqualTo("transaction-log");
    }

    @Test
    void shouldReturnFalseWhenBrokerIsUnavailable() {
        // Arrange
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Act
        boolean published = loggerClient.log(transaction());

        // Assert
        assertThat(published).isFalse();
    }

    private static TransactionRequest transaction() {
        return new TransactionRequest(
                UUID.randomUUID(), "0100", "000001", "612345678901", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002",
                "ISS001", new BigDecimal("15"), TransactionStatus.APPROVED, null, "A1B2C3", 12,
                Instant.parse("2026-10-30T10:00:00Z"), Instant.parse("2026-10-30T10:00:01Z"));
    }
}
