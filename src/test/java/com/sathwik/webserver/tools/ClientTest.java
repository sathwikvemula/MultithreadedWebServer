package com.sathwik.webserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sathwik.webserver.DemoRoutes;
import com.sathwik.webserver.Server;
import com.sathwik.webserver.ServerConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs the command-line client against a real local server. */
class ClientTest {

    private record Result(int exitCode, String out, String err) {
    }

    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(Server::stop);
        servers.clear();
    }

    private Server startServer() throws IOException {
        Server server = new Server(ServerConfig.builder().port(0).build(), DemoRoutes.create());
        servers.add(server);
        server.start();
        return server;
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Client.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String[] withPort(Server server, String... args) {
        String[] all = new String[args.length + 2];
        all[0] = "--port";
        all[1] = String.valueOf(server.getPort());
        System.arraycopy(args, 0, all, 2, args.length);
        return all;
    }

    // ---- successful requests -------------------------------------------

    @Test
    void getsHelloAndPrintsStatusHeadersAndBody() throws Exception {
        Server server = startServer();
        Result result = run(withPort(server));
        assertEquals(0, result.exitCode());
        assertTrue(result.out().startsWith("HTTP/1.1 200 OK"));
        assertTrue(result.out().contains("Content-Type: text/plain; charset=utf-8"));
        assertTrue(result.out().contains("Hello from my Java web server!"));
        assertEquals("", result.err());
    }

    @Test
    void noHeadersPrintsOnlyTheExactBody() throws Exception {
        Server server = startServer();
        Result result = run(withPort(server, "--path", "/health", "--no-headers"));
        assertEquals(0, result.exitCode());
        assertEquals("Server is healthy", result.out());
    }

    @Test
    void postsAUtf8BodyWithoutAnyExtraBytes() throws Exception {
        Server server = startServer();
        String json = "{\"name\":\"Zo\u00eb\",\"price\":\"\u20ac5\"}";

        Result result = run(withPort(server, "--method", "POST", "--path", "/users", "--body", json,
                "--content-type", "application/json"));
        assertEquals(0, result.exitCode());
        assertTrue(result.out().startsWith("HTTP/1.1 201 Created"));
        assertTrue(result.out().contains("User created successfully: " + json));

        // Byte-exact check: the echo contains the body exactly, so nothing was appended or lost.
        RawHttpClient.Response raw = new RawHttpClient().send("127.0.0.1", server.getPort(), "POST", "/users",
                Map.of("Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8));
        assertEquals("User created successfully: " + json, raw.bodyText());
        assertEquals(String.valueOf(raw.body().length), raw.headers().get("Content-Length"));
    }

    @Test
    void aBodyAloneImpliesPost() throws Exception {
        Server server = startServer();
        Result result = run(withPort(server, "--path", "/users", "--body", "hi", "--no-headers"));
        assertEquals(0, result.exitCode());
        assertEquals("User created successfully: hi", result.out());
    }

    @Test
    void sendsABodyFileExactly() throws Exception {
        Server server = startServer();
        Path file = Files.createTempFile("client-body", ".txt");
        try {
            Files.write(file, "line1\nline2 \u00e9".getBytes(StandardCharsets.UTF_8));
            Result result = run(withPort(server, "--path", "/users", "--body-file", file.toString(), "--no-headers"));
            assertEquals(0, result.exitCode());
            assertEquals("User created successfully: line1\nline2 \u00e9", result.out());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---- non-2xx responses ----------------------------------------------

    @Test
    void methodNotAllowedPrintsTheResponseAndExitsWithOne() throws Exception {
        Server server = startServer();
        Result result = run(withPort(server, "--method", "DELETE", "--path", "/users"));
        assertEquals(1, result.exitCode());
        assertTrue(result.out().startsWith("HTTP/1.1 405 Method Not Allowed"));
        assertTrue(result.out().contains("Allow: GET, POST"));
        assertTrue(result.out().contains("HTTP method not allowed for this route"));
    }

    @Test
    void notFoundExitsWithOne() throws Exception {
        Server server = startServer();
        Result result = run(withPort(server, "--path", "/missing"));
        assertEquals(1, result.exitCode());
        assertTrue(result.out().startsWith("HTTP/1.1 404 Not Found"));
    }

    // ---- failures ----------------------------------------------------------

    @Test
    void connectionRefusedExitsWithThreeAndExplainsWhy() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Result result = run("--port", String.valueOf(port));
        assertEquals(3, result.exitCode());
        assertEquals("", result.out());
        assertTrue(result.err().contains("127.0.0.1:" + port), result.err());
        assertTrue(result.err().contains("Connection failed"), result.err());
    }

    @Test
    void aSilentServerTimesOutInsteadOfHanging() throws Exception {
        try (FakeServer silent = FakeServer.silent()) {
            long start = System.nanoTime();
            Result result = run("--port", String.valueOf(silent.port()), "--read-timeout-ms", "300");
            assertEquals(3, result.exitCode());
            assertTrue(result.err().toLowerCase().contains("timed out"), result.err());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 4_000);
        }
    }

    @Test
    void aMalformedResponseExitsWithThree() throws Exception {
        try (FakeServer fake = FakeServer.replying("this is not http\r\n\r\n")) {
            Result result = run("--port", String.valueOf(fake.port()));
            assertEquals(3, result.exitCode());
            assertTrue(result.err().contains("Protocol error"), result.err());
        }
    }

    // ---- what goes on the wire and onto the terminal ------------------------

    @Test
    void sendsHostConnectionCloseAndCustomHeaders() throws Exception {
        try (FakeServer fake = FakeServer.replying("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")) {
            Result result = run("--port", String.valueOf(fake.port()), "--path", "/echo?a=1",
                    "--header", "X-Test: abc", "--header", "Accept: text/plain");
            assertEquals(0, result.exitCode());

            String request = fake.awaitRequestText();
            assertTrue(request.startsWith("GET /echo?a=1 HTTP/1.1\r\n"), request);
            assertTrue(request.contains("\r\nHost: 127.0.0.1:" + fake.port() + "\r\n"), request);
            assertTrue(request.contains("\r\nX-Test: abc\r\n"), request);
            assertTrue(request.contains("\r\nAccept: text/plain\r\n"), request);
            assertTrue(request.contains("\r\nConnection: close\r\n"), request);
            assertTrue(request.endsWith("\r\n\r\n"), "a GET must have no body");
        }
    }

    @Test
    void terminalEscapeSequencesFromTheServerAreNeutralized() throws Exception {
        try (FakeServer fake = FakeServer.replying(
                "HTTP/1.1 200 OK\r\nX-Evil: a\u001b[2Jb\r\nContent-Length: 8\r\n\r\nhi\u001b[31mX")) {
            Result result = run("--port", String.valueOf(fake.port()));
            assertEquals(0, result.exitCode());
            assertFalse(result.out().contains("\u001b"));
            assertTrue(result.out().contains("hi?[31mX"));
            assertTrue(result.out().contains("X-Evil: a?[2Jb"));
        }
    }

    // ---- command line ----------------------------------------------------------

    @Test
    void helpPrintsUsageAndExitsWithZero() {
        Result result = run("--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().startsWith("Usage:"));
        assertTrue(result.out().contains("--body-file"));
        assertTrue(result.out().contains("OS code page"));
        assertEquals("", result.err());
    }

    @Test
    void usageErrorsExitWithTwoAndNeverConnect() {
        Result unknown = run("--bogus", "1");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.err().contains("Unknown option: --bogus"));
        assertEquals("", unknown.out());

        assertEquals(2, run("--port", "abc").exitCode());
        assertEquals(2, run("--path", "no-slash").exitCode());
        assertEquals(2, run("--body", "x", "--body-file", "y").exitCode());
        assertEquals(2, run("stray").exitCode());
    }
}
