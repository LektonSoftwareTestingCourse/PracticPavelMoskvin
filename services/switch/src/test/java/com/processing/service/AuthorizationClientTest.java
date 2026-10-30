package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.CircuitBreakerFactory;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HTTP-контракт Switch -> Authorization: retry, circuit breaker, валидация тела ответа, rollback, health.
 * HTTP-сервер подменён {@link MockRestServiceServer} - сеть не используется.
 */
class AuthorizationClientTest {

    private static final String AUTH_URL = "http://authorization";
    private static final int MAX_ATTEMPTS = 3;
    private static final String RRN = "612345678901";

    private static final String APPROVED_JSON = """
            {"mti":"0100","stan":"000001","rrn":"612345678901","authCode":"A1B2C3",
             "responseCode":"00","status":"APPROVED","declineReason":null,"processingTimeMs":12}
            """;
    private static final String DECLINED_JSON = """
            {"mti":"0100","stan":"000001","rrn":null,"authCode":null,
             "responseCode":"05","status":"DECLINED","declineReason":"CARD_BLOCKED","processingTimeMs":3}
            """;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private AuthorizationClient authorizationClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SwitchProperties properties = properties();
        circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties);
        authorizationClient = new AuthorizationClient(
                properties,
                builder.build(),
                RetryFactory.authorizationRetry(properties),
                circuitBreaker);
    }

    @Test
    void shouldReturnApprovedResponseWhenAuthorizationApproves() {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/api/internal/authorize"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.pan").value("4000001234567890"))
                .andExpect(jsonPath("$.issuerId").value("ISS001"))
                .andRespond(withSuccess(APPROVED_JSON, MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.rrn()).isEqualTo(RRN);
        server.verify();
    }

    @Test
    void shouldReturnDeclinedBodyWhenAuthorizationRespondsWithErrorStatus() {
        // Arrange: Authorization отдаёт DECLINED c HTTP 403 - тело важнее статуса
        server.expect(requestTo(AUTH_URL + "/api/internal/authorize"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(DECLINED_JSON));

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("CARD_BLOCKED");
        server.verify();
    }

    @ParameterizedTest(name = "body={0}")
    @ValueSource(strings = {"{\"status\":\"PENDING\"}", "{\"status\":null}"})
    void shouldRetryAndFailWhenResponseStatusIsNeitherApprovedNorDeclined(String body) {
        // Arrange
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTH_URL + "/api/internal/authorize"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Act + Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("STAN=000001")
                .hasMessageContaining("after 3 attempts")
                .hasMessageContaining("Invalid response from Authorization");
        server.verify();
    }

    @Test
    void shouldRetryAndFailWhenResponseBodyIsEmpty() {
        // Arrange
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTH_URL + "/api/internal/authorize"))
                .andRespond(withServerError());

        // Act + Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
        server.verify();
    }

    @Test
    void shouldReportRootCauseWhenNetworkFailsOnEveryAttempt() {
        // Arrange
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTH_URL + "/api/internal/authorize"))
                .andRespond(withException(new IOException("Connection refused")));

        // Act + Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Last error: Connection refused");
        server.verify();
    }

    @Test
    void shouldSucceedWhenSecondAttemptIsSuccessful() {
        // Arrange
        server.expect(ExpectedCount.once(), requestTo(AUTH_URL + "/api/internal/authorize"))
                .andRespond(withException(new IOException("Connection reset")));
        server.expect(ExpectedCount.once(), requestTo(AUTH_URL + "/api/internal/authorize"))
                .andRespond(withSuccess(APPROVED_JSON, MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = authorizationClient.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        server.verify();
    }

    @Test
    void shouldFailFastWithoutHttpCallWhenCircuitBreakerIsOpen() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act + Assert
        assertThatThrownBy(() -> authorizationClient.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
        server.verify();
    }

    @Test
    void shouldSendRollbackWithOriginalPanAndAmountWhenRollingBack() {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/api/internal/rollback"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.rrn").value(RRN))
                .andExpect(jsonPath("$.pan").value("4000001234567890"))
                .andExpect(jsonPath("$.amount").value(1000))
                .andRespond(withSuccess(
                        "{\"rrn\":\"612345678901\",\"responseCode\":\"00\",\"status\":\"APPROVED\"}",
                        MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        server.verify();
    }

    @Test
    void shouldReturnDeclinedRollbackWhenAuthorizationRejectsRollback() {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/api/internal/rollback"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rrn\":\"612345678901\",\"responseCode\":\"05\",\"status\":\"DECLINED\","
                                + "\"declineReason\":\"ALREADY_ROLLED_BACK\"}"));

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("ALREADY_ROLLED_BACK");
    }

    @ParameterizedTest(name = "body={0}")
    @ValueSource(strings = {"{\"status\":\"UNKNOWN\"}", "{\"status\":null}"})
    void shouldReturnNullWhenRollbackResponseIsInvalid(String body) {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/api/internal/rollback"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackBodyIsEmpty() {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/api/internal/rollback")).andRespond(withServerError());

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackFailsWithNetworkError() {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/api/internal/rollback"))
                .andRespond(withException(new IOException("Connection refused")));

        // Act
        RollbackResponse response = authorizationClient.rollback(request(), RRN);

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReportOkWhenAuthorizationHealthResponds() {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/health")).andExpect(method(HttpMethod.GET)).andRespond(withSuccess());

        // Act
        String health = authorizationClient.checkHealth();

        // Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    void shouldReportDownWhenAuthorizationHealthFails() {
        // Arrange
        server.expect(requestTo(AUTH_URL + "/health")).andRespond(withServerError());

        // Act
        String health = authorizationClient.checkHealth();

        // Assert
        assertThat(health).isEqualTo("down");
    }

    private static SwitchProperties properties() {
        return new SwitchProperties(
                "1.0.0",
                Map.of("400000", "ISS001"),
                AUTH_URL,
                "http://logger",
                "http://merchant",
                new SwitchProperties.HttpProperties(100, 100, 100),
                new SwitchProperties.RetryProperties(MAX_ATTEMPTS, List.of(1L, 1L, 1L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 30_000)));
    }

    private static AuthorizationRequest request() {
        return new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000", new BigDecimal("1000"), "643",
                Instant.parse("2026-10-30T10:00:00Z"), "TERM0001", "POS", "MERCH0000000002",
                "5045", "ACQ002", "ISS001");
    }
}
