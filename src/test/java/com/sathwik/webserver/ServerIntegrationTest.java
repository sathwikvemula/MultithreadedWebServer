package com.sathwik.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sathwik.webserver.TestHttpClient.Response;

/** Starts real servers on free ports and talks to them over real sockets. */
class ServerIntegrationTest {

    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        for (Server server : servers) {
            server.stop();
        }
        servers.clear();
    }

    // ---- helpers -----------------------------------------------------

    private Server start(ServerConfig.Builder config, Router router) throws IOException {
        Server server = new Server(config.port(0).build(), router);
        servers.add(server);
        server.start();
        return server;
    }

    private Server startDemo() throws IOException {
        return start(ServerConfig.builder(), DemoRoutes.create());
    }

    private static String getRequest(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    }

    private static void awaitActive(Server server, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (server.getActiveConnections() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, server.getActiveConnections());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static <T> List<T> runConcurrently(int count, IntFunction<Callable<T>> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<T> work = task.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- demo routes over the wire -------------------------------------

    @Test
    void helloRouteReturnsTheOriginalResponse() throws Exception {
        Server server = startDemo();
        Response response = TestHttpClient.get(server.getPort(), "/hello");
        assertEquals(200, response.status);
        assertEquals("OK", response.reason);
        assertEquals("Hello from my Java web server!", response.body);
        assertEquals("text/plain; charset=utf-8", response.headers.get("Content-Type"));
        assertEquals("close", response.headers.get("Connection"));
        assertEquals("nosniff", response.headers.get("X-Content-Type-Options"));
    }

    @Test
    void healthRouteReturnsTheOriginalResponse() throws Exception {
        Server server = startDemo();
        Response response = TestHttpClient.get(server.getPort(), "/health");
        assertEquals(200, response.status);
        assertEquals("Server is healthy", response.body);
    }

    @Test
    void usersRouteReturnsTheFixedList() throws Exception {
        Server server = startDemo();
        Response response = TestHttpClient.get(server.getPort(), "/users");
        assertEquals(200, response.status);
        assertEquals("User 1\nUser 2\nUser 3", response.body);
    }

    @Test
    void postUsersEchoesTheBodyAndDoesNotPersistAnything() throws Exception {
        Server server = startDemo();
        String json = "{\"name\":\"Zo\u00eb\",\"note\":\"\u20ac5 \uD83D\uDE00\"}";

        Response created = TestHttpClient.post(server.getPort(), "/users", json);
        assertEquals(201, created.status);
        assertEquals("Created", created.reason);
        assertEquals("User created successfully: " + json, created.body);
        assertTrue(created.bodyLength > created.body.length(), "multi-byte characters must be counted as bytes");

        Response list = TestHttpClient.get(server.getPort(), "/users");
        assertEquals("User 1\nUser 2\nUser 3", list.body);
    }

    @Test
    void queryStringsAndPercentEncodedPathsAreRouted() throws Exception {
        Server server = startDemo();
        assertEquals(200, TestHttpClient.get(server.getPort(), "/hello?name=x&flag").status);
        assertEquals("Hello from my Java web server!", TestHttpClient.get(server.getPort(), "/%68ello").body);
    }

    @Test
    void headerNamesAreCaseInsensitiveOnTheWire() throws Exception {
        Server server = startDemo();
        String body = "caf\u00e9";
        int length = body.getBytes(StandardCharsets.UTF_8).length;
        Response response = TestHttpClient.send(server.getPort(),
                "POST /users HTTP/1.1\r\nHOST: localhost\r\ncontent-length: " + length + "\r\n\r\n" + body);
        assertEquals(201, response.status);
        assertEquals("User created successfully: caf\u00e9", response.body);
    }

    // ---- 404 / 405 ---------------------------------------------------

    @Test
    void unknownPathGives404() throws Exception {
        Server server = startDemo();
        Response response = TestHttpClient.get(server.getPort(), "/does-not-exist");
        assertEquals(404, response.status);
        assertEquals("Not Found", response.reason);
        assertEquals("Route not found", response.body);
    }

    @Test
    void unsupportedMethodGives405WithAllowHeader() throws Exception {
        Server server = startDemo();
        Response users = TestHttpClient.send(server.getPort(),
                "DELETE /users HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(405, users.status);
        assertEquals("Method Not Allowed", users.reason);
        assertEquals("HTTP method not allowed for this route", users.body);
        assertEquals("GET, POST", users.headers.get("Allow"));

        Response hello = TestHttpClient.send(server.getPort(),
                "POST /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
        assertEquals(405, hello.status);
        assertEquals("GET", hello.headers.get("Allow"));
    }

    @Test
   void headRequestsAreNotSupportedAndReturnNoBody() throws Exception {
    Server server = startDemo();
    Response response = TestHttpClient.send(server.getPort(),
            "HEAD /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");

    assertEquals(405, response.status);
    assertEquals("", response.body);
    assertEquals("GET", response.headers.get("Allow"));
}

    // ---- malformed and abusive requests -------------------------------

    @Test
    void malformedRequestsGetSpecificErrorsAndDoNotBreakTheServer() throws Exception {
        Server server = startDemo();
        int port = server.getPort();

        Response badLine = TestHttpClient.send(port, "GET /hello\r\n\r\n");
        assertEquals(400, badLine.status);
        assertEquals("Invalid HTTP request line", badLine.body);

        assertEquals(400, TestHttpClient.send(port, "NOT HTTP AT ALL\r\n\r\n").status);
        assertEquals(400, TestHttpClient.send(port, "\u0000\u0001\u0002\r\n\r\n").status);
        assertEquals(400, TestHttpClient.send(port, "GET /hello HTTP/1.1\r\nBroken header\r\n\r\n").status);
        assertEquals(400, TestHttpClient.send(port, "GET /hello HTTP/1.1\r\nAccept: */*\r\n\r\n").status);

        Response oldVersion = TestHttpClient.send(port, "GET /hello HTTP/1.0\r\nHost: x\r\n\r\n");
        assertEquals(505, oldVersion.status);
        assertEquals("Unsupported HTTP version", oldVersion.body);

        assertEquals(200, TestHttpClient.get(port, "/hello").status);
    }

    @Test
    void badContentLengthValuesAreRejected() throws Exception {
        Server server = startDemo();
        for (String value : new String[] {"abc", "-1", "1.5", "99999999999"}) {
            Response response = TestHttpClient.send(server.getPort(),
                    "POST /users HTTP/1.1\r\nHost: x\r\nContent-Length: " + value + "\r\n\r\n");
            assertEquals(400, response.status, "Content-Length: " + value);
        }
    }

    @Test
    void oversizedBodyGets413WithoutTheServerReadingIt() throws Exception {
        Server server = startDemo();
        Response response = TestHttpClient.send(server.getPort(),
                "POST /users HTTP/1.1\r\nHost: x\r\nContent-Length: 2000000\r\n\r\n");
        assertEquals(413, response.status);
        assertEquals("Request body too large", response.body);
    }

    @Test
    void incompleteBodyGets400() throws Exception {
        Server server = startDemo();
        try (Socket socket = TestHttpClient.open(server.getPort())) {
            TestHttpClient.write(socket, "POST /users HTTP/1.1\r\nHost: x\r\nContent-Length: 50\r\n\r\nshort");
            socket.shutdownOutput();
            Response response = TestHttpClient.read(socket);
            assertEquals(400, response.status);
            assertEquals("Request body is incomplete", response.body);
        }
    }

    @Test
    void tooManyHeadersGet431() throws Exception {
        Server server = startDemo();
        StringBuilder request = new StringBuilder("GET /hello HTTP/1.1\r\nHost: x\r\n");
        for (int i = 0; i < 150; i++) {
            request.append("X-Header-").append(i).append(": v\r\n");
        }
        request.append("\r\n");
        assertEquals(431, TestHttpClient.send(server.getPort(), request.toString()).status);
    }

    @Test
    void overlongRequestLineGets414() throws Exception {
        Server server = startDemo();
        String line = "GET /" + "a".repeat(9_000) + " HTTP/1.1\r\nHost: x\r\n\r\n";
        assertEquals(414, TestHttpClient.send(server.getPort(), line).status);
    }

    @Test
    void chunkedRequestsAreRejectedWith501() throws Exception {
        Server server = startDemo();
        Response response = TestHttpClient.send(server.getPort(),
                "POST /users HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
        assertEquals(501, response.status);
    }

    // ---- timeouts and misbehaving clients ----------------------------

    @Test
    void stalledClientGets408AndTheWorkerIsFreed() throws Exception {
        Server server = start(ServerConfig.builder().readTimeoutMillis(300), DemoRoutes.create());
        long start = System.nanoTime();
        try (Socket socket = TestHttpClient.open(server.getPort())) {
            TestHttpClient.write(socket, "GET /he");
            Response response = TestHttpClient.read(socket);
            assertEquals(408, response.status);
            assertEquals("Request timed out", response.body);
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000);
        awaitActive(server, 0);
    }

    @Test
    void silentConnectionGets408() throws Exception {
        Server server = start(ServerConfig.builder().readTimeoutMillis(200), DemoRoutes.create());
        try (Socket socket = TestHttpClient.open(server.getPort())) {
            assertEquals(408, TestHttpClient.read(socket).status);
        }
    }

    @Test
    void slowTricklingClientIsCutOffByTheRequestDeadline() throws Exception {
        Server server = start(
                ServerConfig.builder().readTimeoutMillis(2_000).requestTimeoutMillis(600),
                DemoRoutes.create());
        try (Socket socket = TestHttpClient.open(server.getPort())) {
            // One byte every 100 ms: each read succeeds, so only the overall deadline can stop this.
            String request = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";
            for (int i = 0; i < 10; i++) {
                socket.getOutputStream().write(request.charAt(i));
                socket.getOutputStream().flush();
                Thread.sleep(100);
            }
            Response response = TestHttpClient.read(socket);
            assertEquals(408, response.status);
            assertEquals("Request timed out", response.body);
        }
    }

    @Test
    void clientsThatDisconnectEarlyDoNotAffectTheServer() throws Exception {
        Server server = startDemo();
        int port = server.getPort();

        TestHttpClient.open(port).close(); // connect and close without sending anything
        try (Socket socket = TestHttpClient.open(port)) {
            TestHttpClient.write(socket, "POST /users HTTP/1.1\r\nHost: x\r\nContent-Length: 100\r\n\r\nabc");
        }
        try (Socket socket = TestHttpClient.open(port)) {
            TestHttpClient.write(socket, "GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
            socket.setSoLinger(true, 0); // abortive close: the client sends a reset
        }

        assertEquals(200, TestHttpClient.get(port, "/hello").status);
        awaitActive(server, 0);
    }

    @Test
    void stalledWriteIsCutOffByTheWriteTimeout() throws Exception {
        Router router = DemoRoutes.create()
                .get("/big", r -> HttpResponse.text(HttpStatus.OK, "x".repeat(64 * 1024 * 1024)));
        Server server = start(ServerConfig.builder().writeTimeoutMillis(500), router);
        long received = 0;
        try (Socket socket = TestHttpClient.open(server.getPort())) {
            TestHttpClient.write(socket, getRequest("/big"));
            awaitActive(server, 1);
            awaitActive(server, 0); // the watchdog closes the stuck connection

            byte[] buffer = new byte[64 * 1024];
            try {
                int count;
                while ((count = socket.getInputStream().read(buffer)) >= 0) {
                    received += count;
                }
            } catch (IOException connectionReset) {
                // acceptable: the server side was closed abruptly
            }
        }
        assertTrue(received < 64L * 1024 * 1024, "the response must have been cut short, received " + received);
        assertEquals(200, TestHttpClient.get(server.getPort(), "/hello").status);
    }

    // ---- failures inside route handlers ---------------------------------

    @Test
    void failingHandlerGives500WithoutLeakingDetailsAndServerKeepsRunning() throws Exception {
        Router router = DemoRoutes.create()
                .get("/boom", r -> {
                    throw new IllegalStateException("secret internal detail");
                })
                .get("/null", r -> null);
        Server server = start(ServerConfig.builder(), router);

        Response boom = TestHttpClient.get(server.getPort(), "/boom");
        assertEquals(500, boom.status);
        assertEquals("Internal Server Error", boom.body);
        assertFalse(boom.body.contains("secret"));

        assertEquals(500, TestHttpClient.get(server.getPort(), "/null").status);
        assertEquals(200, TestHttpClient.get(server.getPort(), "/hello").status);
        awaitActive(server, 0);
    }

    // ---- concurrency ------------------------------------------------------

    @Test
    void manyConcurrentClientsAreAllServedCorrectly() throws Exception {
        Server server = start(ServerConfig.builder().poolSize(8).queueCapacity(100), DemoRoutes.create());
        int port = server.getPort();

        List<Response> responses = runConcurrently(60, i -> () -> TestHttpClient.get(port, "/hello"));

        assertEquals(60, responses.size());
        for (Response response : responses) {
            assertEquals(200, response.status);
            assertEquals("Hello from my Java web server!", response.body);
        }
        awaitActive(server, 0);
        assertEquals(0, server.getRejectedConnections());
    }

    @Test
    void concurrentPostsNeverSeeEachOthersBodies() throws Exception {
        Server server = start(ServerConfig.builder().poolSize(8).queueCapacity(100), DemoRoutes.create());
        int port = server.getPort();

        List<Response> responses = runConcurrently(40,
                i -> () -> TestHttpClient.post(port, "/users", "client-" + i + "-caf\u00e9-" + "z".repeat(i * 50)));

        for (int i = 0; i < responses.size(); i++) {
            assertEquals(201, responses.get(i).status);
            assertEquals("User created successfully: client-" + i + "-caf\u00e9-" + "z".repeat(i * 50),
                    responses.get(i).body);
        }
    }

    @Test
    void neverRunsMoreRequestsAtOnceThanThereAreWorkerThreads() throws Exception {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger highest = new AtomicInteger();
        Router router = DemoRoutes.create().get("/slow", r -> {
            int now = current.incrementAndGet();
            highest.accumulateAndGet(now, Math::max);
            sleepQuietly(100);
            current.decrementAndGet();
            return HttpResponse.text(HttpStatus.OK, "done");
        });
        Server server = start(ServerConfig.builder().poolSize(3).queueCapacity(50), router);
        int port = server.getPort();

        List<Response> responses = runConcurrently(12, i -> () -> TestHttpClient.get(port, "/slow"));

        for (Response response : responses) {
            assertEquals(200, response.status);
            assertEquals("done", response.body);
        }
        assertTrue(highest.get() <= 3, "highest concurrency was " + highest.get());
        assertTrue(highest.get() >= 2, "requests should have overlapped, highest was " + highest.get());
    }

    // ---- overload policy -------------------------------------------------

    @Test
    void fullPoolAndQueueAnswer503AndQueuedRequestsAreStillServed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Router router = DemoRoutes.create().get("/block", r -> {
            entered.countDown();
            awaitQuietly(release);
            return HttpResponse.text(HttpStatus.OK, "released");
        });
        Server server = start(ServerConfig.builder().poolSize(1).queueCapacity(1), router);

        try (Socket running = TestHttpClient.open(server.getPort());
             Socket queued = TestHttpClient.open(server.getPort())) {
            TestHttpClient.write(running, getRequest("/block"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            TestHttpClient.write(queued, getRequest("/hello"));
            awaitActive(server, 2);

            Response rejected = TestHttpClient.get(server.getPort(), "/hello");
            assertEquals(503, rejected.status);
            assertEquals("Service Unavailable", rejected.reason);
            assertEquals("1", rejected.headers.get("Retry-After"));
            assertEquals("Server is overloaded, please retry later", rejected.body);
            assertEquals(1, server.getRejectedConnections());

            release.countDown();
            assertEquals("released", TestHttpClient.read(running).body);
            assertEquals("Hello from my Java web server!", TestHttpClient.read(queued).body);
        } finally {
            release.countDown();
        }
        awaitActive(server, 0);
        assertEquals(200, TestHttpClient.get(server.getPort(), "/hello").status);
    }

    // ---- lifecycle ----------------------------------------------------------

    @Test
    void portZeroPicksAFreePortAndServerReportsRunning() throws Exception {
        Server server = startDemo();
        assertTrue(server.getPort() > 0);
        assertTrue(server.isRunning());
    }

    @Test
    void startingTwiceFails() throws Exception {
        Server server = startDemo();
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    void aStoppedServerCannotBeRestarted() throws Exception {
        Server server = startDemo();
        server.stop();
        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::start);
    }

    @Test
    void getPortBeforeStartFails() {
        Server server = new Server(ServerConfig.builder().port(0).build(), DemoRoutes.create());
        assertThrows(IllegalStateException.class, server::getPort);
    }

    @Test
    void stoppingIsIdempotentAndWorksOnAServerThatNeverStarted() throws Exception {
        Server running = startDemo();
        running.stop();
        running.stop();
        running.close();

        Server neverStarted = new Server(ServerConfig.defaults(), DemoRoutes.create());
        servers.add(neverStarted);
        neverStarted.stop();
        neverStarted.awaitStopped();
    }

    @Test
    void bindingAnAddressThatIsInUseFails() throws Exception {
        Server first = startDemo();
        Server second = new Server(ServerConfig.builder().port(first.getPort()).build(), DemoRoutes.create());
        servers.add(second);
        assertThrows(IOException.class, second::start);
    }

    @Test
    void gracefulStopLetsInFlightRequestsFinishAndThenRefusesNewConnections() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        Router router = DemoRoutes.create().get("/slow", r -> {
            entered.countDown();
            sleepQuietly(500);
            return HttpResponse.text(HttpStatus.OK, "finished");
        });
        Server server = start(ServerConfig.builder().shutdownTimeoutSeconds(5), router);
        int port = server.getPort();

        try (Socket socket = TestHttpClient.open(port)) {
            TestHttpClient.write(socket, getRequest("/slow"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            Thread stopper = new Thread(server::stop);
            stopper.start();

            Response response = TestHttpClient.read(socket);
            assertEquals(200, response.status);
            assertEquals("finished", response.body);

            stopper.join(10_000);
            assertFalse(stopper.isAlive());
        }
        assertFalse(server.isRunning());
        assertEquals(0, server.getActiveConnections());
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void forcedStopClosesConnectionsThatDoNotFinishInTime() throws Exception {
        Server server = start(
                ServerConfig.builder().readTimeoutMillis(30_000).requestTimeoutMillis(60_000).shutdownTimeoutSeconds(1),
                DemoRoutes.create());

        try (Socket idle = TestHttpClient.open(server.getPort())) {
            awaitActive(server, 1);

            long start = System.nanoTime();
            server.stop();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMillis < 8_000, "stop took " + elapsedMillis + " ms");
            assertFalse(server.isRunning());
            assertEquals(0, server.getActiveConnections());

            int result;
            try {
                result = idle.getInputStream().read();
            } catch (IOException connectionClosed) {
                result = -1;
            }
            assertEquals(-1, result);
        }
    }

    @Test
    void stoppingFromSeveralThreadsAtOnceIsSafe() throws Exception {
        Server server = startDemo();
        List<Object> results = runConcurrently(5, i -> () -> {
            server.stop();
            return "stopped";
        });
        assertEquals(5, results.size());
        assertFalse(server.isRunning());
    }
}
