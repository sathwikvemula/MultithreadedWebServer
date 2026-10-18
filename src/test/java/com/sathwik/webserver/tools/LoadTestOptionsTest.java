package com.sathwik.webserver.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LoadTestOptionsTest {

    private static LoadTest.Options parse(String... args) {
        return LoadTest.Options.parse(args);
    }

    private static String messageOf(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> parse(args)).getMessage();
    }

    @Test
    void defaultsAre20ClientsDoingAGetOfHelloOnLocalhost8010() {
        LoadTest.Options options = parse();
        assertEquals("127.0.0.1", options.host);
        assertEquals(8010, options.port);
        assertEquals("GET", options.method);
        assertEquals("/hello", options.path);
        assertNull(options.body);
        assertEquals(20, options.clients);
        assertEquals(1, options.requestsPerClient);
        assertEquals(20, options.plannedRequests());
        assertTrue(options.headers.isEmpty());
    }

    @Test
    void acceptsHostPortPathAndClientCount() {
        LoadTest.Options options = parse("--host", "localhost", "--port=9090", "--path", "/health", "--clients", "5");
        assertEquals("localhost", options.host);
        assertEquals(9090, options.port);
        assertEquals("/health", options.path);
        assertEquals(5, options.clients);
    }

    @Test
    void plannedRequestsIsClientsTimesRequestsPerClient() {
        LoadTest.Options options = parse("--clients", "7", "--requests-per-client", "11");
        assertEquals(77, options.plannedRequests());
    }

    @Test
    void aBodyMakesItAPostWithATextContentType() {
        LoadTest.Options options = parse("--body", "caf\u00e9", "--path", "/users");
        assertEquals("POST", options.method);
        assertArrayEquals("caf\u00e9".getBytes(StandardCharsets.UTF_8), options.body);
        assertEquals("text/plain; charset=utf-8", options.headers.get("Content-Type"));
        assertEquals("application/json", parse("--body", "{}", "--content-type", "application/json").headers.get("Content-Type"));
        assertEquals("PUT", parse("--body", "x", "--method", "put").method);
    }

    @Test
    void acceptsTimeouts() {
        LoadTest.Options options = parse("--connect-timeout-ms", "111", "--read-timeout-ms", "222");
        assertEquals(111, options.connectTimeoutMillis);
        assertEquals(222, options.readTimeoutMillis);
    }

    @Test
    void thePoolIsBoundedSoTooManyClientsAreRejected() {
        assertEquals(1_000, LoadTest.MAX_CLIENTS);
        assertEquals(1_000, parse("--clients", "1000").clients);
        assertTrue(messageOf("--clients", "1001").contains("--clients"));
        assertTrue(messageOf("--clients", "0").contains("--clients"));
        assertTrue(messageOf("--clients", "-3").contains("--clients"));
        assertTrue(messageOf("--clients", "many").contains("--clients"));
    }

    @Test
    void totalWorkIsBounded() {
        assertTrue(messageOf("--requests-per-client", "0").contains("--requests-per-client"));
        assertTrue(messageOf("--requests-per-client", "10001").contains("--requests-per-client"));
        assertTrue(messageOf("--clients", "1000", "--requests-per-client", "10000").contains("must not exceed"));
        assertEquals(1_000_000, parse("--clients", "100", "--requests-per-client", "10000").plannedRequests());
    }

    @Test
    void rejectsInvalidInput() {
        assertTrue(messageOf("--port", "0").contains("--port"));
        assertTrue(messageOf("--port", "70000").contains("--port"));
        assertTrue(messageOf("--path", "hello").contains("must start with '/'"));
        assertTrue(messageOf("--path", "/a b").contains("percent-encoded"));
        assertTrue(messageOf("--host", "").contains("Host"));
        assertTrue(messageOf("--method", "NOT VALID").contains("Invalid HTTP method"));
        assertTrue(messageOf("--content-type", "text/plain\r\nX: y").contains("printable ASCII"));
        assertTrue(messageOf("--bogus", "1").contains("Unknown option"));
        assertTrue(messageOf("--clients").contains("Missing value"));
        assertTrue(messageOf("20").contains("Unexpected argument"));
        assertTrue(messageOf("--connect-timeout-ms", "0").contains("--connect-timeout-ms"));
    }
}
