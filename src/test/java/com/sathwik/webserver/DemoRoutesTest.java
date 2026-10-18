package com.sathwik.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the demo routes to the behavior of the original project. */
class DemoRoutesTest {

    private final Router router = DemoRoutes.create();

    private HttpResponse call(String method, String path, String body) {
        return router.route(new HttpRequest(method, path, "HTTP/1.1", Map.of("Host", "localhost"), Map.of(), body));
    }

    @Test
    void helloRoute() {
        HttpResponse response = call("GET", "/hello", "");
        assertEquals("HTTP/1.1 200 OK", response.getStatusLine());
        assertEquals("Hello from my Java web server!", response.getBody());
        assertEquals("text/plain; charset=utf-8", response.getContentType());
    }

    @Test
    void healthRoute() {
        HttpResponse response = call("GET", "/health", "");
        assertEquals(200, response.getStatusCode());
        assertEquals("Server is healthy", response.getBody());
    }

    @Test
    void usersRouteReturnsTheFixedList() {
        HttpResponse response = call("GET", "/users", "");
        assertEquals(200, response.getStatusCode());
        assertEquals("User 1\nUser 2\nUser 3", response.getBody());
    }

    @Test
    void postUsersIsAnEchoStub() {
        HttpResponse response = call("POST", "/users", "{\"name\":\"Ada\"}");
        assertEquals("HTTP/1.1 201 Created", response.getStatusLine());
        assertEquals("User created successfully: {\"name\":\"Ada\"}", response.getBody());
    }

    @Test
    void postUsersDoesNotChangeWhatGetUsersReturns() {
        call("POST", "/users", "{\"name\":\"Ada\"}");
        assertEquals("User 1\nUser 2\nUser 3", call("GET", "/users", "").getBody());
    }

    @Test
    void postUsersWithEmptyBodyStillEchoes() {
        assertEquals("User created successfully: ", call("POST", "/users", "").getBody());
    }

    @Test
    void unknownPathAndWrongMethodKeepTheOriginalMessages() {
        assertEquals("Route not found", call("GET", "/missing", "").getBody());
        HttpResponse wrongMethod = call("DELETE", "/users", "");
        assertEquals(405, wrongMethod.getStatusCode());
        assertEquals("HTTP method not allowed for this route", wrongMethod.getBody());
        assertEquals("GET, POST", wrongMethod.getHeaders().get("Allow"));
        assertEquals("GET", call("POST", "/hello", "").getHeaders().get("Allow"));
    }

    @Test
    void registerAddsTheDemoRoutesToAnExistingRouter() {
        Router custom = new Router().get("/custom", r -> HttpResponse.text(HttpStatus.OK, "mine"));
        DemoRoutes.register(custom);
        assertEquals("mine", custom.route(new HttpRequest("GET", "/custom", "HTTP/1.1", Map.of(), Map.of(), "")).getBody());
        assertEquals(200, custom.route(new HttpRequest("GET", "/hello", "HTTP/1.1", Map.of(), Map.of(), "")).getStatusCode());
    }
}
