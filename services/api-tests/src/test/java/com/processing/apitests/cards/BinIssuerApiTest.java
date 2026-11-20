package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

class BinIssuerApiTest extends ApiTestBase {
    @Test
    @DisplayName("Controller-only BIN CRUD exposes 200/201/204/400/404/409 contracts")
    void binIssuerCrudAndErrors() {
        String bin = "9" + TestData.stan().substring(1);
        Map<String, Object> mapping = Map.of("bin", bin, "issuerId", "API999");

        given()
                .spec(cards)
            .when()
                .get("/api/bins")
            .then()
                .statusCode(200)
                .body("$", not(hasSize(0)));

        given()
                .spec(cards)
            .when()
                .get("/api/bins/{bin}", bin)
            .then()
                .statusCode(404)
                .body("error", equalTo("BinNotFoundException"));

        given()
                .spec(cards)
                .body(Map.of("bin", "12345", "issuerId", "lower"))
            .when()
                .post("/api/bins")
            .then()
                .statusCode(400)
                .body("message", notNullValue());

        given()
                .spec(cards)
                .body(mapping)
            .when()
                .post("/api/bins")
            .then()
                .statusCode(201)
                .body("bin", equalTo(bin))
                .body("issuerId", equalTo("API999"));

        given()
                .spec(cards)
                .body(mapping)
            .when()
                .post("/api/bins")
            .then()
                .statusCode(409)
                .body("error", equalTo("BinAlreadyExistException"));

        given()
                .spec(cards)
            .when()
                .delete("/api/bins/{bin}", bin)
            .then()
                .statusCode(204);

        given()
                .spec(cards)
            .when()
                .delete("/api/bins/{bin}", bin)
            .then()
                .statusCode(404)
                .body("error", equalTo("BinNotFoundException"));
    }

    @Test
    @DisplayName("BIN controller exists in code but is intentionally not routed by Gateway")
    void binControllerIsNotGatewayRouted() {
        given()
                .spec(gateway)
            .when()
                .get("/api/bins")
            .then()
                .statusCode(404)
                .body("status", equalTo(404))
                .body("path", equalTo("/api/bins"));
    }
}
