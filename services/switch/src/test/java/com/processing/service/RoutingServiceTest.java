package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-P3-03 (REQ-SW-01): определение issuerId по BIN из таблицы маршрутизации Switch.
 */
class RoutingServiceTest {

    /** Таблица из switch/application.yml. */
    private static final Map<String, String> BIN_TABLE = Map.of(
            "400000", "ISS001",
            "400001", "ISS002",
            "400002", "ISS003",
            "400003", "ISS004",
            "400004", "ISS005");

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new RoutingService(propertiesWithBinTable(BIN_TABLE));
    }

    @ParameterizedTest(name = "pan={0} -> {1}")
    @CsvSource({
        "4000001234567890, ISS001",
        "4000021234567890, ISS003",
        "4000041234567890, ISS005",
        "400000,           ISS001"
    })
    void shouldReturnIssuerIdWhenBinIsKnown(String pan, String expectedIssuerId) {
        // Act
        String issuerId = routingService.getIssuerIdByPan(pan);

        // Assert
        assertThat(issuerId).isEqualTo(expectedIssuerId);
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"4000051234567890", "3999991234567890", "5000001234567890"})
    void shouldThrowUnknownBinWhenBinIsAbsentInTable(String pan) {
        // Act + Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining(pan.substring(0, 6));
    }

    @ParameterizedTest(name = "pan=''{0}''")
    @NullSource
    @ValueSource(strings = {"", "40000"})
    void shouldThrowUnknownBinWhenPanIsNullOrShorterThanSixDigits(String pan) {
        // Act + Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }

    @Test
    void shouldNotDependOnLaterChangesOfSourceTable() {
        // Arrange
        Map<String, String> mutableTable = new HashMap<>(BIN_TABLE);
        RoutingService service = new RoutingService(propertiesWithBinTable(mutableTable));
        mutableTable.put("400005", "ISS006");

        // Act + Assert
        assertThatThrownBy(() -> service.getIssuerIdByPan("4000051234567890"))
                .isInstanceOf(UnknownBinException.class);
    }

    private static SwitchProperties propertiesWithBinTable(Map<String, String> binTable) {
        return new SwitchProperties("1.0.0", binTable, null, null, null, null, null, null);
    }
}
