package com.sathwik.webserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoadTestMetricsTest {

    private static final long MS = 1_000_000L;

    // ---- classification -------------------------------------------------

    @Test
    void classifiesTwoHundredsAsSuccessAndEverythingElseAsHttpFailure() {
        for (int code : new int[] {200, 201, 204, 250, 299}) {
            assertEquals(LoadTestMetrics.Outcome.SUCCESS, LoadTestMetrics.classify(code), "code " + code);
        }
        for (int code : new int[] {100, 101, 199, 300, 301, 304, 400, 404, 405, 408, 413, 500, 501, 503, 505, 599}) {
            assertEquals(LoadTestMetrics.Outcome.HTTP_FAILURE, LoadTestMetrics.classify(code), "code " + code);
        }
    }

    // ---- aggregation ------------------------------------------------------

    @Test
    void anEmptyRecorderReportsZeros() {
        LoadTestMetrics.Snapshot snapshot = new LoadTestMetrics().snapshot();
        assertEquals(0, snapshot.successes());
        assertEquals(0, snapshot.httpFailures());
        assertEquals(0, snapshot.connectionFailures());
        assertEquals(0, snapshot.responses());
        assertEquals(0, snapshot.completed());
        assertEquals(0, snapshot.minLatencyNanos());
        assertEquals(0, snapshot.maxLatencyNanos());
        assertEquals(0.0, snapshot.averageLatencyNanos(), 0.0);
        assertTrue(snapshot.statusCounts().isEmpty());
        assertTrue(snapshot.failureReasons().isEmpty());
    }

    @Test
    void countsEachOutcomeSeparately() {
        LoadTestMetrics metrics = new LoadTestMetrics();
        metrics.recordResponse(200, MS);
        metrics.recordResponse(201, MS);
        metrics.recordResponse(404, MS);
        metrics.recordResponse(503, MS);
        metrics.recordResponse(500, MS);
        metrics.recordConnectionFailure("Connection failed: refused");

        LoadTestMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.successes());
        assertEquals(3, snapshot.httpFailures());
        assertEquals(1, snapshot.connectionFailures());
        assertEquals(5, snapshot.responses());
        assertEquals(6, snapshot.completed());
    }

    @Test
    void tracksStatusCodes() {
        LoadTestMetrics metrics = new LoadTestMetrics();
        metrics.recordResponse(200, MS);
        metrics.recordResponse(200, MS);
        metrics.recordResponse(503, MS);
        assertEquals(java.util.Map.of(200, 2L, 503, 1L), metrics.snapshot().statusCounts());
    }

    @Test
    void latencyCoversOnlyRequestsThatGotAResponse() {
        LoadTestMetrics metrics = new LoadTestMetrics();
        metrics.recordResponse(200, 2 * MS);
        metrics.recordResponse(404, 4 * MS);
        metrics.recordResponse(200, 6 * MS);
        metrics.recordConnectionFailure("Read timed out"); // must not influence latency

        LoadTestMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2 * MS, snapshot.minLatencyNanos());
        assertEquals(6 * MS, snapshot.maxLatencyNanos());
        assertEquals(12 * MS, snapshot.totalLatencyNanos());
        assertEquals(4.0 * MS, snapshot.averageLatencyNanos(), 0.0001);
    }

    @Test
    void groupsFailureReasonsAndCountsThem() {
        LoadTestMetrics metrics = new LoadTestMetrics();
        metrics.recordConnectionFailure("Read timed out");
        metrics.recordConnectionFailure("Read timed out");
        metrics.recordConnectionFailure("Connection failed: refused");
        assertEquals(java.util.Map.of("Read timed out", 2L, "Connection failed: refused", 1L),
                metrics.snapshot().failureReasons());
    }

    @Test
    void limitsTheNumberOfDistinctFailureReasons() {
        LoadTestMetrics metrics = new LoadTestMetrics();
        int distinct = LoadTestMetrics.MAX_DISTINCT_FAILURE_REASONS;
        for (int i = 0; i < distinct + 5; i++) {
            metrics.recordConnectionFailure("reason " + i);
        }
        LoadTestMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(distinct + 5, snapshot.connectionFailures());
        assertEquals(distinct + 1, snapshot.failureReasons().size());
        assertEquals(5L, snapshot.failureReasons().get(LoadTestMetrics.OTHER_REASON).longValue());
        // an already-known reason is still counted under its own name
        metrics.recordConnectionFailure("reason 0");
        assertEquals(2L, metrics.snapshot().failureReasons().get("reason 0").longValue());
    }

    @Test
    void rejectsNegativeLatencies() {
        assertThrows(IllegalArgumentException.class, () -> new LoadTestMetrics().recordResponse(200, -1));
    }

    @Test
    void snapshotsAreIndependentOfLaterRecording() {
        LoadTestMetrics metrics = new LoadTestMetrics();
        metrics.recordResponse(200, MS);
        LoadTestMetrics.Snapshot first = metrics.snapshot();
        metrics.recordResponse(200, MS);
        assertEquals(1, first.successes());
        assertEquals(2, metrics.snapshot().successes());
        assertThrows(UnsupportedOperationException.class, () -> first.statusCounts().put(1, 1L));
    }

    @Test
    void aggregatesExactlyWhenRecordedFromManyThreads() throws Exception {
        LoadTestMetrics metrics = new LoadTestMetrics();
        int threads = 8;
        int perThread = 5_000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        switch (i % 4) {
                            case 0 -> metrics.recordResponse(200, MS);
                            case 1 -> metrics.recordResponse(404, 3 * MS);
                            case 2 -> metrics.recordResponse(503, 2 * MS);
                            default -> metrics.recordConnectionFailure("Read timed out");
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        LoadTestMetrics.Snapshot snapshot = metrics.snapshot();
        long quarter = (long) threads * perThread / 4;
        assertEquals(quarter, snapshot.successes());
        assertEquals(2 * quarter, snapshot.httpFailures());
        assertEquals(quarter, snapshot.connectionFailures());
        assertEquals(4 * quarter, snapshot.completed());
        assertEquals(quarter, snapshot.statusCounts().get(404).longValue());
        assertEquals(quarter, snapshot.failureReasons().get("Read timed out").longValue());
        assertEquals(MS, snapshot.minLatencyNanos());
        assertEquals(3 * MS, snapshot.maxLatencyNanos());
        assertEquals(quarter * (MS + 3 * MS + 2 * MS), snapshot.totalLatencyNanos());
    }
}
