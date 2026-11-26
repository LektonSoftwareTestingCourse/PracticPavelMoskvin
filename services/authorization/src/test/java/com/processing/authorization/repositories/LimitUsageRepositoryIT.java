package com.processing.authorization.repositories;

import com.processing.authorization.entities.LimitUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class LimitUsageRepositoryIT {

    private static final LocalDate USAGE_DATE = LocalDate.of(2025, 2, 10);
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("100.00");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("200.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private LimitUsageRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private String pan;

    @BeforeEach
    void clearUsage() {
        jdbc.execute("TRUNCATE TABLE limit_usage RESTART IDENTITY CASCADE");
        pan = String.format(Locale.ROOT, "400000%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    }

    @Test
    void shouldInsertUsageOnFirstUpsert() {
        int updated = upsert("35.25");

        assertThat(updated).isEqualTo(1);
        assertAmounts("35.25");
    }

    @Test
    void shouldAccumulateOnSameRowUpToTheDailyLimit() {
        assertThat(upsert("35.25")).isEqualTo(1);
        LimitUsage initial = onlyUsage();

        int updated = upsert("64.75");

        assertThat(updated).isEqualTo(1);
        assertThat(assertAmounts("100.00").getId()).isEqualTo(initial.getId());
    }

    @Test
    void shouldLeaveUsageUnchangedWhenDailyLimitIsExceeded() {
        assertThat(upsert("95.00")).isEqualTo(1);
        LimitUsage initial = onlyUsage();

        int updated = upsert("5.01");

        assertThat(updated).isZero();
        assertThat(onlyUsage()).usingRecursiveComparison().isEqualTo(initial);
    }

    private int upsert(String amount) {
        return transactions.execute(status ->
                repository.upsertLimitUsage(pan, USAGE_DATE, new BigDecimal(amount), DAILY_LIMIT, MONTHLY_LIMIT));
    }

    private LimitUsage assertAmounts(String amount) {
        LimitUsage usage = onlyUsage();
        assertThat(usage.getDailyAmount()).isEqualByComparingTo(amount);
        assertThat(usage.getMonthlyAmount()).isEqualByComparingTo(amount);
        return usage;
    }

    private LimitUsage onlyUsage() {
        List<LimitUsage> rows = transactions.execute(status -> repository.findAll());
        assertThat(rows).singleElement().satisfies(usage -> {
            assertThat(usage.getPan()).isEqualTo(pan);
            assertThat(usage.getUsageDate()).isEqualTo(USAGE_DATE);
        });
        return rows.getFirst();
    }
}
