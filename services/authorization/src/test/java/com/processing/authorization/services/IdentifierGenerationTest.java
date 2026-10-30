package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.repositories.LimitUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-AUTH-22 / TC-AUTH-23: формат и уникальность RRN, формат authCode.
 * RRN = {год % 10}{день года, 3 цифры}{8 цифр счётчика}; счётчик берётся блоками по 1 000 из rrn_seq.
 */
@ExtendWith(MockitoExtension.class)
class IdentifierGenerationTest {

    private static final int RRN_BLOCK_SIZE = 1000;

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
        authService = new AuthServiceImpl(limitUsageRepository, eventNotifier, binLookupClient, cardManagementClient);
    }

    @Test
    void shouldBuildTwelveDigitRrnFromDatePrefixAndSequenceBlock() {
        // Arrange
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(7L);

        // Act
        String rrn = authService.generateRRN();

        // Assert
        assertThat(rrn).matches("\\d{12}");
        assertThat(Integer.parseInt(rrn.substring(1, 4))).isBetween(1, 366);
        assertThat(rrn.substring(4)).isEqualTo("00007000");
    }

    @Test
    void shouldIssueThousandRrnsFromOneSequenceBlock() {
        // Arrange
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(1L);

        // Act
        List<String> rrns = generateRrns(RRN_BLOCK_SIZE);

        // Assert
        assertThat(rrns).doesNotHaveDuplicates();
        assertThat(rrns.get(0)).endsWith("00001000");
        assertThat(rrns.get(RRN_BLOCK_SIZE - 1)).endsWith("00001999");
        verify(limitUsageRepository, times(1)).fetchRrnBlock();
    }

    @Test
    void shouldFetchNextBlockAndStayUniqueWhenBlockBoundaryIsCrossed() {
        // Arrange
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(1L, 2L);

        // Act
        List<String> rrns = generateRrns(RRN_BLOCK_SIZE + 1);

        // Assert
        assertThat(rrns).doesNotHaveDuplicates();
        assertThat(rrns.get(RRN_BLOCK_SIZE)).endsWith("00002000");
        verify(limitUsageRepository, times(2)).fetchRrnBlock();
    }

    @RepeatedTest(20)
    void shouldGenerateSixCharacterAlphanumericUppercaseAuthCode() {
        // Act
        String authCode = authService.generateAuthCode();

        // Assert
        assertThat(authCode).matches("[A-Z0-9]{6}");
    }

    @Test
    void shouldGenerateDifferentAuthCodesForConsecutiveCalls() {
        // Act
        Set<String> codes = IntStream.range(0, 100)
                .mapToObj(i -> authService.generateAuthCode())
                .collect(Collectors.toSet());

        // Assert: 36^6 ~ 2,2 млрд вариантов - совпадения в 100 вызовах практически исключены
        assertThat(codes).hasSizeGreaterThan(95);
    }

    private List<String> generateRrns(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> authService.generateRRN())
                .toList();
    }
}
