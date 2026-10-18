package com.sathwik.webserver;

/**
 * The demonstration routes served by {@link Main}. Their status codes and response
 * bodies are the same as in the original project.
 *
 * <table>
 *   <caption>Demo routes</caption>
 *   <tr><td>GET /hello</td><td>200, fixed greeting</td></tr>
 *   <tr><td>GET /health</td><td>200, fixed status text</td></tr>
 *   <tr><td>GET /users</td><td>200, a <b>fixed, hard-coded</b> list of three names</td></tr>
 *   <tr><td>POST /users</td><td>201, <b>stub</b>: echoes the request body back</td></tr>
 * </table>
 *
 * <p><b>POST /users is a stub.</b> It does not parse or validate the body, does not
 * store anything, and has no effect on what {@code GET /users} returns. It exists to
 * demonstrate request-body handling.
 */
public final class DemoRoutes {

    static final String HELLO_BODY = "Hello from my Java web server!";
    static final String HEALTH_BODY = "Server is healthy";
    static final String USERS_BODY = "User 1\nUser 2\nUser 3";
    static final String USER_CREATED_PREFIX = "User created successfully: ";

    private DemoRoutes() {
    }

    /** Creates a new router containing only the demo routes. */
    public static Router create() {
        return register(new Router());
    }

    /** Adds the demo routes to an existing router and returns it. */
    public static Router register(Router router) {
        return router
                .get("/hello", request -> HttpResponse.text(HttpStatus.OK, HELLO_BODY))
                .get("/health", request -> HttpResponse.text(HttpStatus.OK, HEALTH_BODY))
                .get("/users", request -> HttpResponse.text(HttpStatus.OK, USERS_BODY))
                .post("/users", request -> HttpResponse.text(
                        HttpStatus.CREATED, USER_CREATED_PREFIX + request.getBody()));
    }
}
