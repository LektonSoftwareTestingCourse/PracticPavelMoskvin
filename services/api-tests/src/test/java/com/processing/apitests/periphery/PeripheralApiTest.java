package com.processing.apitests.periphery;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.TestData;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

class PeripheralApiTest extends ApiTestBase {
    @Test
    @DisplayName("Gateway health aggregates downstream dependency state")
    void gatewayHealth() {
        given()
                .spec(gateway)
            .when()
                .get("/health")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", equalTo("ok"))
                .body("services", notNullValue());
    }

    @Test
    @DisplayName("Direct health endpoints expose each service identity and dependency data")
    void directServiceHealthContracts() {
        given().spec(switchService)
            .when().get("/health")
            .then().statusCode(200)
                .body("service", equalTo("switch"))
                .body("dependencies.authorization", equalTo("ok"));

        given().spec(logger)
            .when().get("/health")
            .then().statusCode(200)
                .body("service", equalTo("transaction-logger"))
                .body("transactionsStored", greaterThanOrEqualTo(0));

        given().spec(terminal)
            .when().get("/health")
            .then().statusCode(200)
                .body("service", equalTo("terminal-simulator"))
                .body("dependencies", notNullValue());

        given().spec(merchant)
            .when().get("/health")
            .then().statusCode(200)
                .body("service", equalTo("merchant-acquirer-simulator"))
                .body("merchantsLoaded", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("BIN lookup distinguishes success, unknown BIN and forced failure")
    void binLookupOutcomes() {
        given()
                .spec(binLookup)
            .when()
                .get("/api/bin/400000")
            .then()
                .statusCode(200)
                .body("bin", equalTo("400000"))
                .body("issuerId", equalTo("ISS001"))
                .body("issuerName", notNullValue());

        given()
                .spec(binLookup)
            .when()
                .get("/api/bin/999999")
            .then()
                .statusCode(404)
                .body(emptyString());

        given()
                .spec(binLookup)
                .queryParam("fail", true)
            .when()
                .get("/api/bin/400000")
            .then()
                .statusCode(500)
                .body(emptyString());
    }

    @Test
    @DisplayName("Terminal simulator run is routed by Gateway and returns complete counters")
    void terminalRun() {
        createCard();

        given()
                .spec(gateway)
                .body(Map.of("count", 1, "scenario", "normal", "tps", 1))
            .when()
                .post("/api/simulator/terminal/run")
            .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(1))
                .body("approved", greaterThanOrEqualTo(0))
                .body("declined", greaterThanOrEqualTo(0))
                .body("transactions", hasSize(1));
    }

    @Test
    @DisplayName("Terminal implementation exposes start-continuous/stop and validates requests")
    void terminalContinuousEndpoints() {
        given()
                .spec(gateway)
                .body(Map.of("tps", 0, "transactionType", "NORMAL"))
            .when()
                .post("/api/simulator/terminal/start-continuous")
            .then()
                .statusCode(400)
                .body("serviceName", equalTo("terminal-simulator"));

        createCard();
        given()
                .spec(gateway)
                .body(Map.of("tps", 1, "transactionType", "NORMAL"))
            .when()
                .post("/api/simulator/terminal/start-continuous")
            .then()
                .statusCode(200)
                .body(emptyString());

        given()
                .spec(gateway)
            .when()
                .post("/api/simulator/terminal/stop")
            .then()
                .statusCode(200)
                .body(emptyString());
    }

    @Test
    @DisplayName("Merchant run is routed; plural merchants path exposes the route mismatch")
    void merchantEndpoints() {
        createCard();

        given()
                .spec(gateway)
                .body(Map.of("count", 0, "scenario", "grocery", "tps", 1))
            .when()
                .post("/api/simulator/merchant/run")
            .then()
                .statusCode(400)
                .body("error", equalTo("Invalid request"))
                .body("message", containsString("count"));

        given()
                .spec(gateway)
                .body(Map.of("count", 1, "scenario", "grocery", "tps", 1))
            .when()
                .post("/api/simulator/merchant/run")
            .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(1))
                .body("transactions", hasSize(1));

        given()
                .spec(merchant)
            .when()
                .get("/api/simulator/merchants")
            .then()
                .statusCode(200)
                .body("$", notNullValue());

        given()
                .spec(gateway)
            .when()
                .get("/api/simulator/merchants")
            .then()
                .statusCode(404)
                .body("status", equalTo(404))
                .body("path", equalTo("/api/simulator/merchants"));
    }

    @Test
    @DisplayName("OpenAPI terminal start/status paths are absent in the implementation")
    void declaredTerminalEndpointsExposeContractMismatch() {
        given()
                .spec(gateway)
                .body(Map.of("tps", 1))
            .when()
                .post("/api/simulator/terminal/start")
            .then()
                .statusCode(500)
                .body("serviceName", equalTo("terminal-simulator"));

        given()
                .spec(gateway)
            .when()
                .get("/api/simulator/terminal/status")
            .then()
                .statusCode(500)
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    @DisplayName("Implementation-only merchant fee endpoint has validation and not-found contracts")
    void merchantFeeEndpoint() {
        Map<String, Object> missing = Map.of(
                "transmissionDateTime", Instant.now().toString(),
                "pan", "4000000000000000",
                "stan", TestData.stan(),
                "amount", 1000,
                "terminalId", "TERM0001");

        given()
                .spec(gateway)
                .body(missing)
            .when()
                .post("/api/simulator/merchant/fee")
            .then()
                .statusCode(404)
                .body("error", equalTo("Resource not found"))
                .body("serviceName", equalTo("Merchant acquirer simulator"));

        given()
                .spec(gateway)
                .body(Map.of("pan", "4000000000000000"))
            .when()
                .post("/api/simulator/merchant/fee")
            .then()
                .statusCode(400)
                .body("error", equalTo("Invalid request"));
    }

    @Test
    @DisplayName("Card creation eventually produces a notification visible only through HTTP")
    void cardCreationNotification() {
        Response before = given()
                .spec(notifications)
                .queryParam("page", 0)
                .queryParam("size", 1000)
            .when()
                .get("/api/notifications")
            .then()
                .statusCode(200)
                .extract().response();
        int creationEvents = before.jsonPath()
                .getList("content.findAll { it.eventType == 'CardServiceCreationEvent' }")
                .size();

        createCard();

        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given()
                        .spec(notifications)
                        .queryParam("page", 0)
                        .queryParam("size", 1000)
                    .when()
                        .get("/api/notifications")
                    .then()
                        .statusCode(200)
                        .body("content.findAll { it.eventType == 'CardServiceCreationEvent' }",
                                hasSize(greaterThanOrEqualTo(creationEvents + 1)))
                        .body("content.find { it.eventType == 'CardServiceCreationEvent' }.routingKey",
                                equalTo("card.CardServiceCreationEvent"))
                        .body("content.find { it.eventType == 'CardServiceCreationEvent' }.payload",
                                containsString("\"amount\""))
                        .body("content.find { it.eventType == 'CardServiceCreationEvent' }.payload",
                                containsString("1")));
    }
}
