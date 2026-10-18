package com.sathwik.webserver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles exactly one client connection: parse the request, route it, write one
 * response, close the socket. Runs on a worker thread.
 *
 * <p>Failure handling:
 * <ul>
 *   <li>Malformed, oversized or too-slow requests get the status chosen by the parser
 *       (400, 408, 413, 414, 431, 501, 505).</li>
 *   <li>A route handler that throws, or returns null, produces a generic 500; details
 *       go to the log only.</li>
 *   <li>Connection-level I/O errors (client vanished, reset) are logged quietly.</li>
 *   <li>The socket is always closed, whatever happens.</li>
 * </ul>
 */
final class RequestHandler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(RequestHandler.class.getName());

    /** After an error response, discard at most this much unread input ... */
    static final int ERROR_DRAIN_MAX_BYTES = 64 * 1024;
    /** ... for at most this long, so the client can read the error before the socket closes. */
    static final int ERROR_DRAIN_MAX_MILLIS = 1_000;

    private static final int OUTPUT_BUFFER_BYTES = 8 * 1024;

    private final Socket socket;
    private final HttpRequestParser parser;
    private final Router router;
    private final ServerConfig config;
    private final ScheduledExecutorService watchdog;

    RequestHandler(
            Socket socket,
            HttpRequestParser parser,
            Router router,
            ServerConfig config,
            ScheduledExecutorService watchdog
    ) {
        this.socket = socket;
        this.parser = parser;
        this.router = router;
        this.config = config;
        this.watchdog = watchdog;
    }

    @Override
    public void run() {
        long startNanos = System.nanoTime();
        String summary = "(unparsed request)";
        boolean parseFailed = false;
        try {
            socket.setSoTimeout(config.getReadTimeoutMillis());

            HttpResponse response;
            try {
                HttpRequest request = parser.parse(socket.getInputStream());
                summary = request.getMethod() + " " + request.getPath();
                response = handle(request);
            } catch (HttpParseException e) {
                parseFailed = true;
                response = HttpResponse.text(e.getStatus(), e.getMessage());
            }

            send(response);

            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            LOGGER.log(Level.INFO, "{0} -> {1} ({2} ms)",
                    new Object[] {summary, String.valueOf(response.getStatusCode()), String.valueOf(millis)});
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Connection error for " + summary + ": " + e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Unexpected error while handling a connection", e);
        } finally {
            if (parseFailed) {
                Sockets.drainAndClose(socket, ERROR_DRAIN_MAX_BYTES, ERROR_DRAIN_MAX_MILLIS);
            } else {
                Sockets.closeQuietly(socket);
            }
        }
    }

    private HttpResponse handle(HttpRequest request) {
        try {
            HttpResponse response = router.route(request);
            if (response == null) {
                LOGGER.log(Level.SEVERE, "Route handler returned no response for {0} {1}",
                        new Object[] {request.getMethod(), request.getPath()});
                return internalServerError();
            }
            return response;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE,
                    "Route handler failed for " + request.getMethod() + " " + request.getPath(), e);
            return internalServerError();
        }
    }

    private static HttpResponse internalServerError() {
        return HttpResponse.text(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }

    /**
     * Writes the response. A watchdog closes the socket if writing takes longer than
     * the configured write timeout (for example a client that never reads).
     */
    private void send(HttpResponse response) throws IOException {
        ScheduledFuture<?> guard;
        try {
            guard = watchdog.schedule(
                    () -> Sockets.closeQuietly(socket),
                    config.getWriteTimeoutMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            throw new IOException("Server is shutting down", e);
        }
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), OUTPUT_BUFFER_BYTES);
            response.writeTo(out);
        } finally {
            if (!guard.cancel(false)) {
                LOGGER.log(Level.WARNING, "Writing a response exceeded {0} ms; connection closed",
                        String.valueOf(config.getWriteTimeoutMillis()));
            }
        }
    }
}
