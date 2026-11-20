package com.processing.apitests.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Request builders keep invalid-field mutations out of the assertions. */
public final class TestData {
    private TestData() {
    }

    public static Map<String, Object> card() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bin", "400000");
        body.put("cardholderName", "API TEST " + randomLetters(8));
        body.put("currencyCode", "643");
        body.put("dailyLimit", new BigDecimal("15000000"));
        body.put("monthlyLimit", new BigDecimal("300000000"));
        body.put("initialBalance", new BigDecimal("100000000"));
        return body;
    }

    public static Map<String, Object> authorization(String pan, BigDecimal amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mti", "0100");
        body.put("stan", stan());
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", amount);
        body.put("currencyCode", "643");
        body.put("transmissionDateTime", timestamp());
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        return body;
    }

    public static Map<String, Object> loggedTransaction(String pan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", UUID.randomUUID().toString());
        body.put("mti", "0100");
        body.put("stan", stan());
        body.put("rrn", rrn());
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", 1000);
        body.put("currencyCode", "643");
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        body.put("acquiringFee", 10);
        body.put("status", "APPROVED");
        body.put("declineReason", null);
        body.put("authCode", "ABC123");
        body.put("processingTimeMs", 10);
        body.put("transmissionDateTime", timestamp());
        body.put("createdAt", timestamp());
        return body;
    }

    public static String stan() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    public static String rrn() {
        long value = ThreadLocalRandom.current().nextLong(1_000_000_000_000L);
        return String.format("%012d", value);
    }

    private static String timestamp() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
    }

    public static String clientIp() {
        int third = ThreadLocalRandom.current().nextInt(1, 255);
        int fourth = ThreadLocalRandom.current().nextInt(1, 255);
        return "198.51." + third + "." + fourth;
    }

    public static Map<String, Object> copyWith(
            Map<String, Object> source, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put(field, value);
        return copy;
    }

    private static String randomLetters(int length) {
        return ThreadLocalRandom.current()
                .ints('A', 'Z' + 1)
                .limit(length)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
