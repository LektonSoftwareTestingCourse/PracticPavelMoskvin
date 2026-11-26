package com.processing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class AuthorizationChainIT {

    private static final int HTTP_PORT = 8080;
    private static final String QUEUE = "transaction-log";
    private static final String DLQ = "transaction-log-dlq";
    private static final String EXCHANGE = "smp.transactions";
    private static final String ROUTING_KEY = "transaction.log";
    private static final BigDecimal AMOUNT = new BigDecimal("125");
    private static final BigDecimal FEE = new BigDecimal("7");
    private static final Network NETWORK = Network.newNetwork();
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withCopyFileToContainer(MountableFile.forClasspathResource("chain/databases.sql"),
                    "/docker-entrypoint-initdb.d/10-chain.sql");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management")
            .withNetwork(NETWORK)
            .withNetworkAliases("rabbitmq")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.5.4")
            .withNetwork(NETWORK)
            .withNetworkAliases("bin-lookup", "merchant-acquirer")
            .withExposedPorts(HTTP_PORT)
            .withCopyFileToContainer(MountableFile.forClasspathResource("chain/bin-lookup.json"),
                    "/home/wiremock/mappings/bin-lookup.json")
            .withCopyFileToContainer(MountableFile.forClasspathResource("chain/merchant-fee.json"),
                    "/home/wiremock/mappings/merchant-fee.json")
            .waitingFor(Wait.forHttp("/__admin/mappings"));

    @Container
    static final GenericContainer<?> CARDS = withRabbit(withDatabase(service("card-management"), "cards"))
            .dependsOn(POSTGRES, RABBIT);

    @Container
    static final GenericContainer<?> AUTHORIZATION = withDatabase(service("authorization"), "authorization_db")
            .withEnv("CARD_MGMT_URL", "http://card-management:8080")
            .withEnv("BIN_LOOKUP_URL", "http://bin-lookup:8080")
            .dependsOn(POSTGRES, CARDS, WIREMOCK);

    @Container
    static final GenericContainer<?> LOGGER = withRabbit(withDatabase(service("transaction-logger"), "logger"))
            .withEnv("SPRING_AMQP_DESERIALIZATION_TRUST_ALL", "true")
            .dependsOn(POSTGRES, RABBIT);

    @Container
    static final GenericContainer<?> SWITCH = withRabbit(service("switch"))
            .withEnv("AUTH_URL", "http://authorization:8080")
            .withEnv("MERCHANT_URL", "http://merchant-acquirer:8080")
            .withEnv("LOGGER_URL", "http://transaction-logger:8080")
            .dependsOn(AUTHORIZATION, LOGGER, RABBIT, WIREMOCK);

    @BeforeEach
    void clearState() throws Exception {
        database("cards").execute("""
                TRUNCATE TABLE cards, reservations, reservation_rollbacks, outbox_event RESTART IDENTITY CASCADE
                """);
        database("authorization_db").execute("TRUNCATE TABLE limit_usage RESTART IDENTITY CASCADE");
        database("logger").execute("TRUNCATE TABLE transactions RESTART IDENTITY CASCADE");
        try (var connection = broker().newConnection(); var channel = connection.createChannel()) {
            channel.queuePurge(QUEUE);
            channel.queuePurge(DLQ);
        }
        var resetJournal = requestTo(WIREMOCK, "/__admin/requests").DELETE().build();
        var response = HTTP.send(resetJournal, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("reset WireMock journal: %s", response.body()).isEqualTo(200);
    }

    @Test
    void shouldApproveAndCommitReservationLimitUsageAndTransactionLog() throws Exception {
        String pan = post(CARDS, "/api/cards", Map.of(
                "bin", "400000", "cardholderName", "INTEGRATION TEST", "currencyCode", "643",
                "dailyLimit", 1000, "monthlyLimit", 4000, "initialBalance", 2000), 201).path("pan").asText();
        assertThat(pan).matches("400000\\d{10}");
        String stan = String.format(Locale.ROOT, "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        Instant sentAt = Instant.now();
        var request = new AuthorizationRequest("0100", stan, pan, "000000", AMOUNT, "643", sentAt,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");

        try (var connection = broker().newConnection(); var channel = connection.createChannel()) {
            // A separate queue observes publication without competing with the Logger consumer.
            String probeQueue = channel.queueDeclare().getQueue();
            channel.queueBind(probeQueue, EXCHANGE, ROUTING_KEY);

            JsonNode response = post(SWITCH, "/api/internal/route", request, 200);

            assertThat(response.path("status").asText()).isEqualTo("APPROVED");
            assertThat(response.path("stan").asText()).isEqualTo(stan);
            String rrn = response.path("rrn").asText();
            assertThat(rrn).matches("\\d{12}");
            await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() ->
                    assertThat(channel.basicGet(probeQueue, true)).as("publication to %s", EXCHANGE).isNotNull());

            assertLimitUsage(pan, sentAt);
            assertReservation(pan, rrn);
            assertTransactionLog(pan, rrn, stan);
            assertThat(post(WIREMOCK, "/__admin/requests/count",
                    Map.of("method", "GET", "urlPath", "/api/bin/400000"), 200).path("count").asInt()).isEqualTo(1);
        }
    }

    private void assertLimitUsage(String pan, Instant sentAt) {
        var usage = database("authorization_db").queryForMap("""
                SELECT daily_amount, monthly_amount FROM limit_usage WHERE pan = ? AND usage_date = ?
                """, pan, sentAt.atOffset(ZoneOffset.UTC).toLocalDate());
        assertThat((BigDecimal) usage.get("daily_amount")).isEqualByComparingTo(AMOUNT);
        assertThat((BigDecimal) usage.get("monthly_amount")).isEqualByComparingTo(AMOUNT);
    }

    private void assertReservation(String pan, String rrn) {
        var reservation = database("cards").queryForMap("""
                SELECT reservation_amount, status FROM reservations WHERE pan = ? AND rrn = ?
                """, pan, rrn);
        assertThat((BigDecimal) reservation.get("reservation_amount")).isEqualByComparingTo(AMOUNT);
        assertThat(reservation.get("status")).isEqualTo("RESERVED");
    }

    private void assertTransactionLog(String pan, String rrn, String stan) {
        var logger = database("logger");
        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() -> {
            var rows = logger.queryForList("""
                    SELECT pan, stan, status, amount, acquiring_fee FROM transactions WHERE rrn = ?
                    """, rrn);
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("pan", pan).containsEntry("stan", stan).containsEntry("status", "APPROVED");
                assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(AMOUNT);
                assertThat((BigDecimal) row.get("acquiring_fee")).isEqualByComparingTo(FEE);
            });
        });
    }

    private static JsonNode post(GenericContainer<?> target, String path, Object body, int expectedStatus)
            throws IOException, InterruptedException {
        var request = requestTo(target, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s: %s", path, response.body()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    private static HttpRequest.Builder requestTo(GenericContainer<?> target, String path) {
        URI uri = URI.create("http://%s:%d%s".formatted(target.getHost(), target.getMappedPort(HTTP_PORT), path));
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
    }

    private static JdbcTemplate database(String name) {
        String url = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), name);
        return new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static ConnectionFactory broker() throws Exception {
        var factory = new ConnectionFactory();
        factory.setUri(RABBIT.getAmqpUrl());
        return factory;
    }

    private static GenericContainer<?> service(String name) {
        String jar = name + "-" + System.getProperty("service.version") + ".jar";
        return new GenericContainer<>("eclipse-temurin:21-jre-alpine")
                .withNetwork(NETWORK)
                .withNetworkAliases(name)
                .withExposedPorts(HTTP_PORT)
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", name, "target", jar)), "/app.jar")
                .withEnv("SERVER_PORT", Integer.toString(HTTP_PORT))
                .withCommand("java", "-jar", "/app.jar")
                .waitingFor(Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofSeconds(90)));
    }

    private static GenericContainer<?> withDatabase(GenericContainer<?> service, String database) {
        return service.withEnv("POSTGRES_HOST", "postgres")
                .withEnv("POSTGRES_DB", database)
                .withEnv("POSTGRES_USER", POSTGRES.getUsername())
                .withEnv("POSTGRES_PASSWORD", POSTGRES.getPassword());
    }

    private static GenericContainer<?> withRabbit(GenericContainer<?> service) {
        return service.withEnv("SPRING_RABBITMQ_HOST", "rabbitmq")
                .withEnv("SPRING_RABBITMQ_USERNAME", RABBIT.getAdminUsername())
                .withEnv("SPRING_RABBITMQ_PASSWORD", RABBIT.getAdminPassword());
    }
}
