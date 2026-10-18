package com.sathwik.webserver.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientOptionsTest {

    private static Client.Options parse(String... args) {
        return Client.Options.parse(args);
    }

    private static String messageOf(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> parse(args)).getMessage();
    }

    @Test
    void defaultsAreAGetOfHelloOnLocalhostPort8010() {
        Client.Options options = parse();
        assertEquals("127.0.0.1", options.host);
        assertEquals(8010, options.port);
        assertEquals("GET", options.method);
        assertEquals("/hello", options.path);
        assertNull(options.body);
        assertTrue(options.headers.isEmpty());
        assertTrue(options.showHeaders);
        assertEquals(RawHttpClient.DEFAULT_CONNECT_TIMEOUT_MILLIS, options.connectTimeoutMillis);
        assertEquals(RawHttpClient.DEFAULT_READ_TIMEOUT_MILLIS, options.readTimeoutMillis);
        assertEquals(RawHttpClient.DEFAULT_TOTAL_TIMEOUT_MILLIS, options.totalTimeoutMillis);
    }

    @Test
    void overridesHostPortPathAndTimeouts() {
        Client.Options options = parse("--host", "localhost", "--port=9090", "--path", "/health?x=1",
                "--connect-timeout-ms", "100", "--read-timeout-ms", "200", "--total-timeout-ms", "300",
                "--max-response-bytes", "1024", "--no-headers");
        assertEquals("localhost", options.host);
        assertEquals(9090, options.port);
        assertEquals("/health?x=1", options.path);
        assertEquals(100, options.connectTimeoutMillis);
        assertEquals(200, options.readTimeoutMillis);
        assertEquals(300, options.totalTimeoutMillis);
        assertEquals(1024, options.maxResponseBytes);
        assertFalse(options.showHeaders);
    }

    @Test
    void aBodyMakesTheMethodPostUnlessOneIsGiven() {
        assertEquals("POST", parse("--body", "x").method);
        assertEquals("PUT", parse("--body", "x", "--method", "put").method);
        assertEquals("GET", parse("--body", "x", "--method", "GET").method);
        assertEquals("DELETE", parse("--method", "delete").method);
    }

    @Test
    void bodyIsEncodedAsUtf8WithoutAnythingAppended() {
        Client.Options options = parse("--body", "caf\u00e9 \u20ac");
        assertArrayEquals("caf\u00e9 \u20ac".getBytes(StandardCharsets.UTF_8), options.body);
        assertEquals(9, options.body.length); // c a f (1 each) + e-acute (2) + space (1) + euro (3)
    }

    @Test
    void bodyGetsATextContentTypeUnlessOverridden() {
        assertEquals("text/plain; charset=utf-8", parse("--body", "x").headers.get("Content-Type"));
        assertEquals("application/json",
                parse("--body", "{}", "--content-type", "application/json").headers.get("Content-Type"));
        assertEquals("application/json",
                parse("--body", "{}", "--header", "content-type: application/json").headers.get("content-type"));
        assertFalse(parse().headers.containsKey("Content-Type"));
    }

    @Test
    void bodyFileIsReadAsExactBytes() throws IOException {
        Path file = Files.createTempFile("client-body", ".txt");
        try {
            byte[] bytes = "line one\nline two\u00e9\n".getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            Client.Options options = parse("--body-file", file.toString());
            assertArrayEquals(bytes, options.body);
            assertEquals("POST", options.method);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingBodyFileIsAUsageError() {
        assertTrue(messageOf("--body-file", "definitely-not-here.txt").startsWith("Cannot read body file"));
    }

    @Test
    void bodyAndBodyFileAreMutuallyExclusive() {
        assertEquals("Use either --body or --body-file, not both",
                messageOf("--body", "x", "--body-file", "y"));
    }

    @Test
    void parsesRepeatedHeaders() {
        Client.Options options = parse("--header", "X-One: 1", "--header", "X-Two:2", "--header", "x-one: replaced");
        assertEquals(2, options.headers.size());
        assertEquals("replaced", options.headers.get("x-one"));
        assertEquals("2", options.headers.get("X-Two"));
    }

    @Test
    void rejectsBadHeaders() {
        assertTrue(messageOf("--header", "no colon here").contains("Name: value"));
        assertTrue(messageOf("--header", ": value").contains("Name: value"));
        assertTrue(messageOf("--header", "Bad Name: v").contains("Invalid header name"));
        assertTrue(messageOf("--header", "Host: evil").contains("cannot be overridden"));
        assertTrue(messageOf("--header", "content-length: 5").contains("cannot be overridden"));
        assertTrue(messageOf("--header", "Connection: keep-alive").contains("cannot be overridden"));
        assertTrue(messageOf("--header", "X-A: caf\u00e9").contains("printable ASCII"));
    }

    @Test
    void rejectsInvalidPaths() {
        assertTrue(messageOf("--path", "hello").contains("must start with '/'"));
        assertTrue(messageOf("--path", "/a b").contains("percent-encoded"));
        assertTrue(messageOf("--path", "/caf\u00e9").contains("percent-encoded"));
        assertTrue(messageOf("--path", "/a\r\nX: y").contains("percent-encoded"));
    }

    @Test
    void rejectsInvalidMethodHostPortAndNumbers() {
        assertTrue(messageOf("--method", "BAD METHOD").contains("Invalid HTTP method"));
        assertTrue(messageOf("--host", " ").contains("Host"));
        assertTrue(messageOf("--host", "a b").contains("Host"));
        assertTrue(messageOf("--port", "0").contains("--port"));
        assertTrue(messageOf("--port", "65536").contains("--port"));
        assertTrue(messageOf("--port", "abc").contains("--port"));
        assertTrue(messageOf("--read-timeout-ms", "0").contains("--read-timeout-ms"));
        assertTrue(messageOf("--bogus", "1").contains("Unknown option"));
    }
}
