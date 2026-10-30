package com.processing.gateway.downstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Классификация ошибок downstream: сетевые сбои (по всей цепочке причин) -> 503 Gateway.
 */
class DownstreamExceptionUtilsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailableFailures")
    void shouldTreatNetworkFailureAnywhereInCauseChainAsUnavailable(String description, Throwable failure) {
        // Act
        boolean unavailable = DownstreamExceptionUtils.isDownstreamUnavailable(failure);

        // Assert
        assertThat(unavailable).isTrue();
    }

    static Stream<Arguments> unavailableFailures() {
        return Stream.of(
                Arguments.of("ConnectException", new ConnectException("refused")),
                Arguments.of("SocketTimeoutException", new SocketTimeoutException("read")),
                Arguments.of("HttpTimeoutException", new HttpTimeoutException("timeout")),
                Arguments.of("HttpConnectTimeoutException", new HttpConnectTimeoutException("connect")),
                Arguments.of("ResourceAccessException", new ResourceAccessException("I/O error")),
                Arguments.of("wrapped twice", new IllegalStateException(new RuntimeException(new ConnectException()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherFailures")
    void shouldNotTreatApplicationFailureAsUnavailable(String description, Throwable failure) {
        // Act
        boolean unavailable = DownstreamExceptionUtils.isDownstreamUnavailable(failure);

        // Assert
        assertThat(unavailable).isFalse();
    }

    static Stream<Arguments> otherFailures() {
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("IllegalArgumentException", new IllegalArgumentException("bad")),
                Arguments.of("generic IOException", new IOException("broken pipe")),
                Arguments.of("TimeoutException", new TimeoutException("not network")));
    }

    @Test
    void shouldRethrowIoExceptionAsIs() {
        // Arrange
        IOException failure = new IOException("io");

        // Act + Assert
        assertThatThrownBy(() -> DownstreamExceptionUtils.rethrow(failure)).isSameAs(failure);
    }

    @Test
    void shouldRethrowRuntimeExceptionAsIs() {
        // Arrange
        IllegalStateException failure = new IllegalStateException("runtime");

        // Act + Assert
        assertThatThrownBy(() -> DownstreamExceptionUtils.rethrow(failure)).isSameAs(failure);
    }

    @Test
    void shouldWrapOtherCheckedExceptionIntoIoException() {
        // Arrange
        Exception failure = new Exception("checked");

        // Act + Assert
        assertThatThrownBy(() -> DownstreamExceptionUtils.rethrow(failure))
                .isExactlyInstanceOf(IOException.class)
                .hasCause(failure);
    }
}
