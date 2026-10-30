package com.processing.gateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-P3-09 (часть 1, логика фильтра): невалидная транзакция получает 400 + ErrorResponse и не уходит дальше
 * по цепочке (в Switch); валидная проксируется с тем же телом.
 */
class TransactionValidationFilterTest {

    private static final String VALID_BODY = """
            {"mti":"0100","stan":"000001","pan":"4000001234567890","processingCode":"000000",
             "amount":1000,"currencyCode":"643","transmissionDateTime":"2026-10-30T10:00:00Z",
             "terminalId":"TERM0001","terminalType":"POS","merchantId":"MERCH0000000002",
             "mcc":"5045","acquirerId":"ACQ002"}
            """;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    private SimpleMeterRegistry meterRegistry;
    private TransactionValidationFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new TransactionValidationFilter(objectMapper, new TransactionRequestValidator(),
                new GatewayMetrics(meterRegistry));
    }

    @Test
    void shouldForwardValidTransactionWithOriginalBody() {
        // Arrange
        MockServerWebExchange exchange = transactionExchange(VALID_BODY);

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded.get()).isNotNull();
        String forwardedBody = DataBufferUtils.join(forwarded.get().getRequest().getBody())
                .map(buffer -> buffer.toString(StandardCharsets.UTF_8))
                .block();
        assertThat(forwardedBody).isEqualTo(VALID_BODY);
        assertThat(forwarded.get().getRequest().getHeaders().getContentLength())
                .isEqualTo(VALID_BODY.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void shouldRejectInvalidTransactionWith400AndNotForwardItToSwitch() {
        // Arrange
        MockServerWebExchange exchange = transactionExchange(VALID_BODY.replace("4000001234567890", "400000123456789"));

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"error\":\"VALIDATION_ERROR\"")
                .contains("Field 'pan' must be exactly 16 digits")
                .contains("\"serviceName\":\"gateway\"");
        assertThat(rejectedCounter("validation_invalid_request")).isEqualTo(1.0);
    }

    @Test
    void shouldRejectMalformedJsonWith400() {
        // Arrange
        MockServerWebExchange exchange = transactionExchange("{not-json");

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("Request body must be valid JSON");
        assertThat(rejectedCounter("validation_invalid_json")).isEqualTo(1.0);
    }

    @Test
    void shouldRejectEmptyBodyWith400() {
        // Arrange
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions"));

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldPassThroughRequestsOtherThanTransactionPost() {
        // Arrange
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/cards").body("{not-json"));

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded.get()).isSameAs(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldPassThroughGetOnTransactionsPath() {
        // Arrange
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/transactions"));

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(forwarded.get()).isSameAs(exchange);
    }

    private double rejectedCounter(String reason) {
        return meterRegistry.get("gateway.requests.rejected").tag("reason", reason).counter().count();
    }

    private static MockServerWebExchange transactionExchange(String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }
}
