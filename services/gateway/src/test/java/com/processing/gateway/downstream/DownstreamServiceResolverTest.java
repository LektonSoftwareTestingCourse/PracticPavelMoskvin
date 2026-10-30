package com.processing.gateway.downstream;

import com.processing.gateway.properties.GatewayRouteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Определение downstream-сервиса по пути запроса из метаданных маршрутов Gateway.
 */
class DownstreamServiceResolverTest {

    private DownstreamServiceResolver resolver;

    @BeforeEach
    void setUp() {
        GatewayRouteProperties properties = new GatewayRouteProperties();
        properties.setRoutes(List.of(
                route("switch", List.of("Path=/api/transactions", "Method=POST")),
                route("cardManagement", List.of("Path=/api/cards, /api/cards/**")),
                route("logger", List.of("Path=/api/transactions/search,/api/transactions/{id}")),
                route(null, List.of("Path=/docs/**")),
                route(" ", List.of("Path=/blank/**")),
                route("noPath", List.of("Method=GET"))));
        resolver = new DownstreamServiceResolver(properties);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "/api/transactions,               switch",
        "/api/cards,                      cardManagement",
        "/api/cards/4000001234567890,     cardManagement",
        "/api/transactions/search,        logger",
        "/api/transactions/123,           logger"
    })
    void shouldResolveServiceByFirstMatchingPathPattern(String path, String expectedService) {
        // Act
        Optional<String> service = resolver.resolve(path);

        // Assert
        assertThat(service).contains(expectedService);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/health", "/docs/index.html", "/blank/x", "/api"})
    void shouldResolveNothingWhenRouteHasNoServiceNameOrPathDoesNotMatch(String path) {
        // Act
        Optional<String> service = resolver.resolve(path);

        // Assert
        assertThat(service).isEmpty();
    }

    private static GatewayRouteProperties.RouteDefinition route(String serviceName, List<String> predicates) {
        GatewayRouteProperties.RouteDefinition route = new GatewayRouteProperties.RouteDefinition();
        route.setId(serviceName + "-route");
        route.setPredicates(predicates);
        route.setMetadata(serviceName == null ? Map.of() : Map.of("serviceName", serviceName));
        return route;
    }
}
