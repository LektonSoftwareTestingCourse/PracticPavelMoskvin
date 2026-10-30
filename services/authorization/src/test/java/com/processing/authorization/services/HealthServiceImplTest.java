package com.processing.authorization.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TC-AUTH-01 (логика): health Authorization опрашивает зависимости и не падает при их недоступности.
 */
class HealthServiceImplTest {

    private static final String CMS_URL = "http://card-management:8081";

    private MockRestServiceServer server;
    private HealthServiceImpl healthService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        healthService = new HealthServiceImpl(builder.build());
        ReflectionTestUtils.setField(healthService, "toHealthCheck", List.of(CMS_URL));
    }

    @Test
    void shouldReportDependencyStatusUnderItsServiceNameWhenHealthResponds() {
        // Arrange
        server.expect(requestTo(CMS_URL + "/health"))
                .andRespond(withSuccess("{\"status\":\"ok\",\"service\":\"card-management\"}",
                        MediaType.APPLICATION_JSON));

        // Act
        Map<String, String> health = healthService.healthCheckAllServices();

        // Assert
        assertThat(health).containsExactlyEntriesOf(Map.of("card-management", "ok"));
    }

    @Test
    void shouldFallBackToUrlAndUnknownStatusWhenHealthBodyHasNoStringFields() {
        // Arrange
        server.expect(requestTo(CMS_URL + "/health"))
                .andRespond(withSuccess("{\"status\":1,\"service\":null}", MediaType.APPLICATION_JSON));

        // Act
        Map<String, String> health = healthService.healthCheckAllServices();

        // Assert
        assertThat(health).containsExactlyEntriesOf(Map.of(CMS_URL, "unknown"));
    }

    @Test
    void shouldReportUnknownWhenHealthBodyIsEmpty() {
        // Arrange
        server.expect(requestTo(CMS_URL + "/health")).andRespond(withSuccess());

        // Act
        Map<String, String> health = healthService.healthCheckAllServices();

        // Assert
        assertThat(health).containsExactlyEntriesOf(Map.of(CMS_URL, "unknown"));
    }

    @Test
    void shouldReportDownWhenDependencyRespondsWithError() {
        // Arrange
        server.expect(requestTo(CMS_URL + "/health")).andRespond(withServerError());

        // Act
        Map<String, String> health = healthService.healthCheckAllServices();

        // Assert
        assertThat(health).containsExactlyEntriesOf(Map.of(CMS_URL, "down"));
    }

    @Test
    void shouldReportDownWhenDependencyIsUnreachable() {
        // Arrange
        server.expect(requestTo(CMS_URL + "/health")).andRespond(withException(new IOException("Connection refused")));

        // Act
        Map<String, String> health = healthService.healthCheckAllServices();

        // Assert
        assertThat(health).containsExactlyEntriesOf(Map.of(CMS_URL, "down"));
    }
}
