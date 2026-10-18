package com.sathwik.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpResponseTest {

    private static String render(HttpResponse response) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.writeTo(out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void writesExactBytesWithCrLfLineEndings() throws Exception {
        HttpResponse response = new HttpResponse(200, "OK", "text/plain", "hi");
        assertEquals(
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: 2\r\n"
                        + "Connection: close\r\n"
                        + "X-Content-Type-Options: nosniff\r\n"
                        + "\r\n"
                        + "hi",
                render(response));
    }

    @Test
    void doesNotAppendAnythingAfterTheBody() throws Exception {
        String wire = render(new HttpResponse(200, "OK", "text/plain", "Hello"));
        assertTrue(wire.endsWith("\r\n\r\nHello"));
    }

    @Test
    void contentLengthCountsUtf8BytesNotCharacters() throws Exception {
        HttpResponse response = new HttpResponse(200, "OK", "text/plain", "caf\u00e9 \u20ac");
        assertEquals(9, response.getContentLength());
        assertTrue(render(response).contains("Content-Length: 9\r\n"));
    }

    @Test
    void nullBodyBecomesEmpty() throws Exception {
        HttpResponse response = new HttpResponse(204, "No Content", "text/plain", null);
        assertEquals(0, response.getContentLength());
        assertEquals("", response.getBody());
    }

    @Test
    void keepsAnExplicitCharsetAndNonTextTypes() {
        assertEquals("text/plain; charset=iso-8859-1",
                new HttpResponse(200, "OK", "text/plain; charset=iso-8859-1", "x").getContentType());
        assertEquals("application/json",
                new HttpResponse(200, "OK", "application/json", "{}").getContentType());
    }

    @Test
    void textFactoryUsesStandardReasonPhrase() {
        HttpResponse response = HttpResponse.text(HttpStatus.NOT_FOUND, "Route not found");
        assertEquals("HTTP/1.1 404 Not Found", response.getStatusLine());
        assertEquals(404, response.getStatusCode());
    }

    @Test
    void withHeaderAddsHeaderAndLeavesOriginalUntouched() throws Exception {
        HttpResponse original = HttpResponse.text(HttpStatus.METHOD_NOT_ALLOWED, "no");
        HttpResponse withAllow = original.withHeader("Allow", "GET, POST");
        assertTrue(render(withAllow).contains("Allow: GET, POST\r\n"));
        assertTrue(original.getHeaders().isEmpty());
    }

    @Test
    void withHeaderReplacesExistingHeaderIgnoringCase() {
        HttpResponse response = HttpResponse.text(HttpStatus.OK, "x")
                .withHeader("Allow", "GET")
                .withHeader("allow", "POST");
        assertEquals(1, response.getHeaders().size());
        assertEquals("POST", response.getHeaders().get("allow"));
    }

    @Test
    void rejectsHeaderInjection() {
        HttpResponse response = HttpResponse.text(HttpStatus.OK, "x");
        assertThrows(IllegalArgumentException.class, () -> response.withHeader("X-Test", "a\r\nSet-Cookie: evil=1"));
        assertThrows(IllegalArgumentException.class, () -> response.withHeader("Bad Name", "v"));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponse(200, "OK\r\nX: y", "text/plain", "x"));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponse(200, "OK", "text/plain\r\nX: y", "x"));
    }

    @Test
    void rejectsHeadersTheServerManagesItself() {
        HttpResponse response = HttpResponse.text(HttpStatus.OK, "x");
        assertThrows(IllegalArgumentException.class, () -> response.withHeader("Content-Length", "1"));
        assertThrows(IllegalArgumentException.class, () -> response.withHeader("connection", "keep-alive"));
    }

    @Test
    void rejectsInvalidStatusCode() {
        assertThrows(IllegalArgumentException.class, () -> new HttpResponse(99, "x", "text/plain", ""));
        assertThrows(IllegalArgumentException.class, () -> new HttpResponse(600, "x", "text/plain", ""));
    }

    @Test
    void bodyBytesAreDefensiveCopies() {
        HttpResponse response = HttpResponse.text(HttpStatus.OK, "abc");
        response.getBodyBytes()[0] = 'z';
        assertEquals("abc", new String(response.getBodyBytes(), StandardCharsets.UTF_8));
    }
}
