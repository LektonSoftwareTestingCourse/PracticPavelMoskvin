package com.processing.controller;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/internal/route}: контроллер всегда отвечает 200 и проксирует решение RouteService.
 */
@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    @Mock
    private RouteService routeService;

    private RouteController controller;

    @BeforeEach
    void setUp() {
        controller = new RouteController(routeService);
    }

    @ParameterizedTest(name = "pan=''{0}''")
    @NullSource
    @ValueSource(strings = {"4000001234567890", "4000001", "40000012"})
    void shouldReturnRouteServiceResponseWithStatus200ForAnyPan(String pan) {
        // Arrange
        AuthorizationRequest request = requestWithPan(pan);
        AuthorizationResponse declined = AuthorizationResponse.unknownBin("000001");
        when(routeService.route(request)).thenReturn(declined);

        // Act
        ResponseEntity<AuthorizationResponse> response = controller.routeTransaction(request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(declined);
    }

    private static AuthorizationRequest requestWithPan(String pan) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000", new BigDecimal("1000"), "643",
                Instant.parse("2026-10-30T10:00:00Z"), "TERM0001", "POS", "MERCH0000000002",
                "5045", "ACQ002", null);
    }
}
