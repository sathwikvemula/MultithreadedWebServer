package com.sathwik.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RouterTest {

    private static HttpRequest request(String method, String path) {
        return new HttpRequest(method, path, "HTTP/1.1", Map.of("Host", "localhost"), Map.of(), "");
    }

    private static Router usersRouter() {
        return new Router()
                .get("/users", r -> HttpResponse.text(HttpStatus.OK, "list"))
                .post("/users", r -> HttpResponse.text(HttpStatus.CREATED, "created"));
    }

    @Test
    void dispatchesToTheMatchingHandler() {
        Router router = usersRouter();
        assertEquals("list", router.route(request("GET", "/users")).getBody());
        assertEquals(201, router.route(request("POST", "/users")).getStatusCode());
    }

    @Test
    void passesTheRequestToTheHandler() {
        Router router = new Router().get("/echo", r -> HttpResponse.text(HttpStatus.OK, r.getPath() + r.getMethod()));
        assertEquals("/echoGET", router.route(request("GET", "/echo")).getBody());
    }

    @Test
    void unknownPathGives404() {
        HttpResponse response = usersRouter().route(request("GET", "/nope"));
        assertEquals(404, response.getStatusCode());
        assertEquals("Route not found", response.getBody());
        assertNull(response.getHeaders().get("Allow"));
    }

    @Test
    void knownPathWithWrongMethodGives405AndAllowHeader() {
        HttpResponse response = usersRouter().route(request("DELETE", "/users"));
        assertEquals(405, response.getStatusCode());
        assertEquals("HTTP method not allowed for this route", response.getBody());
        assertEquals("GET, POST", response.getHeaders().get("Allow"));
    }

    @Test
    void allowHeaderIsSortedRegardlessOfRegistrationOrder() {
        Router router = new Router()
                .post("/x", r -> HttpResponse.text(HttpStatus.OK, ""))
                .get("/x", r -> HttpResponse.text(HttpStatus.OK, ""))
                .add("DELETE", "/x", r -> HttpResponse.text(HttpStatus.OK, ""));
        assertEquals("DELETE, GET, POST", router.route(request("PUT", "/x")).getHeaders().get("Allow"));
    }

    @Test
    void methodsAreCaseSensitive() {
        assertEquals(405, usersRouter().route(request("get", "/users")).getStatusCode());
    }

    @Test
    void headAndOptionsAreNotHandledAutomatically() {
        Router router = usersRouter();
        assertEquals(405, router.route(request("HEAD", "/users")).getStatusCode());
        assertEquals(405, router.route(request("OPTIONS", "/users")).getStatusCode());
    }

    @Test
    void pathsMustMatchExactly() {
        Router router = usersRouter();
        assertEquals(404, router.route(request("GET", "/users/")).getStatusCode());
        assertEquals(404, router.route(request("GET", "/Users")).getStatusCode());
        assertEquals(404, router.route(request("GET", "/")).getStatusCode());
    }

    @Test
    void emptyRouterAnswers404() {
        assertEquals(404, new Router().route(request("GET", "/anything")).getStatusCode());
    }

    @Test
    void routesCanBeAddedAfterwards() {
        Router router = new Router();
        router.get("/late", r -> HttpResponse.text(HttpStatus.OK, "late"));
        assertEquals("late", router.route(request("GET", "/late")).getBody());
    }

    @Test
    void rejectsDuplicateRoutes() {
        Router router = usersRouter();
        assertThrows(IllegalArgumentException.class,
                () -> router.get("/users", r -> HttpResponse.text(HttpStatus.OK, "again")));
    }

    @Test
    void rejectsInvalidRegistrations() {
        Router router = new Router();
        RouteHandler handler = r -> HttpResponse.text(HttpStatus.OK, "");
        assertThrows(IllegalArgumentException.class, () -> router.add("BAD METHOD", "/x", handler));
        assertThrows(IllegalArgumentException.class, () -> router.add("", "/x", handler));
        assertThrows(IllegalArgumentException.class, () -> router.add("GET", "no-slash", handler));
        assertThrows(IllegalArgumentException.class, () -> router.add("GET", null, handler));
        assertThrows(NullPointerException.class, () -> router.add("GET", "/x", null));
    }
}
