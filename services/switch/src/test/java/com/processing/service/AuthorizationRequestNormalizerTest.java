package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нормализация terminalId (8 символов) и merchantId (15 символов) перед отправкой в Authorization.
 */
class AuthorizationRequestNormalizerTest {

    @ParameterizedTest(name = "terminalId=''{0}'' -> ''{1}''")
    @CsvSource(nullValues = "NULL", value = {
        "NULL,       NULL",
        "'   ',      '   '",
        "T1,         T1000000",
        "TERM001,    TERM0010",
        "TERM0001,   TERM0001",
        "TERM00012,  TERM0001"
    })
    void shouldPadOrTruncateTerminalIdToEightCharacters(String terminalId, String expected) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeTerminalId(terminalId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @ParameterizedTest(name = "merchantId=''{0}'' -> ''{1}''")
    @CsvSource(nullValues = "NULL", value = {
        "NULL,              NULL",
        "'  ',              '  '",
        "MERCH0000000002,   MERCH0000000002",
        "MERCH00000000002,  MERCH0000000002",
        "MERCH10000000002,  MERCH1000000000",
        "SHOP00000000002X,  SHOP00000000002",
        "MERCH000000000123, MERCH0000000001",
        "MERCH2,            MERCH2000000000"
    })
    void shouldNormalizeMerchantIdToFifteenCharacters(String merchantId, String expected) {
        // Act
        String normalized = AuthorizationRequestNormalizer.normalizeMerchantId(merchantId);

        // Assert
        assertThat(normalized).isEqualTo(expected);
    }

    @Test
    void shouldNormalizeOnlyTerminalAndMerchantIdsWhenNormalizingRequest() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", Instant.parse("2026-10-30T10:00:00Z"),
                "T1", "POS", "MERCH2", "5045", "ACQ002", null);

        // Act
        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        // Assert
        assertThat(normalized.terminalId()).isEqualTo("T1000000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH2000000000");
        assertThat(normalized)
                .usingRecursiveComparison()
                .ignoringFields("terminalId", "merchantId")
                .isEqualTo(request);
    }
}
