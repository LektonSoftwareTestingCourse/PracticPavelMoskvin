package com.processing.service;

import com.processing.config.SwitchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Получение комиссии эквайринга: при недоступности Merchant Acquirer транзакция не блокируется (fee = null).
 */
class MerchantAcquirerClientTest {

    private static final String MERCHANT_URL = "http://merchant";
    private static final String FEE_PATH = MERCHANT_URL + "/api/simulator/merchant/fee";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-30T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    private MockRestServiceServer server;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SwitchProperties properties = new SwitchProperties(
                "1.0.0", Map.of(), "http://authorization", "http://logger", MERCHANT_URL, null, null, null);
        client = new MerchantAcquirerClient(properties, builder.build());
    }

    @Test
    void shouldReturnFeeWhenMerchantAcquirerResponds() {
        // Arrange
        server.expect(requestTo(FEE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.stan").value("000001"))
                .andExpect(jsonPath("$.terminalId").value("TERM0001"))
                .andRespond(withSuccess("{\"acquirerFee\":15.50}", MediaType.APPLICATION_JSON));

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isEqualByComparingTo("15.50");
        server.verify();
    }

    @Test
    void shouldReturnNullWhenResponseBodyIsEmpty() {
        // Arrange
        server.expect(requestTo(FEE_PATH)).andRespond(withSuccess());

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isNull();
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerFails() {
        // Arrange
        server.expect(requestTo(FEE_PATH)).andRespond(withServerError());

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isNull();
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerIsUnreachable() {
        // Arrange
        server.expect(requestTo(FEE_PATH)).andRespond(withException(new IOException("Connection refused")));

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isNull();
    }

    private BigDecimal fetchFee() {
        return client.fetchAcquiringFee(TRANSMISSION_TIME, "000001", "4000001234567890", "TERM0001", AMOUNT);
    }
}
