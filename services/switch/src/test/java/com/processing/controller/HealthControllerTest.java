package com.processing.controller;

import com.processing.config.SwitchProperties;
import com.processing.dto.HealthResponse;
import com.processing.service.AuthorizationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code GET /health}: Switch сам по себе "ok", статус Authorization выносится в dependencies.
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private AuthorizationClient authorizationClient;

    private HealthController controller;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties("1.0.0", Map.of(), null, null, null, null, null, null);
        controller = new HealthController(properties, authorizationClient);
    }

    @ParameterizedTest(name = "authorization={0}")
    @ValueSource(strings = {"ok", "down"})
    void shouldReportOwnStatusOkAndAuthorizationDependencyStatus(String authorizationStatus) {
        // Arrange
        when(authorizationClient.checkHealth()).thenReturn(authorizationStatus);

        // Act
        HealthResponse health = controller.health().getBody();

        // Assert
        assertThat(health).isNotNull();
        assertThat(health.status()).isEqualTo("ok");
        assertThat(health.service()).isEqualTo("switch");
        assertThat(health.version()).isEqualTo("1.0.0");
        assertThat(health.dependencies()).containsExactlyEntriesOf(Map.of("authorization", authorizationStatus));
    }
}
