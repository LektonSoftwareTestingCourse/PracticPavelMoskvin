package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ключ rate limit: X-Forwarded-For -> X-Real-IP -> remote address -> "unknown".
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @ParameterizedTest(name = "X-Forwarded-For=''{0}'' -> {1}")
    @CsvSource(delimiter = '|', value = {
        "203.0.113.7                       | 203.0.113.7",
        "' 203.0.113.7 , 10.0.0.1, 10.0.0.2' | 203.0.113.7"
    })
    void shouldUseFirstForwardedForAddress(String forwardedFor, String expectedIp) {
        // Arrange
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/transactions")
                .header("X-Forwarded-For", forwardedFor)
                .header("X-Real-IP", "198.51.100.1"));

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo(expectedIp);
    }

    @Test
    void shouldUseRealIpWhenForwardedForIsBlank() {
        // Arrange
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/transactions")
                .header("X-Forwarded-For", "  ")
                .header("X-Real-IP", " 198.51.100.1 "));

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("198.51.100.1");
    }

    @Test
    void shouldUseRemoteAddressWhenNoProxyHeaders() {
        // Arrange
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/transactions")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 54321)));

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("192.0.2.10");
    }

    @Test
    void shouldReturnUnknownWhenRemoteAddressIsUnresolved() {
        // Arrange
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/transactions")
                .remoteAddress(InetSocketAddress.createUnresolved("client.invalid", 54321)));

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("unknown");
    }

    @Test
    void shouldReturnUnknownWhenNothingIsKnownAboutClient() {
        // Arrange
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/transactions"));

        // Act
        String ip = resolver.resolve(exchange);

        // Assert
        assertThat(ip).isEqualTo("unknown");
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request);
    }
}
