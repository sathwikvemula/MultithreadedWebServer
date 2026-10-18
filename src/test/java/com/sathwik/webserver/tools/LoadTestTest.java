package com.sathwik.webserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sathwik.webserver.DemoRoutes;
import com.sathwik.webserver.HttpResponse;
import com.sathwik.webserver.HttpStatus;
import com.sathwik.webserver.Router;
import com.sathwik.webserver.Server;
import com.sathwik.webserver.ServerConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs the load tester against real local servers. */
class LoadTestTest {

    private record Result(int exitCode, String out, String err) {
    }

    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(Server::stop);
        servers.clear();
    }

    private Server startServer(ServerConfig.Builder config, Router router) throws IOException {
        Server server = new Server(config.port(0).build(), router);
        servers.add(server);
        server.start();
        return server;
    }

    private Server startDemo() throws IOException {
        return startServer(ServerConfig.builder(), DemoRoutes.create());
    }

    private static LoadTest.Options options(Server server, String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "--port";
        args[1] = String.valueOf(server.getPort());
        System.arraycopy(extra, 0, args, 2, extra.length);
        return LoadTest.Options.parse(args);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LoadTest.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static long workerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("loadtest-worker-") && t.isAlive())
                .count();
    }

    // ---- successful runs ---------------------------------------------------

    @Test
    void theDefaultRunSends20SuccessfulRequests() throws Exception {
        Server server = startDemo();
        LoadTest.Report report = LoadTest.execute(options(server));

        assertEquals(20, report.plannedRequests());
        assertEquals(20, report.snapshot().successes());
        assertEquals(0, report.snapshot().httpFailures());
        assertEquals(0, report.snapshot().connectionFailures());
        assertEquals(Map.of(200, 20L), report.snapshot().statusCounts());
        assertEquals(0, report.notCompleted());
        assertFalse(report.interrupted());
        assertTrue(report.isSuccessful());
        assertTrue(report.elapsedNanos() > 0);
        assertTrue(report.requestsPerSecond() > 0);
        assertTrue(report.snapshot().averageLatencyNanos() > 0);
        assertTrue(report.snapshot().minLatencyNanos() <= report.snapshot().averageLatencyNanos());
        assertTrue(report.snapshot().averageLatencyNanos() <= report.snapshot().maxLatencyNanos());
        assertEquals(0, workerThreads(), "the executor must be shut down");
        assertEquals(0, server.getRejectedConnections());
    }

    @Test
    void severalRequestsPerClientAreAllCounted() throws Exception {
        Server server = startDemo();
        LoadTest.Report report = LoadTest.execute(options(server, "--clients", "5", "--requests-per-client", "10"));
        assertEquals(50, report.plannedRequests());
        assertEquals(50, report.snapshot().successes());
        assertTrue(report.isSuccessful());
    }

    @Test
    void postsWithABody() throws Exception {
        Server server = startDemo();
        LoadTest.Report report = LoadTest.execute(options(server,
                "--path", "/users", "--body", "{\"name\":\"caf\u00e9\"}", "--clients", "10"));
        assertEquals(Map.of(201, 10L), report.snapshot().statusCounts());
        assertTrue(report.isSuccessful());
    }

    // ---- failures are counted separately -----------------------------------------

    @Test
    void nonSuccessStatusesAreHttpFailuresNotConnectionFailures() throws Exception {
        Server server = startDemo();
        LoadTest.Report report = LoadTest.execute(options(server, "--path", "/missing"));
        assertEquals(0, report.snapshot().successes());
        assertEquals(20, report.snapshot().httpFailures());
        assertEquals(0, report.snapshot().connectionFailures());
        assertEquals(Map.of(404, 20L), report.snapshot().statusCounts());
        assertFalse(report.isSuccessful());

        LoadTest.Report wrongMethod = LoadTest.execute(options(server, "--method", "DELETE", "--path", "/users", "--clients", "4"));
        assertEquals(Map.of(405, 4L), wrongMethod.snapshot().statusCounts());
    }

    @Test
    void connectionFailuresAreCountedWhenNothingIsListening() throws Exception {
        LoadTest.Options options = LoadTest.Options.parse(new String[] {
                "--port", String.valueOf(closedPort()), "--clients", "8", "--connect-timeout-ms", "500"});
        LoadTest.Report report = LoadTest.execute(options);

        assertEquals(0, report.snapshot().responses());
        assertEquals(8, report.snapshot().connectionFailures());
        assertEquals(0, report.snapshot().httpFailures());
        assertEquals(0, report.notCompleted());
        assertFalse(report.snapshot().failureReasons().isEmpty());
        assertFalse(report.isSuccessful());
    }

    @Test
    void protocolErrorsCountAsConnectionFailures() throws Exception {
        try (FakeServer fake = FakeServer.replying("garbage\r\n\r\n")) {
            LoadTest.Report report = LoadTest.execute(LoadTest.Options.parse(
                    new String[] {"--port", String.valueOf(fake.port()), "--clients", "1"}));
            assertEquals(1, report.snapshot().connectionFailures());
            assertTrue(report.snapshot().failureReasons().keySet().iterator().next().startsWith("Protocol error"));
        }
    }

    @Test
    void anOverloadedServerShowsUpAs503HttpFailuresAlongsideSuccesses() throws Exception {
        Router router = DemoRoutes.create().get("/slow", r -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HttpResponse.text(HttpStatus.OK, "done");
        });
        Server server = startServer(ServerConfig.builder().poolSize(1).queueCapacity(1), router);

        LoadTest.Report report = LoadTest.execute(options(server, "--path", "/slow", "--clients", "6"));

        assertEquals(6, report.snapshot().completed());
        assertTrue(report.snapshot().successes() >= 1, "some requests must succeed");
        assertTrue(report.snapshot().httpFailures() >= 1, "some requests must be rejected");
        assertTrue(report.snapshot().statusCounts().containsKey(503));
        assertEquals(0, report.snapshot().connectionFailures());
        assertEquals(report.snapshot().statusCounts().get(503).longValue(), server.getRejectedConnections());
        assertFalse(report.isSuccessful());
    }

    // ---- concurrency ---------------------------------------------------------------

    @Test
    void allClientsAreReleasedTogether() throws Exception {
        int clients = 10;
        CyclicBarrier barrier = new CyclicBarrier(clients);
        Router router = DemoRoutes.create().get("/barrier", r -> {
            try {
                // Only completes if all 10 requests are inside the server at the same moment.
                barrier.await(10, TimeUnit.SECONDS);
                return HttpResponse.text(HttpStatus.OK, "together");
            } catch (Exception e) {
                throw new IllegalStateException("clients were not concurrent", e);
            }
        });
        Server server = startServer(ServerConfig.builder().poolSize(clients).queueCapacity(clients), router);

        LoadTest.Report report = LoadTest.execute(options(server, "--path", "/barrier", "--clients", String.valueOf(clients)));

        assertEquals(clients, report.snapshot().successes(), "statuses: " + report.snapshot().statusCounts());
        assertTrue(report.isSuccessful());
    }

    @Test
    void usesExactlyAsManyThreadsAsClientsAndNoMore() throws Exception {
        Router router = DemoRoutes.create().get("/slow", r -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HttpResponse.text(HttpStatus.OK, "done");
        });
        Server server = startServer(ServerConfig.builder().poolSize(10).queueCapacity(10), router);

        LoadTest.Report[] holder = new LoadTest.Report[1];
        Thread runner = new Thread(() -> holder[0] = LoadTest.execute(
                options(server, "--path", "/slow", "--clients", "6", "--requests-per-client", "2")));
        runner.start();

        long highest = 0;
        while (runner.isAlive()) {
            highest = Math.max(highest, workerThreads());
            Thread.sleep(5);
        }
        runner.join();

        assertEquals(6, highest);
        assertEquals(12, holder[0].snapshot().successes());
        assertEquals(0, workerThreads());
    }

    // ---- interruption ----------------------------------------------------------------

    @Test
    void interruptionStopsTheWorkersAndReturnsAPartialReport() throws Exception {
        try (FakeServer silent = FakeServer.silent()) {
            LoadTest.Options options = LoadTest.Options.parse(new String[] {
                    "--port", String.valueOf(silent.port()), "--clients", "5",
                    "--connect-timeout-ms", "500", "--read-timeout-ms", "1000"});

            LoadTest.Report[] holder = new LoadTest.Report[1];
            boolean[] flagRestored = new boolean[1];
            Thread runner = new Thread(() -> {
                holder[0] = LoadTest.execute(options);
                flagRestored[0] = Thread.currentThread().isInterrupted();
            });
            runner.start();
            Thread.sleep(300);
            long start = System.nanoTime();
            runner.interrupt();
            runner.join(15_000);

            assertFalse(runner.isAlive(), "execute must return after an interrupt");
            assertTrue((System.nanoTime() - start) / 1_000_000 < 8_000);
            assertTrue(holder[0].interrupted());
            assertFalse(holder[0].isSuccessful());
            assertTrue(flagRestored[0], "the interrupt flag must be restored for the caller");
            assertEquals(0, workerThreads(), "no worker thread may be left behind");
        }
    }

    // ---- the command-line wrapper and the report text ------------------------------------

    @Test
    void runPrintsAReportAndExitsZeroWhenEverythingSucceeds() throws Exception {
        Server server = startDemo();
        Result result = run("--port", String.valueOf(server.getPort()));

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("Starting 20 client(s)"));
        assertTrue(result.out().contains("Total requests:               20"));
        assertTrue(result.out().contains("Successful (2xx):             20"));
        assertTrue(result.out().contains("HTTP failures (non-2xx):      0"));
        assertTrue(result.out().contains("Connection/protocol failures: 0"));
        assertTrue(result.out().contains("Status codes:                 200 x20"));
        assertTrue(result.out().contains("Throughput:"));
        assertTrue(result.out().contains("Latency (responses only):"));
        assertTrue(result.out().contains("Result:                       PASSED"));
        assertTrue(result.out().contains("not a production-grade benchmark"));
        assertEquals("", result.err());
    }

    @Test
    void runExitsNonZeroOnHttpFailuresAndOnConnectionFailures() throws Exception {
        Server server = startDemo();
        Result notFound = run("--port", String.valueOf(server.getPort()), "--path", "/missing", "--clients", "3");
        assertEquals(1, notFound.exitCode());
        assertTrue(notFound.out().contains("HTTP failures (non-2xx):      3"));
        assertTrue(notFound.out().contains("Result:                       FAILED"));

        Result refused = run("--port", String.valueOf(closedPort()), "--clients", "3", "--connect-timeout-ms", "500");
        assertEquals(1, refused.exitCode());
        assertTrue(refused.out().contains("Connection/protocol failures: 3"));
        assertTrue(refused.out().contains("n/a (no responses received)"));
        assertTrue(refused.out().contains("Result:                       FAILED"));
    }

    @Test
    void runReportsUsageErrorsWithExitTwo() {
        Result bad = run("--clients", "0");
        assertEquals(2, bad.exitCode());
        assertTrue(bad.err().contains("--clients"));
        assertEquals("", bad.out());

        assertEquals(2, run("--bogus", "1").exitCode());
        Result help = run("--help");
        assertEquals(0, help.exitCode());
        assertTrue(help.out().startsWith("Usage:"));
        assertTrue(help.out().contains("NOT a production-grade benchmark"));
    }

    @Test
    void reportComputesThroughputFromElapsedTime() {
        LoadTest.Options options = LoadTest.Options.parse(new String[0]);
        LoadTestMetrics metrics = new LoadTestMetrics();
        for (int i = 0; i < 20; i++) {
            metrics.recordResponse(200, 5_000_000L);
        }
        LoadTest.Report report = new LoadTest.Report(options, metrics.snapshot(), 500_000_000L, false);
        assertEquals(40.0, report.requestsPerSecond(), 0.0001);
        assertEquals(500.0, report.elapsedMillis(), 0.0001);
        assertTrue(report.format().contains("Throughput:                   40.0 requests/s"));
        assertTrue(report.format().contains("avg 5.00 ms, min 5.00 ms, max 5.00 ms"));

        LoadTest.Report instant = new LoadTest.Report(options, metrics.snapshot(), 0L, false);
        assertEquals(0.0, instant.requestsPerSecond(), 0.0);
    }

    @Test
    void anIncompleteOrInterruptedRunIsNeverSuccessful() {
        LoadTest.Options options = LoadTest.Options.parse(new String[0]);
        LoadTestMetrics metrics = new LoadTestMetrics();
        for (int i = 0; i < 19; i++) {
            metrics.recordResponse(200, 1_000_000L);
        }
        LoadTest.Report incomplete = new LoadTest.Report(options, metrics.snapshot(), 1_000_000L, false);
        assertEquals(1, incomplete.notCompleted());
        assertFalse(incomplete.isSuccessful());
        assertTrue(incomplete.format().contains("Not completed:                1"));

        metrics.recordResponse(200, 1_000_000L);
        assertTrue(new LoadTest.Report(options, metrics.snapshot(), 1_000_000L, false).isSuccessful());
        LoadTest.Report interrupted = new LoadTest.Report(options, metrics.snapshot(), 1_000_000L, true);
        assertFalse(interrupted.isSuccessful());
        assertTrue(interrupted.format().contains("interrupted"));
    }
}
