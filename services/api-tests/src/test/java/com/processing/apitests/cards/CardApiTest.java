package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.TestData;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

class CardApiTest extends ApiTestBase {
    private static final BigDecimal BALANCE = new BigDecimal("1000");

    @Test
    @DisplayName("TC-CMS-01: health exposes the card count")
    void healthContainsCardCount() {
        given()
                .spec(cards)
            .when()
                .get("/health")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("ok"))
                .body("cardsInDatabase", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("TC-CMS-02/13: a created card is returned by PAN")
    void createAndReadCard() {
        Map<String, Object> request = TestData.card();

        Response created = createCard(request);

        String pan = created.jsonPath().getString("pan");
        given()
                .spec(gateway)
            .when()
                .get("/api/cards/{pan}", pan)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .header("X-Cache", notNullValue())
                .body("pan", equalTo(pan))
                .body("pan", matchesPattern("400000\\d{10}"))
                .body("cardholderName", equalTo(request.get("cardholderName")))
                .body("status", equalTo("ACTIVE"));
    }

    @ParameterizedTest(name = "invalid create field {0}")
    @MethodSource("invalidCreateRequests")
    @DisplayName("TC-CMS-03...12: create validates one invalid class at a time")
    void createRejectsInvalidFields(String field, Object value, String messagePart) {
        Map<String, Object> request = TestData.copyWith(TestData.card(), field, value);
        Matcher<String> messageMatcher = "cardholderName".equals(field) && "".equals(value)
                ? anyOf(containsString("blank"), containsString("Cardholder name"))
                : containsString(messagePart);

        Response response = given()
                .spec(gateway)
                .body(request)
            .when()
                .post("/api/cards")
            .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("message", messageMatcher)
                .extract().response();

        assertErrorBody(response, "card-management");
    }

    @Test
    @DisplayName("Known gap: negative initialBalance is accepted because the DTO has no constraint")
    void negativeInitialBalanceDocumentsObservedContractGap() {
        Map<String, Object> request = TestData.copyWith(
                TestData.card(), "initialBalance", new BigDecimal("-1"));

        given()
                .spec(gateway)
                .body(request)
            .when()
                .post("/api/cards")
            .then()
                .statusCode(201)
                .body("availableBalance", equalTo(-1));
    }

    @Test
    @DisplayName("TC-CMS-11: zero limits and zero balance are valid ON-boundaries")
    void zeroMoneyBoundariesAreAccepted() {
        Map<String, Object> request = TestData.card();
        request.put("dailyLimit", BigDecimal.ZERO);
        request.put("monthlyLimit", BigDecimal.ZERO);
        request.put("initialBalance", BigDecimal.ZERO);

        given()
                .spec(gateway)
                .body(request)
            .when()
                .post("/api/cards")
            .then()
                .statusCode(201)
                .body("dailyLimit", equalTo(0))
                .body("monthlyLimit", equalTo(0))
                .body("availableBalance", equalTo(0));
    }

    @Test
    @DisplayName("TC-CMS-14/15: missing and malformed PANs have distinct error bodies")
    void getRejectsMissingAndMalformedPan() {
        given()
                .spec(gateway)
            .when()
                .get("/api/cards/{pan}", "4000000000000000")
            .then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"))
                .body("serviceName", equalTo("card-management"));

        given()
                .spec(gateway)
            .when()
                .get("/api/cards/{pan}", "400000000000000")
            .then()
                .statusCode(400)
                .body("error", equalTo("ConstraintViolationException"))
                .body("message", containsString("16"));
    }

    @Test
    @DisplayName("TC-CMS-16...20: list supports pagination, filters and an empty selection")
    void listSupportsAllFiltersAndPagination() {
        Response created = createCard();
        String pan = created.jsonPath().getString("pan");
        String issuerId = created.jsonPath().getString("issuerId");
        String start = Instant.now().minusSeconds(60).toString();
        String end = Instant.now().plusSeconds(60).toString();

        given()
                .spec(gateway)
                .queryParam("limit", 1)
                .queryParam("offset", 0)
                .queryParam("status", "ACTIVE")
                .queryParam("bin", "400000")
                .queryParam("issuerId", issuerId)
                .queryParam("startDate", start)
                .queryParam("endDate", end)
            .when()
                .get("/api/cards")
            .then()
                .statusCode(200)
                .body("cards", hasSize(1))
                .body("cards.status", everyItem(equalTo("ACTIVE")))
                .body("cards.bin", everyItem(equalTo("400000")))
                .body("cards.issuerId", everyItem(equalTo(issuerId)));

        given()
                .spec(gateway)
                .queryParam("issuerId", "NORESULT")
            .when()
                .get("/api/cards")
            .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("cards", empty());

        given()
                .spec(gateway)
            .when()
                .get("/api/cards/{pan}", pan)
            .then()
                .statusCode(200);
    }

    @ParameterizedTest(name = "list query {0}={1} -> {2}")
    @MethodSource("invalidListParameters")
    @DisplayName("Card list enforces every query boundary")
    void listRejectsInvalidParameters(String name, Object value, int status) {
        given()
                .spec(gateway)
                .queryParam(name, value)
            .when()
                .get("/api/cards")
            .then()
                .statusCode(status)
                .body("message", notNullValue());
    }

    @Test
    @DisplayName("Known gap: an inverted card date range is accepted as an empty query")
    void invertedDateRangeDocumentsObservedContractGap() {
        String start = Instant.now().plusSeconds(60).toString();
        String end = Instant.now().minusSeconds(60).toString();

        given()
                .spec(gateway)
                .queryParam("startDate", start)
                .queryParam("endDate", end)
            .when()
                .get("/api/cards")
            .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("cards", empty());
    }

    @Test
    @DisplayName("TC-CMS-25...27: generator boundaries are observable in status and body")
    void generatorBoundaries() {
        given()
                .spec(gateway)
                .body(Map.of("count", 1, "bins", new String[]{"400000"}))
            .when()
                .post("/api/cards/generate")
            .then()
                .statusCode(201)
                .body("generated", equalTo(1))
                .body("cards", hasSize(1));

        given()
                .spec(gateway)
                .body(Map.of("count", 0, "bins", new String[]{"400000"}))
            .when()
                .post("/api/cards/generate")
            .then()
                .statusCode(400)
                .body("message", notNullValue());

        given()
                .spec(gateway)
                .body(Map.of("count", 10001, "bins", new String[]{"400000"}))
            .when()
                .post("/api/cards/generate")
            .then()
                .statusCode(400)
                .body("error", equalTo("CardGenerationLimitException"));
    }

    @Test
    @DisplayName("TC-P3-10/TC-CMS-28...32: reserve and rollback are stateful and idempotent")
    void reserveAndRollbackStateMachine() {
        String pan = createCard(BALANCE, BALANCE, BALANCE.multiply(BigDecimal.TEN));
        String rrn = TestData.rrn();
        Map<String, Object> reserve = Map.of("amount", 100, "rrn", rrn);
        Map<String, Object> rollback = Map.of("amount", 100, "rrn", rrn, "pan", pan);

        given()
                .spec(gateway)
                .body(reserve)
            .when()
                .post("/api/cards/{pan}/reserve", pan)
            .then()
                .statusCode(200)
                .body("availableBalance", equalTo(900));

        given()
                .spec(gateway)
                .body(reserve)
            .when()
                .post("/api/cards/{pan}/reserve", pan)
            .then()
                .statusCode(409)
                .body("error", equalTo("ReservationAlreadyExistsException"));

        given()
                .spec(gateway)
                .body(rollback)
            .when()
                .post("/api/cards/{pan}/rollback", pan)
            .then()
                .statusCode(200)
                .body("availableBalance", equalTo(1000));

        given()
                .spec(gateway)
                .body(rollback)
            .when()
                .post("/api/cards/{pan}/rollback", pan)
            .then()
                .statusCode(409)
                .body("error", equalTo("RollbackAlreadySatisfiedException"));

        Map<String, Object> unknown = Map.of(
                "amount", 100, "rrn", TestData.rrn(), "pan", pan);
        given()
                .spec(gateway)
                .body(unknown)
            .when()
                .post("/api/cards/{pan}/rollback", pan)
            .then()
                .statusCode(404)
                .body("error", equalTo("ReservationNotFoundException"));
    }

    @Test
    @DisplayName("Reserve checks ON/OFF balance and request validation boundaries")
    void reserveBoundaries() {
        String exactPan = createCard(BALANCE, BALANCE, BALANCE.multiply(BigDecimal.TEN));

        given()
                .spec(gateway)
                .body(Map.of("amount", 1000, "rrn", TestData.rrn()))
            .when()
                .post("/api/cards/{pan}/reserve", exactPan)
            .then()
                .statusCode(200)
                .body("availableBalance", equalTo(0));

        String offPan = createCard(BALANCE, BALANCE, BALANCE.multiply(BigDecimal.TEN));
        given()
                .spec(gateway)
                .body(Map.of("amount", 1001, "rrn", TestData.rrn()))
            .when()
                .post("/api/cards/{pan}/reserve", offPan)
            .then()
                .statusCode(402)
                .body("error", equalTo("InsufficientFundsException"));

        given()
                .spec(gateway)
                .body(Map.of("amount", 0, "rrn", TestData.rrn()))
            .when()
                .post("/api/cards/{pan}/reserve", offPan)
            .then()
                .statusCode(400)
                .body("message", notNullValue());

        given()
                .spec(gateway)
                .body(Map.of("amount", 1, "rrn", "12345"))
            .when()
                .post("/api/cards/{pan}/reserve", offPan)
            .then()
                .statusCode(400)
                .body("message", containsString("12"));
    }

    @Test
    @DisplayName("PATCH validates monetary boundaries and missing cards")
    void patchValidationAndNotFoundContracts() {
        String pan = createCard().jsonPath().getString("pan");

        given()
                .spec(gateway)
                .body(Map.of("dailyLimit", -1))
            .when()
                .patch("/api/cards/{pan}", pan)
            .then()
                .statusCode(400)
                .body("message", containsString("negative"));

        given()
                .spec(gateway)
                .body(Map.of("status", "BLOCKED"))
            .when()
                .patch("/api/cards/{pan}", "4000000000000000")
            .then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    @DisplayName("TC-P3-11: status transitions control reserve and soft delete visibility")
    void statusTransitionsAndSoftDelete() {
        String pan = createCard(BALANCE, BALANCE, BALANCE.multiply(BigDecimal.TEN));

        given().spec(gateway).body(Map.of("status", "BLOCKED"))
            .when().patch("/api/cards/{pan}", pan)
            .then().statusCode(200).body("status", equalTo("BLOCKED"));

        given().spec(gateway).body(Map.of("amount", 1, "rrn", TestData.rrn()))
            .when().post("/api/cards/{pan}/reserve", pan)
            .then().statusCode(409).body("message", containsString("BLOCKED"));

        given().spec(gateway).body(Map.of("status", "ACTIVE"))
            .when().patch("/api/cards/{pan}", pan)
            .then().statusCode(200).body("status", equalTo("ACTIVE"));

        given().spec(gateway)
            .when().delete("/api/cards/{pan}", pan)
            .then().statusCode(204).body(equalTo(""));

        given().spec(gateway)
            .when().get("/api/cards/{pan}", pan)
            .then().statusCode(404);

        given().spec(gateway)
            .when().get("/api/cards")
            .then().statusCode(200).body("cards.pan", not(hasItem(pan)));

        given().spec(gateway)
            .when().delete("/api/cards/{pan}", pan)
            .then().statusCode(404);

        String secondPan = createCard().jsonPath().getString("pan");
        given().spec(gateway).body(Map.of("status", "DELETED"))
            .when().patch("/api/cards/{pan}", secondPan)
            .then().statusCode(500).body("message", notNullValue());
    }

    @Test
    @DisplayName("Implementation-only bulk-update endpoint updates only selected cards")
    void bulkUpdateEndpointIsCovered() {
        String pan = createCard().jsonPath().getString("pan");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("bins", null);
        request.put("pans", new String[]{pan});
        request.put("status", "BLOCKED");

        given()
                .spec(gateway)
                .body(request)
            .when()
                .post("/api/cards/bulk-update")
            .then()
                .statusCode(200)
                .body("updated", equalTo(1));

        given()
                .spec(gateway)
            .when()
                .get("/api/cards/{pan}", pan)
            .then()
                .statusCode(200)
                .body("status", equalTo("BLOCKED"));
    }

    private static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
                Arguments.of("bin", "40000", "6"),
                Arguments.of("bin", "4000000", "6"),
                Arguments.of("bin", "40000A", "digits"),
                Arguments.of("cardholderName", "", "blank"),
                Arguments.of("cardholderName", "Ivan Ivanov", "uppercase"),
                Arguments.of("currencyCode", "64", "3"),
                Arguments.of("currencyCode", "64A", "digits"),
                Arguments.of("dailyLimit", -1, "negative"),
                Arguments.of("monthlyLimit", -1, "negative")
        );
    }

    private static Stream<Arguments> invalidListParameters() {
        return Stream.of(
                Arguments.of("limit", 0, 400),
                Arguments.of("limit", 10001, 413),
                Arguments.of("offset", -1, 400),
                Arguments.of("status", "UNKNOWN", 500),
                Arguments.of("bin", "40000", 400),
                Arguments.of("issuerId", "bad-value", 400)
        );
    }
}
