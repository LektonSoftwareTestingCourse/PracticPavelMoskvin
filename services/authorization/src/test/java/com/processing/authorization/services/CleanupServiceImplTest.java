package com.processing.authorization.services;

import com.processing.authorization.repositories.LimitUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ежемесячная очистка limit_usage: удаляется ровно предыдущий календарный месяц.
 */
@ExtendWith(MockitoExtension.class)
class CleanupServiceImplTest {

    @Mock
    private LimitUsageRepository limitUsageRepository;

    private CleanupServiceImpl cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new CleanupServiceImpl(limitUsageRepository);
    }

    @Test
    void shouldDeleteRecordsFromFirstToLastDayOfPreviousMonth() {
        // Arrange
        LocalDate previousMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(1);

        // Act
        cleanupService.cleanPreviousMonthRecords();

        // Assert
        verify(limitUsageRepository).deleteByUsageDateBetween(
                previousMonth.withDayOfMonth(1),
                previousMonth.withDayOfMonth(previousMonth.lengthOfMonth()));
    }

    @Test
    void shouldNotPropagateFailureWhenDeletionFails() {
        // Arrange: задача по расписанию не должна ронять планировщик
        when(limitUsageRepository.deleteByUsageDateBetween(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        // Act + Assert
        assertThatCode(() -> cleanupService.cleanPreviousMonthRecords()).doesNotThrowAnyException();
    }
}
