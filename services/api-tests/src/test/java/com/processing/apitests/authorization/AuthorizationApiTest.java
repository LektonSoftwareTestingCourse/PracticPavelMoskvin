package com.processing.apitests.authorization;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.TestData;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class AuthorizationApiTest extends ApiTestBase {
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
    @DisplayName("TC-AUTH-01: authorization health reports CMS dependency")
    void healthReportsCardManagement() {
        given()
                .spec(authorization)
            .when()
                .get("/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("dependencies.card-management", equalTo("ok"));
    }

    @Test
    @DisplayName("TC-AUTH-02: approval returns formatted identifiers and reserves funds")
    void approvedAuthorization() {
        String pan = createCard(
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("1000000"));
        Map<String, Object> request = TestData.authorization(pan, new BigDecimal("100"));

        Response response = given()
                .spec(authorization)
                .body(request)
            .when()
                .post("/api/internal/authorize")
            .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue())
                .extract().response();

        given()
                .spec(cards)
            .when()
                .get("/api/cards/{pan}", pan)
            .then()
                .statusCode(200)
                .body("availableBalance", equalTo(99900));

        rollbackApprovedAuthorization(pan, request, response.jsonPath().getString("rrn"));
    }

    @Test
    @DisplayName("TC-P3-08: blocked, missing and poor cards map to different HTTP/domain outcomes")
    void contractBusinessDeclines() {
        String blockedPan = randomPan("400000");
        String missingPan = randomPan("400001");
        String poorPan = randomPan("400002");
        stubCard(blockedPan, 200, cardBody(blockedPan, "BLOCKED", 10000, 10000, 10000));
        stubCard(missingPan, 404, Map.of("message", "not found"));
        stubCard(poorPan, 200, cardBody(poorPan, "ACTIVE", 100, 10000, 10000));
        stubBinLookup();

        assertDecline(blockedPan, 1000, 403, "05", "CARD_BLOCKED");
        assertDecline(missingPan, 1000, 404, "14", "CARD_NOT_FOUND");
        assertDecline(poorPan, 1000, 422, "51", "INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("Status expiry and expiryDate are two independently covered decline paths")
    void expiredCardPaths() {
        String statusPan = randomPan("400000");
        String datePan = randomPan("400001");
        stubCard(statusPan, 200, cardBody(statusPan, "EXPIRED", 10000, 10000, 10000));
        Map<String, Object> expiredByDate = cardBody(datePan, "ACTIVE", 10000, 10000, 10000);
        expiredByDate.put("expiryDate", YearMonth.now().minusMonths(1).toString());
        stubCard(datePan, 200, expiredByDate);
        stubBinLookup();

        assertDecline(statusPan, 100, 403, "54", "CARD_EXPIRED");
        assertDecline(datePan, 100, 403, "54", "CARD_EXPIRED");
    }

    @Test
    @DisplayName("Inactive and payment-required cards share code 05 but keep distinct reasons")
    void inactiveAndPaymentRequiredPaths() {
        String inactivePan = randomPan("400000");
        String paymentRequiredPan = randomPan("400001");
        stubCard(inactivePan, 200, cardBody(inactivePan, "INACTIVE", 10000, 10000, 10000));
        stubCard(paymentRequiredPan, 402, Map.of("message", "payment required"));
        stubBinLookup();

        assertDecline(inactivePan, 100, 403, "05", "CARD_INACTIVE");
        assertDecline(paymentRequiredPan, 100, 403, "05", "CARD_BLOCKED");
    }

    @ParameterizedTest(name = "reserve HTTP {0} -> {2}/{3}")
    @MethodSource("reserveFailureOutcomes")
    @DisplayName("Reserve-stage failures preserve every authorization business outcome")
    void reserveStageFailures(int reserveStatus, int httpStatus, String code, String reason) {
        String pan = randomPan("400000");
        stubCard(pan, 200, cardBody(pan, "ACTIVE", 10000, 10000, 10000));
        stubBinLookup();
        stubReserve(pan, reserveStatus);

        assertDecline(pan, 100, httpStatus, code, reason);
    }

    @Test
    @DisplayName("TC-AUTH-18/24: the amount-limit outcome wins before a reservation")
    void amountLimitDeclineHasExpectedCodeAndNoBalanceEffect() {
        String pan = createCard(
                new BigDecimal("1000"),
                new BigDecimal("500"),
                new BigDecimal("500"));

        given()
                .spec(authorization)
                .body(TestData.authorization(pan, new BigDecimal("501")))
            .when()
                .post("/api/internal/authorize")
            .then()
                .statusCode(400)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"))
                .body("rrn", nullValue())
                .body("authCode", nullValue());

        given()
                .spec(cards)
            .when()
                .get("/api/cards/{pan}", pan)
            .then()
                .statusCode(200)
                .body("availableBalance", equalTo(1000));
    }

    @ParameterizedTest(name = "authorization rejects {0}={1}")
    @MethodSource("invalidAuthorizationFields")
    @DisplayName("TC-AUTH-25/28: endpoint validation covers required fields and boundaries")
    void validationRejectsOneInvalidClass(String field, Object value) {
        Map<String, Object> request = TestData.copyWith(
                TestData.authorization("4000000000000000", BigDecimal.ONE), field, value);

        given()
                .spec(authorization)
                .body(request)
            .when()
                .post("/api/internal/authorize")
            .then()
                .statusCode(400)
                .contentType("application/json")
                .body(notNullValue());
    }

    @Test
    @DisplayName("TC-AUTH-20/21: CMS failure declines, BIN failure degrades gracefully")
    void dependencyFailuresHaveDifferentSemantics() {
        String unavailablePan = randomPan("400000");
        stubCard(unavailablePan, 503, Map.of("message", "unavailable"));

        assertDecline(unavailablePan, 100, 503, "96", "SERVICE_UNAVAILABLE");

        resetWireMock();
        String gracefulPan = randomPan("400003");
        stubCard(gracefulPan, 200, cardBody(gracefulPan, "ACTIVE", 10000, 10000, 10000));
        stubReserve(gracefulPan, 200);
        stub("GET", "/api/bin/400003", 500, Map.of("message", "bin failed"), 0);

        given()
                .spec(contractAuthorization)
                .body(TestData.authorization(gracefulPan, new BigDecimal("100")))
            .when()
                .post("/api/internal/authorize")
            .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }

    @ParameterizedTest(name = "rollback CMS HTTP {0} -> {1}/{2}")
    @MethodSource("rollbackFailureOutcomes")
    @DisplayName("Rollback maps all downstream outcomes to stable HTTP/domain contracts")
    void rollbackFailureContracts(int cmsStatus, int httpStatus, String reason, String code) {
        String pan = randomPan("400000");
        String rrn = TestData.rrn();
        stub("POST", "/api/cards/" + pan + "/rollback", cmsStatus, Map.of(), 0);

        given()
                .spec(contractAuthorization)
                .body(Map.of("rrn", rrn, "pan", pan, "amount", 100))
            .when()
                .post("/api/internal/rollback")
            .then()
                .statusCode(httpStatus)
                .body("rrn", equalTo(rrn))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason));
    }

    private void rollbackApprovedAuthorization(
            String pan, Map<String, Object> request, String rrn) {
        Map<String, Object> rollback = Map.of(
                "rrn", rrn,
                "pan", pan,
                "amount", request.get("amount"));

        given()
                .spec(authorization)
                .body(rollback)
            .when()
                .post("/api/internal/rollback")
            .then()
                .statusCode(200)
                .body("rrn", equalTo(rrn))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));

        given()
                .spec(authorization)
                .body(rollback)
            .when()
                .post("/api/internal/rollback")
            .then()
                .statusCode(409)
                .body("status", equalTo("DECLINED"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    private void assertDecline(
            String pan, int amount, int httpStatus, String code, String reason) {
        given()
                .spec(contractAuthorization)
                .body(TestData.authorization(pan, new BigDecimal(amount)))
            .when()
                .post("/api/internal/authorize")
            .then()
                .statusCode(httpStatus)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason))
                .body("rrn", nullValue())
                .body("authCode", nullValue());
    }

    private void stubCard(String pan, int status, Map<String, Object> body) {
        stub("GET", "/api/cards/" + pan, status, body, 0);
    }

    private void stubReserve(String pan, int status) {
        stub("POST", "/api/cards/" + pan + "/reserve", status, Map.of(), 0);
    }

    private void stubBinLookup() {
        stub("GET", "/api/bin/400000", 200, Map.of("bin", "400000", "issuerId", "ISS001"), 0);
        stub("GET", "/api/bin/400001", 200, Map.of("bin", "400001", "issuerId", "ISS002"), 0);
        stub("GET", "/api/bin/400002", 200, Map.of("bin", "400002", "issuerId", "ISS003"), 0);
    }

    private void stub(String method, String path, int status, Map<String, Object> body, int delay) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("jsonBody", body);
        response.put("headers", Map.of("Content-Type", "application/json"));
        if (delay > 0) {
            response.put("fixedDelayMilliseconds", delay);
        }
        Map<String, Object> mapping = Map.of(
                "request", Map.of("method", method, "urlPath", path),
                "response", response);
        given()
                .spec(wireMock)
                .body(mapping)
            .when()
                .post("/__admin/mappings")
            .then()
                .statusCode(201);
    }

    private static Map<String, Object> cardBody(
            String pan, String status, int balance, int daily, int monthly) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", UUID.randomUUID().toString());
        card.put("pan", pan);
        card.put("bin", pan.substring(0, 6));
        card.put("cardholderName", "CONTRACT TEST");
        card.put("expiryDate", YearMonth.now().plusYears(2).toString());
        card.put("status", status);
        card.put("currencyCode", "643");
        card.put("dailyLimit", daily);
        card.put("monthlyLimit", monthly);
        card.put("availableBalance", balance);
        card.put("issuerId", "ISS001");
        card.put("createdAt", Instant.now().toString());
        return card;
    }

    private static String randomPan(String bin) {
        String tail = UUID.randomUUID().toString().replace("-", "").replaceAll("\\D", "");
        String padded = (tail + "0000000000").substring(0, 10);
        return bin + padded;
    }

    private static Stream<Arguments> invalidAuthorizationFields() {
        return Stream.of(
                Arguments.of("mti", "100"),
                Arguments.of("stan", "12345"),
                Arguments.of("pan", "400000000000000"),
                Arguments.of("processingCode", "00000"),
                Arguments.of("amount", -1),
                Arguments.of("currencyCode", "64"),
                Arguments.of("transmissionDateTime", null),
                Arguments.of("terminalId", "TERM001"),
                Arguments.of("merchantId", "MERCHANT"),
                Arguments.of("mcc", "541"),
                Arguments.of("acquirerId", ""),
                Arguments.of("issuerId", "")
        );
    }

    private static Stream<Arguments> reserveFailureOutcomes() {
        return Stream.of(
                Arguments.of(400, 404, "14", "CARD_NOT_FOUND"),
                Arguments.of(402, 422, "51", "INSUFFICIENT_FUNDS"),
                Arguments.of(404, 404, "14", "CARD_NOT_FOUND"),
                Arguments.of(409, 503, "96", "RESERVATION_FAILED"),
                Arguments.of(500, 503, "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(503, 503, "96", "SERVICE_UNAVAILABLE")
        );
    }

    private static Stream<Arguments> rollbackFailureOutcomes() {
        return Stream.of(
                Arguments.of(400, 404, "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(404, 404, "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(409, 409, "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(422, 503, "ROLLBACK_FAILED", "96"),
                Arguments.of(500, 503, "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(503, 503, "SERVICE_UNAVAILABLE", "96")
        );
    }
}
