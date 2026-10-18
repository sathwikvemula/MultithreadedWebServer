package com.sathwik.webserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A small multithreaded HTTP/1.1 server.
 *
 * <p>One accept thread hands each connection to a bounded worker pool
 * ({@code poolSize} threads plus a queue of {@code queueCapacity} waiting connections).
 * <b>Overload policy:</b> when the pool and the queue are both full, the accept thread
 * answers {@code 503 Service Unavailable} with {@code Retry-After: 1}, closes the
 * connection and counts it; a connection is never dropped silently.
 *
 * <p>Lifecycle: {@code NEW -> RUNNING -> STOPPING -> STOPPED}. A server can be started
 * once. {@link #stop()} is idempotent and safe to call from any thread, including a
 * JVM shutdown hook.
 *
 * <p><b>Graceful shutdown:</b> stop accepting connections, let in-flight and queued
 * requests finish for up to {@code shutdownTimeoutSeconds}, then force the rest to stop
 * by closing their sockets and interrupting the workers.
 */
public final class Server implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    private static final int OVERLOAD_DRAIN_MAX_BYTES = 64 * 1024;
    /** Pending-connection backlog requested from the OS (the OS may lower it). */
    private static final int LISTEN_BACKLOG = 128;
    private static final long ACCEPT_JOIN_MILLIS = 2_000;
    private static final long FORCED_SHUTDOWN_WAIT_SECONDS = 2;
    private static final long ACCEPT_ERROR_PAUSE_MILLIS = 50;

    private enum State { NEW, RUNNING, STOPPING, STOPPED }

    private final ServerConfig config;
    private final Router router;
    private final HttpRequestParser parser;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private volatile ServerSocket serverSocket;
    private volatile ThreadPoolExecutor workers;
    private volatile ScheduledThreadPoolExecutor watchdog;
    private volatile Thread acceptThread;

    public Server(ServerConfig config, Router router) {
        this.config = Objects.requireNonNull(config, "config");
        this.router = Objects.requireNonNull(router, "router");
        this.parser = new HttpRequestParser(config);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Binds the listening socket and starts accepting connections.
     *
     * @throws IOException           if the address cannot be bound (for example the port is in use)
     * @throws IllegalStateException if the server was already started or stopped
     */
    public synchronized void start() throws IOException {
        if (state.get() != State.NEW) {
            throw new IllegalStateException("Server cannot be started (state: " + state.get() + ")");
        }

        ServerSocket listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(config.getHost(), config.getPort()), LISTEN_BACKLOG);
        } catch (IOException | RuntimeException e) {
            closeQuietly(listener);
            throw e;
        }

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                config.getPoolSize(),
                config.getPoolSize(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()),
                new NamedThreadFactory("worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        ScheduledThreadPoolExecutor timer =
                new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("write-watchdog-"));
        // Without this, cancelled watchdog tasks (one per response) would linger in the queue.
        timer.setRemoveOnCancelPolicy(true);

        this.serverSocket = listener;
        this.workers = pool;
        this.watchdog = timer;
        Thread acceptor = new NamedThreadFactory("accept-").newThread(this::acceptLoop);
        this.acceptThread = acceptor;
        state.set(State.RUNNING);
        acceptor.start();

        LOGGER.log(Level.INFO, "Listening on {0}:{1} (workers={2}, queue={3})",
                new Object[] {config.getHost(), String.valueOf(listener.getLocalPort()),
                        String.valueOf(config.getPoolSize()), String.valueOf(config.getQueueCapacity())});
        if (listener.getInetAddress().isAnyLocalAddress()) {
            LOGGER.warning("Bound to all network interfaces. This is an educational server and is "
                    + "not hardened for untrusted networks.");
        }
    }

    /**
     * Stops the server and waits until it has fully stopped. Safe to call more than once
     * and from several threads; later callers wait for the first to finish.
     */
    public void stop() {
        synchronized (this) {
            if (state.get() == State.NEW) {
                state.set(State.STOPPED);
                stopped.countDown();
                return;
            }
        }
        if (!state.compareAndSet(State.RUNNING, State.STOPPING)) {
            awaitStoppedUninterruptibly();
            return;
        }
        doStop();
    }

    @Override
    public void close() {
        stop();
    }

    /** Blocks until the server has stopped. */
    public void awaitStopped() throws InterruptedException {
        stopped.await();
    }

    private void doStop() {
        LOGGER.info("Stopping server...");
        boolean interrupted = false;
        try {
            closeQuietly(serverSocket);

            Thread acceptor = acceptThread;
            if (acceptor != null && acceptor != Thread.currentThread()) {
                acceptor.join(ACCEPT_JOIN_MILLIS);
            }

            ThreadPoolExecutor pool = workers;
            pool.shutdown();
            if (!pool.awaitTermination(config.getShutdownTimeoutSeconds(), TimeUnit.SECONDS)) {
                LOGGER.log(Level.WARNING,
                        "Graceful shutdown timed out with {0} open connection(s); forcing shutdown",
                        String.valueOf(openSockets.size()));
                forceStop(pool);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            forceStop(workers);
        } finally {
            watchdog.shutdownNow();
            state.set(State.STOPPED);
            stopped.countDown();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        LOGGER.info("Server stopped");
    }

    /** Interrupts workers and closes every open connection, which unblocks stuck socket I/O. */
    private void forceStop(ThreadPoolExecutor pool) {
        pool.shutdownNow();
        for (Socket socket : openSockets) {
            Sockets.closeQuietly(socket);
        }
        try {
            if (!pool.awaitTermination(FORCED_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.severe("Worker threads did not terminate after forced shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        openSockets.clear();
    }

    private void awaitStoppedUninterruptibly() {
        boolean interrupted = false;
        while (true) {
            try {
                stopped.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Accepting and dispatching connections
    // ------------------------------------------------------------------

    private void acceptLoop() {
        ServerSocket listener = serverSocket;
        while (state.get() == State.RUNNING) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (state.get() != State.RUNNING || listener.isClosed()) {
                    break;
                }
                LOGGER.log(Level.WARNING, "Error accepting connection", e);
                try {
                    Thread.sleep(ACCEPT_ERROR_PAUSE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            try {
                dispatch(socket);
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Failed to dispatch a connection", e);
                openSockets.remove(socket);
                Sockets.closeQuietly(socket);
            }
        }
    }

    private void dispatch(Socket socket) {
        openSockets.add(socket);
        RequestHandler handler = new RequestHandler(socket, parser, router, config, watchdog);
        try {
            workers.execute(() -> {
                try {
                    handler.run();
                } finally {
                    openSockets.remove(socket);
                }
            });
        } catch (RejectedExecutionException e) {
            openSockets.remove(socket);
            rejectConnection(socket);
        }
    }

    /**
     * Answers 503 on the accept thread. The response is tiny and the socket is brand new,
     * so the write cannot block in practice; the client is never waited for.
     */
    private void rejectConnection(Socket socket) {
        boolean shuttingDown = state.get() != State.RUNNING;
        if (!shuttingDown) {
            long total = rejectedConnections.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                LOGGER.log(Level.WARNING,
                        "Overloaded: worker pool and queue are full; {0} connection(s) rejected so far",
                        String.valueOf(total));
            }
        }
        HttpResponse response = HttpResponse.text(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        shuttingDown ? "Server is shutting down" : "Server is overloaded, please retry later")
                .withHeader("Retry-After", "1");
        try {
            response.writeTo(socket.getOutputStream());
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not send 503: " + e.getMessage());
        } finally {
            Sockets.drainAvailableAndClose(socket, OVERLOAD_DRAIN_MAX_BYTES);
        }
    }

    // ------------------------------------------------------------------
    // Observability (also used by the tests)
    // ------------------------------------------------------------------

    /** The port the server is actually listening on (useful when configured with port 0). */
    public int getPort() {
        ServerSocket listener = serverSocket;
        if (listener == null) {
            throw new IllegalStateException("Server has not been started");
        }
        return listener.getLocalPort();
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /** Connections accepted and not yet finished (being handled or waiting in the queue). */
    public int getActiveConnections() {
        return openSockets.size();
    }

    /** Connections answered with 503 because the pool and queue were full. */
    public long getRejectedConnections() {
        return rejectedConnections.get();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void closeQuietly(ServerSocket listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.close();
        } catch (IOException ignored) {
            // Nothing useful can be done if closing fails.
        }
    }

    /** Creates daemon threads with readable names and logs anything that escapes a thread. */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failed, error) ->
                    LOGGER.log(Level.SEVERE, "Uncaught error in thread " + failed.getName(), error));
            return thread;
        }
    }
}
