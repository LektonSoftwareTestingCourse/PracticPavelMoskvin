package com.processing.apitests.support;

/** Runtime endpoints used by the black-box API suite. */
public final class ApiConfig {
    public static final String GATEWAY = env("API_GATEWAY_URL", "http://localhost:8080");
    public static final String CARDS = env("API_CARD_URL", "http://localhost:8081");
    public static final String SWITCH = env("API_SWITCH_URL", "http://localhost:8082");
    public static final String AUTHORIZATION = env("API_AUTH_URL", "http://localhost:8083");
    public static final String MERCHANT = env("API_MERCHANT_URL", "http://localhost:8084");
    public static final String TERMINAL = env("API_TERMINAL_URL", "http://localhost:8085");
    public static final String LOGGER = env("API_LOGGER_URL", "http://localhost:8088");
    public static final String BIN_LOOKUP = env("API_BIN_URL", "http://localhost:8096");
    public static final String NOTIFICATIONS = env("API_NOTIFICATION_URL", "http://localhost:8097");
    public static final String CONTRACT_GATEWAY = env(
            "API_CONTRACT_GATEWAY_URL", "http://localhost:18080");
    public static final String CONTRACT_AUTHORIZATION = env(
            "API_CONTRACT_AUTH_URL", "http://localhost:18083");
    public static final String WIREMOCK = env("API_WIREMOCK_URL", "http://localhost:18089");

    private ApiConfig() {
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }
}
