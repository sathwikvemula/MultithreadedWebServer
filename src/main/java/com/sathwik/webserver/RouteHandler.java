package com.sathwik.webserver;

/**
 * Handles one routed request and produces its response.
 *
 * <p>Handlers run concurrently on worker threads, so they must not mutate shared
 * state without synchronization. If a handler throws, the server answers 500 and
 * logs the error; the exception details are never sent to the client.
 */
@FunctionalInterface
public interface RouteHandler {

    HttpResponse handle(HttpRequest request);
}
