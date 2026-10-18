package com.sathwik.webserver.tools;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe recorder for load-test results. Every request ends in exactly one of three outcomes:
 * <ul>
 *   <li>{@link Outcome#SUCCESS}: a complete HTTP response with a 2xx status;</li>
 *   <li>{@link Outcome#HTTP_FAILURE}: a complete HTTP response with any other status (including 503);</li>
 *   <li>{@link Outcome#CONNECTION_FAILURE}: no usable response (refused, reset, timed out, or a malformed
 *       or truncated response).</li>
 * </ul>
 *
 * <p>Latency is recorded only for requests that received a complete response, because the time
 * until a connection fails says nothing about how fast the server answers.
 */
public final class LoadTestMetrics {

    public enum Outcome { SUCCESS, HTTP_FAILURE, CONNECTION_FAILURE }

    /** Distinct failure descriptions kept; further distinct ones are counted under "(other)". */
    static final int MAX_DISTINCT_FAILURE_REASONS = 20;
    static final String OTHER_REASON = "(other)";

    private final LongAdder successes = new LongAdder();
    private final LongAdder httpFailures = new LongAdder();
    private final LongAdder connectionFailures = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private final AtomicLong minLatencyNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxLatencyNanos = new AtomicLong(0);
    private final ConcurrentHashMap<Integer, LongAdder> statusCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> failureReasons = new TreeMap<>();

    /** Maps an HTTP status code to SUCCESS (200-299) or HTTP_FAILURE (anything else). */
    public static Outcome classify(int statusCode) {
        return statusCode >= 200 && statusCode <= 299 ? Outcome.SUCCESS : Outcome.HTTP_FAILURE;
    }

    /** Records a complete HTTP response and how long the request took, measured with a monotonic clock. */
    public void recordResponse(int statusCode, long latencyNanos) {
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latency must not be negative");
        }
        if (classify(statusCode) == Outcome.SUCCESS) {
            successes.increment();
        } else {
            httpFailures.increment();
        }
        statusCounts.computeIfAbsent(statusCode, code -> new LongAdder()).increment();
        totalLatencyNanos.add(latencyNanos);
        minLatencyNanos.accumulateAndGet(latencyNanos, Math::min);
        maxLatencyNanos.accumulateAndGet(latencyNanos, Math::max);
    }

    /** Records a request that produced no usable HTTP response. */
    public void recordConnectionFailure(String reason) {
        connectionFailures.increment();
        synchronized (failureReasons) {
            String key = reason;
            if (!failureReasons.containsKey(key) && failureReasons.size() >= MAX_DISTINCT_FAILURE_REASONS) {
                key = OTHER_REASON;
            }
            failureReasons.merge(key, 1L, Long::sum);
        }
    }

    /**
     * Returns the totals so far. The counters are not read atomically as a group, so take the
     * snapshot after all recording has finished if you need exact totals.
     */
    public Snapshot snapshot() {
        long responses = successes.sum() + httpFailures.sum();
        Map<Integer, Long> statuses = new TreeMap<>();
        statusCounts.forEach((code, count) -> statuses.put(code, count.sum()));
        Map<String, Long> reasons;
        synchronized (failureReasons) {
            reasons = new TreeMap<>(failureReasons);
        }
        return new Snapshot(
                successes.sum(),
                httpFailures.sum(),
                connectionFailures.sum(),
                responses == 0 ? 0 : minLatencyNanos.get(),
                responses == 0 ? 0 : maxLatencyNanos.get(),
                totalLatencyNanos.sum(),
                Collections.unmodifiableMap(statuses),
                Collections.unmodifiableMap(reasons));
    }

    /** An immutable copy of the totals. */
    public record Snapshot(
            long successes,
            long httpFailures,
            long connectionFailures,
            long minLatencyNanos,
            long maxLatencyNanos,
            long totalLatencyNanos,
            Map<Integer, Long> statusCounts,
            Map<String, Long> failureReasons
    ) {
        /** Requests that received a complete HTTP response, whatever its status. */
        public long responses() {
            return successes + httpFailures;
        }

        /** All finished requests: responses plus connection/protocol failures. */
        public long completed() {
            return responses() + connectionFailures;
        }

        /** Average latency of requests that received a response; 0 if there were none. */
        public double averageLatencyNanos() {
            long count = responses();
            return count == 0 ? 0.0 : (double) totalLatencyNanos / count;
        }
    }
}
