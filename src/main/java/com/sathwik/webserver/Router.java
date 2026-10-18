package com.sathwik.webserver;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Maps (method, path) pairs to {@link RouteHandler}s.
 *
 * <p>Matching rules:
 * <ul>
 *   <li>The path must match exactly (after percent-decoding): {@code /users/} is not {@code /users}.</li>
 *   <li>Methods are case-sensitive, as in HTTP.</li>
 *   <li>Unknown path: 404 {@code Route not found}.</li>
 *   <li>Known path, unregistered method: 405 {@code HTTP method not allowed for this route},
 *       with an {@code Allow} header listing the registered methods.</li>
 *   <li>HEAD and OPTIONS are not handled automatically; they are answered with 405 unless registered.</li>
 * </ul>
 *
 * <p>The router is thread-safe. Routes are normally registered before the server starts,
 * but registering later is also safe.
 */
public final class Router {

    private final Map<String, Map<String, RouteHandler>> routes = new ConcurrentHashMap<>();

    /**
     * Registers a handler.
     *
     * @throws IllegalArgumentException if the method or path is invalid or the route already exists
     */
    public Router add(String method, String path, RouteHandler handler) {
        if (!HttpSyntax.isToken(method)) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/': " + path);
        }
        Objects.requireNonNull(handler, "handler");

        RouteHandler previous = routes
                .computeIfAbsent(path, key -> new ConcurrentSkipListMap<>())
                .putIfAbsent(method, handler);
        if (previous != null) {
            throw new IllegalArgumentException("Route already registered: " + method + " " + path);
        }
        return this;
    }

    public Router get(String path, RouteHandler handler) {
        return add("GET", path, handler);
    }

    public Router post(String path, RouteHandler handler) {
        return add("POST", path, handler);
    }

    /**
     * Finds the handler for the request and calls it. Never returns null for an unknown
     * route; a handler that itself returns null is reported by the caller as a server error.
     */
    public HttpResponse route(HttpRequest request) {
        Map<String, RouteHandler> methods = routes.get(request.getPath());
        if (methods == null) {
            return HttpResponse.text(HttpStatus.NOT_FOUND, "Route not found");
        }
        RouteHandler handler = methods.get(request.getMethod());
        if (handler == null) {
            return HttpResponse.text(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed for this route")
                    .withHeader("Allow", String.join(", ", methods.keySet()));
        }
        return handler.handle(request);
    }
}
