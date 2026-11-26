package com.processing.transactionlogger.listener;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.transactionlogger.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransactionLogDlqIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearState() {
        jdbc.execute("TRUNCATE TABLE transactions RESTART IDENTITY CASCADE");
        admin.purgeQueue(RabbitMQConfig.QUEUE);
        admin.purgeQueue(RabbitMQConfig.DLQ_QUEUE);
    }

    @Test
    void shouldDeadLetterMalformedJsonAtFirstDeliveryAndStoreNextTransaction() {
        String messageId = UUID.randomUUID().toString();
        byte[] brokenJson = "{\"rrn\":".getBytes(StandardCharsets.UTF_8);
        Message poison = MessageBuilder.withBody(brokenJson)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(messageId)
                .build();
        AtomicInteger deliveries = new AtomicInteger();
        MessagePostProcessor countDeliveries = message -> {
            if (messageId.equals(message.getMessageProperties().getMessageId())) {
                deliveries.incrementAndGet();
            }
            return message;
        };
        assertThat(listeners.getListenerContainers()).hasSize(1);
        var listener = (AbstractMessageListenerContainer) listeners.getListenerContainers().iterator().next();
        listener.addAfterReceivePostProcessors(countDeliveries);
        try {
            rabbit.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, poison);

            Message rejected = receiveDeadLetter();
            assertThat(rejected.getBody()).isEqualTo(brokenJson);
            assertThat(rejected.getMessageProperties().getMessageId()).isEqualTo(messageId);
            assertThat(rejected.getMessageProperties().getXDeathHeader()).singleElement().satisfies(death -> {
                assertThat(death.get("reason")).isEqualTo("rejected");
                assertThat(death.get("queue")).isEqualTo(RabbitMQConfig.QUEUE);
                assertThat(((Number) death.get("count")).longValue()).isEqualTo(1L);
            });
            assertThat(deliveries.get()).isEqualTo(1);
            assertThat(admin.getQueueInfo(RabbitMQConfig.QUEUE).getMessageCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class)).isZero();

            TransactionRequest valid = newTransaction();
            rabbit.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, valid);

            await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() ->
                    assertThat(jdbc.queryForList("SELECT id FROM transactions WHERE rrn = ?", UUID.class, valid.rrn()))
                            .containsExactly(valid.id()));
            assertThat(admin.getQueueInfo(RabbitMQConfig.DLQ_QUEUE).getMessageCount()).isZero();
        } finally {
            listener.removeAfterReceivePostProcessor(countDeliveries);
        }
    }

    private Message receiveDeadLetter() {
        AtomicReference<Message> received = new AtomicReference<>();
        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() -> {
            received.set(rabbit.receive(RabbitMQConfig.DLQ_QUEUE));
            assertThat(received.get()).as("dead letter").isNotNull();
        });
        return received.get();
    }

    private static TransactionRequest newTransaction() {
        var random = ThreadLocalRandom.current();
        String rrn = String.format(Locale.ROOT, "%012d", random.nextLong(1_000_000_000_000L));
        String stan = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String pan = String.format(Locale.ROOT, "400000%010d", random.nextLong(10_000_000_000L));
        Instant now = Instant.now();
        return new TransactionRequest(UUID.randomUUID(), "0100", stan, rrn, pan, "000000",
                new BigDecimal("125"), "643", "TERM0001", "POS", "MERCH0000000002", "5045",
                "ACQ002", "ISS001", new BigDecimal("5"), TransactionStatus.APPROVED,
                null, "123456", 10, now, now);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerTestConfiguration {

        @Bean
        Declarables deadLetterTopology() {
            DirectExchange exchange = new DirectExchange(RabbitMQConfig.DLX_EXCHANGE);
            Queue queue = QueueBuilder.durable(RabbitMQConfig.DLQ_QUEUE).ttl(60_000).build();
            return new Declarables(exchange, queue,
                    BindingBuilder.bind(queue).to(exchange).with(RabbitMQConfig.DLQ_ROUTING_KEY));
        }

        @Bean
        SimpleMessageConverter transactionMessageConverter() {
            SimpleMessageConverter converter = new SimpleMessageConverter();
            converter.addAllowedListPatterns(
                    "com.processing.common.dto.transactionlogger.*", "java.lang.Enum",
                    "java.math.*", "java.time.*", "java.util.UUID");
            return converter;
        }
    }
}
