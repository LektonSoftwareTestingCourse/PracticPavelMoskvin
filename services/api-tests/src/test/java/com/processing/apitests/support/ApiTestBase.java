package com.processing.apitests.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

import java.math.BigDecimal;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

public abstract class ApiTestBase {
    protected static RequestSpecification gateway;
    protected static RequestSpecification cards;
    protected static RequestSpecification authorization;
    protected static RequestSpecification switchService;
    protected static RequestSpecification logger;
    protected static RequestSpecification terminal;
    protected static RequestSpecification merchant;
    protected static RequestSpecification binLookup;
    protected static RequestSpecification notifications;
    protected static RequestSpecification contractGateway;
    protected static RequestSpecification contractAuthorization;
    protected static RequestSpecification wireMock;

    @BeforeAll
    static void createSpecifications() {
        gateway = spec(ApiConfig.GATEWAY);
        cards = spec(ApiConfig.CARDS);
        authorization = spec(ApiConfig.AUTHORIZATION);
        switchService = spec(ApiConfig.SWITCH);
        logger = spec(ApiConfig.LOGGER);
        terminal = spec(ApiConfig.TERMINAL);
        merchant = spec(ApiConfig.MERCHANT);
        binLookup = spec(ApiConfig.BIN_LOOKUP);
        notifications = spec(ApiConfig.NOTIFICATIONS);
        contractGateway = spec(ApiConfig.CONTRACT_GATEWAY);
        contractAuthorization = spec(ApiConfig.CONTRACT_AUTHORIZATION);
        wireMock = spec(ApiConfig.WIREMOCK);
    }

    protected static Response createCard() {
        return createCard(TestData.card());
    }

    protected static Response createCard(Map<String, Object> body) {
        return given()
                .spec(gateway)
                .body(body)
            .when()
                .post("/api/cards")
            .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("pan", matchesPattern("\\d{16}"))
                .body("status", equalTo("ACTIVE"))
                .body("availableBalance", notNullValue())
                .extract().response();
    }

    protected static String createCard(BigDecimal balance, BigDecimal daily, BigDecimal monthly) {
        Map<String, Object> body = TestData.card();
        body.put("initialBalance", balance);
        body.put("dailyLimit", daily);
        body.put("monthlyLimit", monthly);
        return createCard(body).jsonPath().getString("pan");
    }

    protected static RequestSpecification gatewayRequest() {
        return given()
                .spec(gateway)
                .header("X-Forwarded-For", TestData.clientIp());
    }

    protected static void assertErrorBody(Response response, String expectedService) {
        response.then()
                .contentType(ContentType.JSON)
                .body("error", notNullValue())
                .body("message", notNullValue())
                .body("timestamp", notNullValue())
                .body("serviceName", equalTo(expectedService));
    }

    private static RequestSpecification spec(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }
}
