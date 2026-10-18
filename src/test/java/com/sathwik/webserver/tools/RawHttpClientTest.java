package com.sathwik.webserver.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawHttpClientTest {

    private static final String OK_HELLO = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello";

    private static RawHttpClient client() {
        return new RawHttpClient(1_000, 2_000, 5_000, 1024 * 1024);
    }

    private static RawHttpClient.Response get(FakeServer server) throws IOException {
        return client().send("127.0.0.1", server.port(), "GET", "/x", Map.of(), null);
    }

    private static ProtocolException protocolError(String reply) throws IOException {
        try (FakeServer server = FakeServer.replying(reply)) {
            return assertThrows(ProtocolException.class, () -> get(server));
        }
    }

    // ---- status line parsing ---------------------------------------

    @Test
    void parsesStatusLines() throws Exception {
        RawHttpClient.StatusLine ok = RawHttpClient.parseStatusLine("HTTP/1.1 200 OK");
        assertEquals("HTTP/1.1", ok.version());
        assertEquals(200, ok.statusCode());
        assertEquals("OK", ok.reasonPhrase());

        assertEquals("Method Not Allowed", RawHttpClient.parseStatusLine("HTTP/1.1 405 Method Not Allowed").reasonPhrase());
        assertEquals("", RawHttpClient.parseStatusLine("HTTP/1.1 204").reasonPhrase());
        assertEquals("", RawHttpClient.parseStatusLine("HTTP/1.0 503 ").reasonPhrase());
        assertEquals(100, RawHttpClient.parseStatusLine("HTTP/1.1 100 Continue").statusCode());
        assertEquals(599, RawHttpClient.parseStatusLine("HTTP/1.1 599 Custom").statusCode());
    }

    @Test
    void rejectsMalformedStatusLines() {
        for (String line : new String[] {
                "", "HTTP/1.1", "HTTP/1.1 ", "200 OK", "HTTP/1.1 20 OK", "HTTP/1.1 2000 OK", "HTTP/1.1 abc OK",
                "HTTP/1.1 +20 OK", "HTTP/1.1 099 Low", "HTTP/1.1 600 High", "HTTPS/1.1 200 OK", "HTTP/11 200 OK",
                "http/1.1 200 OK", "HTTP/1.1  200 OK", "garbage with spaces"}) {
            assertThrows(ProtocolException.class, () -> RawHttpClient.parseStatusLine(line), "line: '" + line + "'");
        }
    }

    // ---- building requests ------------------------------------------

    @Test
    void buildsAnExactGetRequest() {
        byte[] request = RawHttpClient.buildRequest("127.0.0.1", 8010, "GET", "/hello?a=1", Map.of(), null);
        assertEquals("GET /hello?a=1 HTTP/1.1\r\nHost: 127.0.0.1:8010\r\nConnection: close\r\n\r\n",
                new String(request, StandardCharsets.ISO_8859_1));
    }

    @Test
    void buildsAPostRequestWithUtf8ByteLengthAndNoTrailingNewline() {
        byte[] body = "{\"n\":\"caf\u00e9 \u20ac\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        byte[] request = RawHttpClient.buildRequest("localhost", 80, "POST", "/users", headers, body);

        String expectedHead = "POST /users HTTP/1.1\r\nHost: localhost:80\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        byte[] head = expectedHead.getBytes(StandardCharsets.ISO_8859_1);

        assertEquals(head.length + body.length, request.length);
        byte[] actualHead = java.util.Arrays.copyOfRange(request, 0, head.length);
        byte[] actualBody = java.util.Arrays.copyOfRange(request, head.length, request.length);
        assertArrayEquals(head, actualHead);
        assertArrayEquals(body, actualBody);
        assertTrue(body.length > "{\"n\":\"caf\u00e9 \u20ac\"}".length(), "Content-Length must count bytes, not characters");
    }

    @Test
    void postWithoutBodyAnnouncesAZeroLength() {
        String request = new String(
                RawHttpClient.buildRequest("h", 1, "POST", "/p", null, null), StandardCharsets.ISO_8859_1);
        assertTrue(request.contains("Content-Length: 0\r\n"));
        String get = new String(
                RawHttpClient.buildRequest("h", 1, "GET", "/p", null, null), StandardCharsets.ISO_8859_1);
        assertFalse(get.contains("Content-Length"));
    }

    @Test
    void emptyBodyIsSentAsZeroLengthWithoutExtraBytes() {
        byte[] request = RawHttpClient.buildRequest("h", 1, "POST", "/p", null, new byte[0]);
        assertTrue(new String(request, StandardCharsets.ISO_8859_1).endsWith("Content-Length: 0\r\nConnection: close\r\n\r\n"));
    }

    @Test
    void ipv6HostsAreBracketedInTheHostHeader() {
        String request = new String(
                RawHttpClient.buildRequest("::1", 8010, "GET", "/", null, null), StandardCharsets.ISO_8859_1);
        assertTrue(request.contains("Host: [::1]:8010\r\n"));
    }

    @Test
    void refusesToBuildInvalidRequests() {
        assertThrows(IllegalArgumentException.class, () -> RawHttpClient.buildRequest("", 1, "GET", "/", null, null));
        assertThrows(IllegalArgumentException.class, () -> RawHttpClient.buildRequest("h", 0, "GET", "/", null, null));
        assertThrows(IllegalArgumentException.class, () -> RawHttpClient.buildRequest("h", 70000, "GET", "/", null, null));
        assertThrows(IllegalArgumentException.class, () -> RawHttpClient.buildRequest("h", 1, "G ET", "/", null, null));
        assertThrows(IllegalArgumentException.class, () -> RawHttpClient.buildRequest("h", 1, "GET", "no-slash", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RawHttpClient.buildRequest("h", 1, "GET", "/a\r\nInjected: yes", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RawHttpClient.buildRequest("h", 1, "GET", "/", Map.of("X-A", "v\r\nInjected: yes"), null));
        assertThrows(IllegalArgumentException.class,
                () -> RawHttpClient.buildRequest("h\r\nInjected: yes", 1, "GET", "/", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RawHttpClient.buildRequest("h", 1, "GET", "/", Map.of("Content-Length", "5"), null));
    }

    // ---- real requests against a fake server ------------------------

    @Test
    void sendsTheExactBytesAndParsesTheResponse() throws Exception {
        String body = "caf\u00e9";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        try (FakeServer server = FakeServer.replying(
                "HTTP/1.1 201 Created\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nok")) {
            RawHttpClient.Response response = client().send("127.0.0.1", server.port(), "POST", "/users",
                    Map.of("Content-Type", "text/plain; charset=utf-8"), bodyBytes);

            assertEquals(201, response.statusCode());
            assertEquals("Created", response.reasonPhrase());
            assertEquals("HTTP/1.1 201 Created", response.statusLine());
            assertEquals("ok", response.bodyText());
            assertEquals("text/plain", response.headers().get("content-type"));
            assertTrue(response.elapsedNanos() > 0);

            byte[] sent = server.awaitRequest();
            String expectedHead = "POST /users HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                    + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + bodyBytes.length
                    + "\r\nConnection: close\r\n\r\n";
            byte[] head = expectedHead.getBytes(StandardCharsets.ISO_8859_1);
            assertEquals(head.length + bodyBytes.length, sent.length, "no bytes may be added after the body");
            assertArrayEquals(bodyBytes, java.util.Arrays.copyOfRange(sent, head.length, sent.length));
        }
    }

    @Test
    void decodesUtf8BodiesUsingByteLengths() throws Exception {
        byte[] body = "h\u00e9llo \u20ac".getBytes(StandardCharsets.UTF_8);
        byte[] reply = ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[reply.length + body.length];
        System.arraycopy(reply, 0, all, 0, reply.length);
        System.arraycopy(body, 0, all, reply.length, body.length);
        try (FakeServer server = FakeServer.replying(all)) {
            assertEquals("h\u00e9llo \u20ac", get(server).bodyText());
        }
    }

    @Test
    void headerNamesAreCaseInsensitiveAndDuplicatesAreCombined() throws Exception {
        try (FakeServer server = FakeServer.replying(
                "HTTP/1.1 200 OK\r\ncontent-length: 5\r\nX-Multi: a\r\nx-multi: b\r\n\r\nhello")) {
            RawHttpClient.Response response = get(server);
            assertEquals("hello", response.bodyText());
            assertEquals("a, b", response.headers().get("X-MULTI"));
        }
    }

    @Test
    void toleratesAMissingReasonPhraseAndBareLineFeeds() throws Exception {
        try (FakeServer server = FakeServer.replying("HTTP/1.1 200\nContent-Length: 2\n\nok")) {
            RawHttpClient.Response response = get(server);
            assertEquals(200, response.statusCode());
            assertEquals("", response.reasonPhrase());
            assertEquals("HTTP/1.1 200", response.statusLine());
            assertEquals("ok", response.bodyText());
        }
    }

    @Test
    void readsUntilCloseWhenThereIsNoContentLength() throws Exception {
        try (FakeServer server = FakeServer.replying("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nread until close")) {
            assertEquals("read until close", get(server).bodyText());
        }
    }

    @Test
    void headAndNoContentStatusesHaveNoBody() throws Exception {
        try (FakeServer server = FakeServer.replying(OK_HELLO)) {
            RawHttpClient.Response head = client().send("127.0.0.1", server.port(), "HEAD", "/x", null, null);
            assertEquals(200, head.statusCode());
            assertEquals("", head.bodyText());
            assertEquals("5", head.headers().get("Content-Length"));
        }
        try (FakeServer server = FakeServer.replying("HTTP/1.1 204 No Content\r\n\r\n")) {
            assertEquals("", get(server).bodyText());
        }
    }

    @Test
    void emptyResponseIsAProtocolError() throws Exception {
        try (FakeServer server = FakeServer.replying(new byte[0])) {
            ProtocolException e = assertThrows(ProtocolException.class, () -> get(server));
            assertTrue(e.getMessage().contains("closed before any response"));
        }
    }

    @Test
    void garbageStatusLineIsAProtocolError() throws Exception {
        assertTrue(protocolError("NOT HTTP AT ALL\r\n\r\n").getMessage().startsWith("Malformed HTTP status line"));
    }

    @Test
    void truncatedBodyIsAProtocolError() throws Exception {
        ProtocolException e = protocolError("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort");
        assertTrue(e.getMessage().contains("5 of 100"));
    }

    @Test
    void truncatedHeadersAreAProtocolError() throws Exception {
        assertTrue(protocolError("HTTP/1.1 200 OK\r\nContent-Le").getMessage().contains("middle of the headers"));
        assertTrue(protocolError("HTTP/1.1 200 OK\r\nX: y\r\n").getMessage().contains("before the end of the response headers"));
    }

    @Test
    void malformedHeadersAreAProtocolError() throws Exception {
        assertTrue(protocolError("HTTP/1.1 200 OK\r\nno colon\r\n\r\n").getMessage().startsWith("Malformed response header"));
        assertTrue(protocolError("HTTP/1.1 200 OK\r\nBad Name: v\r\n\r\n").getMessage().startsWith("Malformed response header"));
    }

    @Test
    void invalidContentLengthIsAProtocolError() throws Exception {
        assertTrue(protocolError("HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\n").getMessage().contains("Invalid Content-Length"));
        assertTrue(protocolError("HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n").getMessage().contains("Invalid Content-Length"));
    }

    @Test
    void chunkedResponsesAreRejectedClearly() throws Exception {
        assertTrue(protocolError("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
                .getMessage().contains("not supported"));
    }

    @Test
    void tooManyHeadersAreRejected() throws Exception {
        StringBuilder reply = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 150; i++) {
            reply.append("X-").append(i).append(": v\r\n");
        }
        reply.append("\r\n");
        assertTrue(protocolError(reply.toString()).getMessage().contains("too large"));
    }

    @Test
    void overlongStatusLineIsRejected() throws Exception {
        assertTrue(protocolError("HTTP/1.1 200 " + "x".repeat(10_000) + "\r\n\r\n").getMessage().contains("too long"));
    }

    @Test
    void oversizedBodiesAreRejectedBeforeBeingRead() throws Exception {
        RawHttpClient small = new RawHttpClient(1_000, 2_000, 5_000, 10);
        try (FakeServer server = FakeServer.replying("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n" + "x".repeat(100))) {
            ProtocolException e = assertThrows(ProtocolException.class,
                    () -> small.send("127.0.0.1", server.port(), "GET", "/x", null, null));
            assertTrue(e.getMessage().contains("exceeds the limit"));
        }
        try (FakeServer server = FakeServer.replying("HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(100))) {
            assertThrows(ProtocolException.class, () -> small.send("127.0.0.1", server.port(), "GET", "/x", null, null));
        }
    }

    @Test
    void aBodyExactlyAtTheLimitIsAccepted() throws Exception {
        RawHttpClient exact = new RawHttpClient(1_000, 2_000, 5_000, 5);
        try (FakeServer server = FakeServer.replying(OK_HELLO)) {
            assertEquals("hello", exact.send("127.0.0.1", server.port(), "GET", "/x", null, null).bodyText());
        }
    }

    // ---- timeouts and connection failures ----------------------------

    @Test
    void aSilentServerCausesAReadTimeout() throws Exception {
        RawHttpClient impatient = new RawHttpClient(1_000, 300, 5_000, 1024);
        try (FakeServer server = FakeServer.silent()) {
            long start = System.nanoTime();
            SocketTimeoutException e = assertThrows(SocketTimeoutException.class,
                    () -> impatient.send("127.0.0.1", server.port(), "GET", "/x", null, null));
            assertTrue(e.getMessage().toLowerCase().contains("read timed out"));
            assertTrue((System.nanoTime() - start) / 1_000_000 < 4_000, "must not hang");
        }
    }

    @Test
    void aServerThatTricklesIsCutOffByTheTotalTimeout() throws Exception {
        RawHttpClient strict = new RawHttpClient(1_000, 2_000, 400, 1024);
        try (FakeServer server = FakeServer.trickling(OK_HELLO, 80)) {
            SocketTimeoutException e = assertThrows(SocketTimeoutException.class,
                    () -> strict.send("127.0.0.1", server.port(), "GET", "/x", null, null));
            assertTrue(e.getMessage().contains("Total timeout"));
        }
    }

    @Test
    void aClosedPortIsReportedAsAConnectionFailure() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        int closedPort = port;
        ConnectException e = assertThrows(ConnectException.class,
                () -> client().send("127.0.0.1", closedPort, "GET", "/x", null, null));
        assertTrue(RawHttpClient.describeFailure(e).startsWith("Connection failed"));
    }

    @Test
    void invalidConstructorArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RawHttpClient(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RawHttpClient(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RawHttpClient(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RawHttpClient(1, 1, 1, -1));
    }

    // ---- helpers ------------------------------------------------------

    @Test
    void describesFailuresInStableReadableWays() {
        assertEquals("Protocol error: bad", RawHttpClient.describeFailure(new ProtocolException("bad")));
        assertEquals("Read timed out", RawHttpClient.describeFailure(new SocketTimeoutException("Read timed out")));
        assertEquals("Timed out", RawHttpClient.describeFailure(new SocketTimeoutException()));
        assertEquals("Connection failed: refused", RawHttpClient.describeFailure(new ConnectException("refused")));
        assertEquals("Socket error: reset", RawHttpClient.describeFailure(new SocketException("reset")));
        assertEquals("Unknown host: nope", RawHttpClient.describeFailure(new java.net.UnknownHostException("nope")));
        assertEquals("IOException: other", RawHttpClient.describeFailure(new IOException("other")));
        assertEquals("IOException", RawHttpClient.describeFailure(new IOException()));
    }

    @Test
    void sanitizesUntrustedTextForDisplay() {
        assertEquals("plain\ttext\r\nok", RawHttpClient.sanitizeForDisplay("plain\ttext\r\nok"));
        assertEquals("a?[31mred?[0m", RawHttpClient.sanitizeForDisplay("a\u001b[31mred\u001b[0m"));
        assertEquals("nul?byte?del", RawHttpClient.sanitizeForDisplay("nul\u0000byte\u007fdel"));
        assertEquals("ab...", RawHttpClient.printable("abcdef", 2));
        assertEquals("a?b", RawHttpClient.printable("a\nb", 10));
    }

    @Test
    void responseObjectsAreDefensivelyCopied() throws Exception {
        try (FakeServer server = FakeServer.replying(OK_HELLO)) {
            RawHttpClient.Response response = get(server);
            response.body()[0] = 'X';
            assertEquals("hello", response.bodyText());
        }
    }
}
