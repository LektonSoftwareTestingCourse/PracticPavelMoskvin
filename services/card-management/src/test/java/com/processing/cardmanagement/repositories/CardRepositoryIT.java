package com.processing.cardmanagement.repositories;

import com.processing.cardmanagement.exceptions.PanCollisionException;
import com.processing.cardmanagement.mappers.CardExpiryDateMapper;
import com.processing.cardmanagement.mappers.CardPersistenceMapperImpl;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CardRepositoryPersistenceAdapter.class, CardPersistenceMapperImpl.class, CardExpiryDateMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class CardRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CardRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void clearCards() {
        jdbc.execute("TRUNCATE TABLE cards RESTART IDENTITY CASCADE");
    }

    @Test
    void shouldRoundTripAllCardFields() {
        Card card = activeCard(uniquePan());

        transactions.executeWithoutResult(status -> repository.create(card));

        transactions.executeWithoutResult(status -> assertThat(repository.findByPan(card.pan()))
                .hasValueSatisfying(found -> assertThat(found)
                        .usingRecursiveComparison()
                        .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                        .isEqualTo(card)));
    }

    @Test
    void shouldRejectAnotherActiveCardWithSamePan() {
        Card first = activeCard(uniquePan());
        Card duplicate = activeCard(first.pan());
        transactions.executeWithoutResult(status -> repository.create(first));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repository.create(duplicate)))
                .isInstanceOf(PanCollisionException.class);

        transactions.executeWithoutResult(status -> assertThat(repository.findByPan(first.pan()))
                .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(first.id())));
    }

    @Test
    void shouldReusePanWhenPreviousCardIsDeleted() {
        Card deleted = activeCard(uniquePan()).deleted();
        Card replacement = activeCard(deleted.pan());
        transactions.executeWithoutResult(status -> repository.create(deleted));

        transactions.executeWithoutResult(status -> repository.create(replacement));

        transactions.executeWithoutResult(status -> {
            assertThat(repository.findByPan(replacement.pan()))
                    .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(replacement.id()));
            assertThat(jdbc.queryForList("SELECT status FROM cards WHERE pan = ?", String.class, replacement.pan()))
                    .containsExactlyInAnyOrder("DELETED", "ACTIVE");
        });
    }

    private static Card activeCard(String pan) {
        return new Card(UUID.randomUUID(), pan, pan.substring(0, 6), "Integration Test", YearMonth.of(2035, 7),
                CardStatus.ACTIVE, "643", new BigDecimal("1234"), new BigDecimal("9876"),
                new BigDecimal("4321"), "ISS001", Instant.parse("2025-01-02T03:04:05Z"));
    }

    private static String uniquePan() {
        return String.format(Locale.ROOT, "400000%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    }
}
