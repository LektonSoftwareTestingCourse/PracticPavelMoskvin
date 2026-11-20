package com.processing.apitests.transactions;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.TestData;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayLoggerApiTest extends ApiTestBase {
    @BeforeEach
    void resetWireMock() {
        given()
                .spec(wireMock)
            .when()
                .post("/__admin/reset")
            .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("TC-P3-09: Gateway rejects locally or proxies the Switch response unchanged")
    void gatewayValidationAndProxyContract() {
        Map<String, Object> switchResponse = new LinkedHashMap<>();
        switchResponse.put("mti", "0110");
        switchResponse.put("stan", "123456");
        switchResponse.put("rrn", null);
        switchResponse.put("authCode", null);
        switchResponse.put("responseCode", "14");
        switchResponse.put("status", "DECLINED");
        switchResponse.put("declineReason", "CARD_NOT_FOUND");
        switchResponse.put("processingTimeMs", 3);
        stubRoute(200, switchResponse);

        Map<String, Object> invalid = TestData.authorization(
                "400000000000000", BigDecimal.ONE);
        given()
                .spec(contractGateway)
                .body(invalid)
            .when()
                .post("/api/transactions")
            .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("serviceName", equalTo("gateway"))
                .body("message", containsString("pan"));
        assertRouteRequestCount(0);

        Map<String, Object> valid = TestData.authorization(
                "4000000000000000", BigDecimal.ONE);
        valid.put("stan", "123456");
        given()
                .spec(contractGateway)
                .body(valid)
            .when()
                .post("/api/transactions")
            .then()
                .statusCode(200)
                .body("mti", equalTo("0110"))
                .body("stan", equalTo("123456"))
                .body("rrn", nullValue())
                .body("authCode", nullValue())
                .body("responseCode", equalTo("14"))
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"))
                .body("processingTimeMs", equalTo(3));
        assertRouteRequestCount(1);
    }

    @Test
    @DisplayName("Gateway converts a Switch connection failure into its own 503 contract")
    void gatewayDownstreamUnavailableContract() {
        Map<String, Object> mapping = Map.of(
                "request", Map.of("method", "POST", "urlPath", "/api/internal/route"),
                "response", Map.of("fault", "CONNECTION_RESET_BY_PEER"));
        given()
                .spec(wireMock)
                .body(mapping)
            .when()
                .post("/__admin/mappings")
            .then()
                .statusCode(201);

        given()
                .spec(contractGateway)
                .body(TestData.authorization("4000000000000000", BigDecimal.ONE))
            .when()
                .post("/api/transactions")
            .then()
                .statusCode(500);
    }

    @Test
    @DisplayName("Internal Switch route performs the same authorization contract as Gateway")
    void internalSwitchRoute() {
        String pan = createCard(
                new BigDecimal("10000"),
                new BigDecimal("10000"),
                new BigDecimal("100000"));

        given()
                .spec(switchService)
                .body(TestData.authorization(pan, new BigDecimal("10")))
            .when()
                .post("/api/internal/route")
            .then()
                .statusCode(200)
                .body("mti", equalTo("0100"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("\\d{12}"));
    }

    @ParameterizedTest(name = "Gateway invalid class: {0}={1}")
    @MethodSource("invalidGatewayFields")
    @DisplayName("Gateway validates each request field independently")
    void gatewayValidationMatrix(String field, Object value, String messagePart) {
        Map<String, Object> request = TestData.copyWith(
                TestData.authorization("4000000000000000", BigDecimal.ONE), field, value);

        given()
                .spec(contractGateway)
                .body(request)
            .when()
                .post("/api/transactions")
            .then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("message", containsString(messagePart))
                .body("serviceName", equalTo("gateway"));
    }

    @Test
    @DisplayName("Gateway rate limiting returns 429, Retry-After and a diagnostic body")
    void transactionRateLimitContract() {
        String clientIp = TestData.clientIp();
        Map<String, Object> invalidBody = Map.of();
        List<CompletableFuture<Integer>> calls = IntStream.range(0, 120)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> given()
                        .spec(gateway)
                        .header("X-Forwarded-For", clientIp)
                        .body(invalidBody)
                    .when()
                        .post("/api/transactions")
                        .statusCode()))
                .toList();
        List<Integer> statuses = new ArrayList<>();
        calls.forEach(call -> statuses.add(call.join()));

        assertTrue(statuses.contains(429), "At least one request must exhaust the client bucket");

        given()
                .spec(gateway)
                .header("X-Forwarded-For", clientIp)
                .body(invalidBody)
            .when()
                .post("/api/transactions")
            .then()
                .statusCode(429)
                .header("Retry-After", equalTo("1"))
                .body("error", equalTo("RATE_LIMIT_EXCEEDED"))
                .body("retryAfterMs", equalTo(1000));
    }

    @Test
    @DisplayName("Internal log handles new, duplicate and conflicting deliveries")
    void internalLogIdempotencyAndConflict() {
        Map<String, Object> transaction = TestData.loggedTransaction("4000000000000000");
        String id = transaction.get("id").toString();

        given()
                .spec(logger)
                .body(transaction)
            .when()
                .post("/api/internal/log")
            .then()
                .statusCode(201)
                .body("id", equalTo(id))
                .body("status", equalTo("stored"));

        given()
                .spec(logger)
                .body(transaction)
            .when()
                .post("/api/internal/log")
            .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("stan", equalTo(transaction.get("stan")));

        Map<String, Object> conflict = TestData.copyWith(transaction, "amount", 1001);
        given()
                .spec(logger)
                .body(conflict)
            .when()
                .post("/api/internal/log")
            .then()
                .statusCode(409)
                .body("error", equalTo("Transaction conflict"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("retryAfterMs", equalTo("0"));
    }

    @Test
    @DisplayName("Gateway-to-Logger flow is eventually visible and selected with GPath")
    void approvedTransactionEventuallyAppearsInSearch() {
        String pan = createCard(
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("1000000"));
        Map<String, Object> request = TestData.authorization(pan, new BigDecimal("100"));
        String stan = request.get("stan").toString();

        Response authorizationResponse = gatewayRequest()
                .body(request)
            .when()
                .post("/api/transactions")
            .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("\\d{12}"))
                .extract().response();

        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given()
                        .spec(gateway)
                        .queryParam("pan", pan)
                        .queryParam("status", "APPROVED")
                    .when()
                        .get("/api/transactions/search")
                    .then()
                        .statusCode(200)
                        .body("transactions.findAll { it.stan == '%s' }".formatted(stan), hasSize(1))
                        .body("transactions.find { it.stan == '%s' }.rrn".formatted(stan),
                                equalTo(authorizationResponse.jsonPath().getString("rrn"))));
    }

    @Test
    @DisplayName("A Gateway-rejected request never appears in the asynchronous log")
    void rejectedAtGatewayNeverAppearsInLogger() {
        Map<String, Object> request = TestData.authorization(
                "400000000000000", BigDecimal.ONE);
        String stan = request.get("stan").toString();

        gatewayRequest()
                .body(request)
            .when()
                .post("/api/transactions")
            .then()
                .statusCode(400);

        await()
                .during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given()
                        .spec(gateway)
                        .queryParam("limit", 50)
                    .when()
                        .get("/api/transactions/search")
                    .then()
                        .statusCode(200)
                        .body("transactions.findAll { it.stan == '%s' }".formatted(stan), empty()));
    }

    @Test
    @DisplayName("Search/export/dashboard expose stored data through all public read endpoints")
    void searchExportAndDashboardContracts() {
        Map<String, Object> transaction = TestData.loggedTransaction("4000000000000000");
        given().spec(logger).body(transaction)
            .when().post("/api/internal/log")
            .then().statusCode(201);

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        given()
                .spec(gateway)
                .queryParam("pan", transaction.get("pan"))
                .queryParam("status", "APPROVED")
                .queryParam("dateFrom", today)
                .queryParam("dateTo", today)
                .queryParam("merchantId", transaction.get("merchantId"))
                .queryParam("issuerId", transaction.get("issuerId"))
                .queryParam("mcc", transaction.get("mcc"))
                .queryParam("limit", 1)
                .queryParam("offset", 0)
            .when()
                .get("/api/transactions/search")
            .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("transactions", hasSize(1))
                .body("transactions[0].id", equalTo(transaction.get("id")));

        given()
                .spec(gateway)
                .queryParam("pan", transaction.get("pan"))
            .when()
                .get("/api/transactions/export")
            .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("transactions.csv"))
                .body(containsString(transaction.get("id").toString()));

        given().spec(gateway).when().get("/api/dashboard/stats")
            .then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(1));
        given().spec(gateway).queryParam("limit", 1).when().get("/api/dashboard/recent")
            .then().statusCode(200).body("$", hasSize(1));
        given().spec(gateway).queryParam("granularity", "hour").when().get("/api/dashboard/charts")
            .then().statusCode(200).body("$", is(notNullValue()));
    }

    @ParameterizedTest(name = "read API rejects {0}={1} on {2}")
    @MethodSource("invalidReadParameters")
    @DisplayName("Search and dashboard validate pagination, enums and date ranges")
    void readEndpointsRejectInvalidParameters(String name, Object value, String path) {
        given()
                .spec(gateway)
                .queryParam(name, value)
            .when()
                .get(path)
            .then()
                .statusCode(400)
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", notNullValue());
    }

    @Test
    @DisplayName("Search returns an explicit empty collection for unique criteria")
    void emptySearch() {
        given()
                .spec(gateway)
                .queryParam("merchantId", "NO-SUCH-MERCHANT")
            .when()
                .get("/api/transactions/search")
            .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", empty());
    }

    private void stubRoute(int status, Map<String, Object> responseBody) {
        Map<String, Object> mapping = Map.of(
                "request", Map.of("method", "POST", "urlPath", "/api/internal/route"),
                "response", Map.of(
                        "status", status,
                        "jsonBody", responseBody,
                        "headers", Map.of("Content-Type", "application/json")));
        given()
                .spec(wireMock)
                .body(mapping)
            .when()
                .post("/__admin/mappings")
            .then()
                .statusCode(201);
    }

    private void assertRouteRequestCount(int expected) {
        given()
                .spec(wireMock)
                .body(Map.of("method", "POST", "urlPath", "/api/internal/route"))
            .when()
                .post("/__admin/requests/count")
            .then()
                .statusCode(200)
                .body("count", equalTo(expected));
    }

    private static Stream<Arguments> invalidGatewayFields() {
        return Stream.of(
                Arguments.of("mti", "0400", "mti"),
                Arguments.of("stan", "", "stan"),
                Arguments.of("pan", "400000000000000", "pan"),
                Arguments.of("processingCode", "", "processingCode"),
                Arguments.of("amount", 0, "amount"),
                Arguments.of("currencyCode", "64", "currencyCode"),
                Arguments.of("transmissionDateTime", null, "transmissionDateTime"),
                Arguments.of("terminalId", "", "terminalId"),
                Arguments.of("merchantId", "", "merchantId"),
                Arguments.of("mcc", "54A1", "mcc"),
                Arguments.of("acquirerId", "", "acquirerId")
        );
    }

    private static Stream<Arguments> invalidReadParameters() {
        return Stream.of(
                Arguments.of("limit", 0, "/api/transactions/search"),
                Arguments.of("limit", 501, "/api/transactions/search"),
                Arguments.of("offset", -1, "/api/transactions/search"),
                Arguments.of("status", "UNKNOWN", "/api/transactions/search"),
                Arguments.of("limit", 0, "/api/dashboard/recent"),
                Arguments.of("limit", 501, "/api/dashboard/recent"),
                Arguments.of("granularity", "minute", "/api/dashboard/charts")
        );
    }
}
