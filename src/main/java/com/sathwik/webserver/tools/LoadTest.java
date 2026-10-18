package com.sathwik.webserver.tools;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small concurrent load check for the server.
 *
 * <p>A fixed pool of {@code --clients} threads is created (never more than {@value #MAX_CLIENTS}).
 * Each thread waits at a start gate so all clients begin together, then sends
 * {@code --requests-per-client} requests one after another, using a new connection for each.
 * Elapsed time and latency use {@link System#nanoTime()} (a monotonic clock).
 *
 * <p><b>What this is not:</b> it is a quick functional check that the server copes with concurrent
 * connections and reports failures honestly. It runs on one machine, against loopback by default, with
 * the load generator and the server competing for the same CPUs. It is not a production-grade
 * benchmark and its numbers must not be quoted as server capacity.
 *
 * <p>Exit codes: {@code 0} every request got a 2xx response; {@code 1} at least one request failed,
 * returned a non-2xx status, did not complete, or the run was interrupted; {@code 2} invalid command line.
 */
public final class LoadTest {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    static final int MAX_CLIENTS = 1_000;
    static final int MAX_REQUESTS_PER_CLIENT = 10_000;
    static final long MAX_TOTAL_REQUESTS = 1_000_000L;

    /** Responses are read in full but never kept; anything over this is a protocol failure. */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int TOTAL_TIMEOUT_MILLIS = 30_000;
    private static final long READY_TIMEOUT_SECONDS = 10;
    private static final long SHUTDOWN_EXTRA_GRACE_MILLIS = 1_000;

    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--host", "--port", "--path", "--method", "--body", "--content-type",
            "--clients", "--requests-per-client", "--connect-timeout-ms", "--read-timeout-ms");
    private static final Set<String> FLAG_OPTIONS = Set.of("--help");

    private static final String USAGE = """
            Usage: java -cp <jar> com.sathwik.webserver.tools.LoadTest [options]

            Starts concurrent clients that all begin together, sends requests, and reports the results.
            This is a small functional check, NOT a production-grade benchmark.

            Options (each takes a value, as "--name value" or "--name=value"):
              --host <name>               Server host (default 127.0.0.1)
              --port <number>             Server port (default 8010)
              --path <path>               Request path, percent-encoded (default /hello)
              --method <name>             HTTP method (default GET, or POST when --body is given)
              --body <text>               Request body (UTF-8)
              --content-type <type>       Content-Type for the body (default text/plain; charset=utf-8)
              --clients <n>               Concurrent clients, 1-%d (default 20)
              --requests-per-client <n>   Sequential requests per client, 1-%d (default 1)
              --connect-timeout-ms <n>    Connect timeout (default 3000)
              --read-timeout-ms <n>       Longest a single read may block (default 5000)
              --help                      Show this help

            Exit codes: 0 = every request returned 2xx, 1 = any failure, non-2xx or interruption,
                        2 = bad command line.
            """.formatted(MAX_CLIENTS, MAX_REQUESTS_PER_CLIENT);

    private LoadTest() {
    }

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    static final class Options {

        final String host;
        final int port;
        final String method;
        final String path;
        final byte[] body;
        final Map<String, String> headers;
        final int clients;
        final int requestsPerClient;
        final int connectTimeoutMillis;
        final int readTimeoutMillis;

        Options(
                String host, int port, String method, String path, byte[] body, Map<String, String> headers,
                int clients, int requestsPerClient, int connectTimeoutMillis, int readTimeoutMillis
        ) {
            this.host = host;
            this.port = port;
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
            this.clients = clients;
            this.requestsPerClient = requestsPerClient;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
        }

        long plannedRequests() {
            return (long) clients * requestsPerClient;
        }

        /**
         * @throws IllegalArgumentException with a user-readable message for any invalid input
         */
        static Options parse(String[] args) {
            CommandLine cl = CommandLine.parse(args, VALUE_OPTIONS, FLAG_OPTIONS);

            String host = cl.has("--host") ? cl.last("--host") : "127.0.0.1";
            RawHttpClient.validateHost(host);
            int port = cl.integer("--port", 8010, 1, 65_535);
            String path = cl.has("--path") ? cl.last("--path") : "/hello";
            RawHttpClient.validatePath(path);

            byte[] body = cl.has("--body") ? cl.last("--body").getBytes(StandardCharsets.UTF_8) : null;
            String method = cl.has("--method")
                    ? cl.last("--method").toUpperCase(Locale.ROOT)
                    : (body != null ? "POST" : "GET");
            RawHttpClient.validateMethod(method);

            Map<String, String> headers = new LinkedHashMap<>();
            if (cl.has("--content-type")) {
                RawHttpClient.validateHeader("Content-Type", cl.last("--content-type"));
                headers.put("Content-Type", cl.last("--content-type"));
            } else if (body != null) {
                headers.put("Content-Type", "text/plain; charset=utf-8");
            }

            int clients = cl.integer("--clients", 20, 1, MAX_CLIENTS);
            int requestsPerClient = cl.integer("--requests-per-client", 1, 1, MAX_REQUESTS_PER_CLIENT);
            if ((long) clients * requestsPerClient > MAX_TOTAL_REQUESTS) {
                throw new IllegalArgumentException(
                        "clients x requests-per-client must not exceed " + MAX_TOTAL_REQUESTS);
            }

            return new Options(
                    host, port, method, path, body, headers, clients, requestsPerClient,
                    cl.integer("--connect-timeout-ms", RawHttpClient.DEFAULT_CONNECT_TIMEOUT_MILLIS, 1, 3_600_000),
                    cl.integer("--read-timeout-ms", RawHttpClient.DEFAULT_READ_TIMEOUT_MILLIS, 1, 3_600_000));
        }
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    /** The outcome of one run. */
    record Report(
            Options options,
            LoadTestMetrics.Snapshot snapshot,
            long elapsedNanos,
            boolean interrupted
    ) {
        long plannedRequests() {
            return options.plannedRequests();
        }

        /** Requests that never finished (only non-zero if the run was interrupted or a worker died). */
        long notCompleted() {
            return plannedRequests() - snapshot.completed();
        }

        double elapsedMillis() {
            return elapsedNanos / 1_000_000.0;
        }

        /** Finished requests divided by elapsed seconds; 0 if no measurable time passed. */
        double requestsPerSecond() {
            return elapsedNanos <= 0 ? 0.0 : snapshot.completed() / (elapsedNanos / 1_000_000_000.0);
        }

        boolean isSuccessful() {
            return !interrupted
                    && notCompleted() == 0
                    && snapshot.httpFailures() == 0
                    && snapshot.connectionFailures() == 0;
        }

        String format() {
            StringBuilder out = new StringBuilder();
            out.append("Load test results\n");
            out.append(String.format(Locale.ROOT, "  Target:                       %s http://%s:%d%s%n",
                    options.method, options.host, options.port, options.path));
            out.append(String.format(Locale.ROOT, "  Clients:                      %d concurrent, %d request(s) each%n",
                    options.clients, options.requestsPerClient));
            out.append(String.format(Locale.ROOT, "  Total requests:               %d%n", plannedRequests()));
            out.append(String.format(Locale.ROOT, "  Successful (2xx):             %d%n", snapshot.successes()));
            out.append(String.format(Locale.ROOT, "  HTTP failures (non-2xx):      %d%n", snapshot.httpFailures()));
            out.append(String.format(Locale.ROOT, "  Connection/protocol failures: %d%n", snapshot.connectionFailures()));
            if (notCompleted() > 0) {
                out.append(String.format(Locale.ROOT, "  Not completed:                %d%n", notCompleted()));
            }
            if (!snapshot.statusCounts().isEmpty()) {
                StringBuilder codes = new StringBuilder();
                snapshot.statusCounts().forEach((code, count) -> {
                    if (codes.length() > 0) {
                        codes.append(", ");
                    }
                    codes.append(code).append(" x").append(count);
                });
                out.append(String.format(Locale.ROOT, "  Status codes:                 %s%n", codes));
            }
            snapshot.failureReasons().forEach((reason, count) ->
                    out.append(String.format(Locale.ROOT, "  Failure:                      %s (x%d)%n", reason, count)));
            out.append(String.format(Locale.ROOT, "  Elapsed:                      %.1f ms%n", elapsedMillis()));
            out.append(String.format(Locale.ROOT, "  Throughput:                   %.1f requests/s%n", requestsPerSecond()));
            if (snapshot.responses() > 0) {
                out.append(String.format(Locale.ROOT,
                        "  Latency (responses only):     avg %.2f ms, min %.2f ms, max %.2f ms%n",
                        snapshot.averageLatencyNanos() / 1_000_000.0,
                        snapshot.minLatencyNanos() / 1_000_000.0,
                        snapshot.maxLatencyNanos() / 1_000_000.0));
            } else {
                out.append("  Latency (responses only):     n/a (no responses received)\n");
            }
            if (interrupted) {
                out.append("  The run was interrupted; results are partial.\n");
            }
            out.append("  Result:                       ").append(isSuccessful() ? "PASSED" : "FAILED").append('\n');
            out.append('\n');
            out.append("Note: a small single-machine check, not a production-grade benchmark. The load generator\n");
            out.append("and the server share this machine, so do not read these numbers as server capacity.\n");
            return out.toString();
        }
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int exitCode = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    /** Runs the load test and returns the process exit code. Separated from {@link #main} for testing. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                out.print(USAGE);
                return EXIT_OK;
            }
        }

        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println("Run with --help for usage.");
            return EXIT_USAGE;
        }

        out.printf(Locale.ROOT, "Starting %d client(s), %d request(s) each, against %s:%d%s ...%n",
                options.clients, options.requestsPerClient, options.host, options.port, options.path);
        Report report = execute(options);
        out.print(report.format());
        return report.isSuccessful() ? EXIT_OK : EXIT_FAILED;
    }

    /**
     * Runs the test and returns the report. If the calling thread is interrupted while waiting, the
     * workers are stopped, a partial report is returned, and the thread's interrupt flag is restored.
     */
    static Report execute(Options options) {
        LoadTestMetrics metrics = new LoadTestMetrics();
        RawHttpClient client = new RawHttpClient(
                options.connectTimeoutMillis, options.readTimeoutMillis, TOTAL_TIMEOUT_MILLIS, MAX_RESPONSE_BYTES);

        ExecutorService executor = Executors.newFixedThreadPool(options.clients, new WorkerThreadFactory());
        CountDownLatch ready = new CountDownLatch(options.clients);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(options.clients);

        for (int i = 0; i < options.clients; i++) {
            executor.execute(() -> {
                ready.countDown();
                try {
                    startGate.await();
                    for (int r = 0; r < options.requestsPerClient && !Thread.currentThread().isInterrupted(); r++) {
                        sendOne(client, options, metrics);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        boolean interrupted = false;
        long startNanos = System.nanoTime();
        long endNanos;
        try {
            // Wait until every worker thread is up so that they really are released together.
            ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            startNanos = System.nanoTime();
            startGate.countDown();
            finished.await();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        endNanos = System.nanoTime();

        interrupted |= shutDown(executor, options);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Report(options, metrics.snapshot(), endNanos - startNanos, interrupted);
    }

    private static void sendOne(RawHttpClient client, Options options, LoadTestMetrics metrics) {
        try {
            RawHttpClient.Response response = client.send(
                    options.host, options.port, options.method, options.path, options.headers, options.body);
            metrics.recordResponse(response.statusCode(), response.elapsedNanos());
        } catch (IOException e) {
            metrics.recordConnectionFailure(RawHttpClient.describeFailure(e));
        } catch (RuntimeException e) {
            metrics.recordConnectionFailure("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Stops the worker pool. Workers blocked in socket I/O cannot be interrupted, but every socket
     * operation has a timeout, so they finish within the grace period. Returns true if this thread
     * was interrupted while waiting.
     */
    private static boolean shutDown(ExecutorService executor, Options options) {
        executor.shutdownNow();
        long graceMillis = (long) options.connectTimeoutMillis + options.readTimeoutMillis + SHUTDOWN_EXTRA_GRACE_MILLIS;
        try {
            if (!executor.awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) {
                System.err.println("Warning: some load-test threads are still running after " + graceMillis + " ms");
            }
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "loadtest-worker-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
