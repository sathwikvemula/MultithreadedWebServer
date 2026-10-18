package com.sathwik.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpRequestParserTest {

    private final HttpRequestParser parser = new HttpRequestParser(ServerConfig.defaults());

    private HttpRequest parse(String raw) throws Exception {
        return parse(parser, raw.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest parse(HttpRequestParser p, byte[] raw) throws Exception {
        return p.parse(new ByteArrayInputStream(raw));
    }

    private HttpParseException failure(String raw) {
        return failure(parser, raw);
    }

    private HttpParseException failure(HttpRequestParser p, String raw) {
        return assertThrows(HttpParseException.class,
                () -> p.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
    }

    private static String get(String target) {
        return "GET " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
    }

    private static String post(String headers, String body) {
        return "POST /users HTTP/1.1\r\nHost: localhost\r\n" + headers + "\r\n" + body;
    }

    // ---- happy paths -------------------------------------------------

    @Test
    void parsesSimpleGetRequest() throws Exception {
        HttpRequest request = parse(get("/hello"));
        assertEquals("GET", request.getMethod());
        assertEquals("/hello", request.getPath());
        assertEquals("HTTP/1.1", request.getVersion());
        assertEquals("localhost", request.getHeader("Host"));
        assertEquals("", request.getBody());
        assertTrue(request.getQueryParameters().isEmpty());
    }

    @Test
    void acceptsBareLineFeedLineEndings() throws Exception {
        HttpRequest request = parse("GET /health HTTP/1.1\nHost: localhost\n\n");
        assertEquals("/health", request.getPath());
    }

    @Test
    void headerLookupIsCaseInsensitive() throws Exception {
        HttpRequest request = parse(
                "GET /hello HTTP/1.1\r\nHOST: localhost\r\ncontent-type: text/plain\r\n\r\n");
        assertEquals("text/plain", request.getHeader("Content-Type"));
        assertEquals("localhost", request.getHeader("host"));
        assertNull(request.getHeader("X-Missing"));
    }

    @Test
    void trimsHeaderValueWhitespace() throws Exception {
        HttpRequest request = parse(
                "GET /hello HTTP/1.1\r\nHost:   localhost  \r\nX-Test:\t value \t\r\n\r\n");
        assertEquals("localhost", request.getHeader("Host"));
        assertEquals("value", request.getHeader("X-Test"));
    }

    @Test
    void combinesDuplicateListHeaders() throws Exception {
        HttpRequest request = parse(
                "GET /hello HTTP/1.1\r\nHost: localhost\r\nAccept: a\r\nAccept: b\r\n\r\n");
        assertEquals("a, b", request.getHeader("Accept"));
    }

    @Test
    void parsesAndDecodesQueryParameters() throws Exception {
        HttpRequest request = parse(get("/search?name=Jos%C3%A9&q=a+b&flag&x=1&x=2&=skipped&&"));
        assertEquals("/search", request.getPath());
        assertEquals("Jos\u00e9", request.getQueryParameter("name"));
        assertEquals("a b", request.getQueryParameter("q"));
        assertEquals("", request.getQueryParameter("flag"));
        assertEquals("2", request.getQueryParameter("x"));
        assertEquals(4, request.getQueryParameters().size());
    }

    @Test
    void decodesPercentEncodedPathButKeepsPlusSigns() throws Exception {
        assertEquals("/a b", parse(get("/a%20b")).getPath());
        assertEquals("/a+b", parse(get("/a+b")).getPath());
    }

    @Test
    void queryStringDoesNotChangeThePath() throws Exception {
        assertEquals("/users", parse(get("/users?page=2")).getPath());
    }

    @Test
    void readsBodyUsingByteLengthForMultiByteUtf8() throws Exception {
        String text = "h\u00e9llo \u20ac";
        byte[] bodyBytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(bodyBytes.length > text.length());
        HttpRequest request = parse(post("Content-Length: " + bodyBytes.length + "\r\n", text));
        assertEquals(text, request.getBody());
    }

    @Test
    void bodyIsEmptyWhenNoContentLengthIsSent() throws Exception {
        assertEquals("", parse(post("", "ignored")).getBody());
    }

    @Test
    void acceptsBodyExactlyAtTheLimit() throws Exception {
        HttpRequestParser small = new HttpRequestParser(ServerConfig.builder().maxBodyBytes(5).build());
        HttpRequest request = parse(small, post("Content-Length: 5\r\n", "12345").getBytes(StandardCharsets.UTF_8));
        assertEquals("12345", request.getBody());
    }

    @Test
    void parsedRequestCannotBeModified() throws Exception {
        HttpRequest request = parse(get("/hello?a=1"));
        assertThrows(UnsupportedOperationException.class, () -> request.getHeaders().put("X", "y"));
        assertThrows(UnsupportedOperationException.class, () -> request.getQueryParameters().put("b", "2"));
    }

    @Test
    void toStringDoesNotLeakHeadersOrBody() throws Exception {
        HttpRequest request = parse(post("Authorization: secret-token\r\nContent-Length: 6\r\n", "hidden"));
        String text = request.toString();
        assertFalse(text.contains("secret-token"));
        assertFalse(text.contains("hidden"));
    }

    // ---- request line ------------------------------------------------

    @Test
    void rejectsEmptyRequest() {
        HttpParseException e = failure("");
        assertEquals(400, e.getStatus().code());
        assertEquals("Empty request", e.getMessage());
    }

    @Test
    void rejectsBlankRequestLine() {
        assertEquals("Empty request", failure("   \r\n\r\n").getMessage());
    }

    @Test
    void rejectsMalformedRequestLines() {
        assertEquals(400, failure("GET /hello\r\nHost: x\r\n\r\n").getStatus().code());
        assertEquals(400, failure("GET  /hello HTTP/1.1\r\nHost: x\r\n\r\n").getStatus().code());
        assertEquals(400, failure("GET /hello HTTP/1.1 extra\r\nHost: x\r\n\r\n").getStatus().code());
        assertEquals("Invalid HTTP request line", failure("GET /hello\r\n\r\n").getMessage());
    }

    @Test
    void rejectsInvalidMethod() {
        assertEquals("Invalid HTTP method", failure("G(T /hello HTTP/1.1\r\nHost: x\r\n\r\n").getMessage());
    }

    @Test
    void rejectsPathThatDoesNotStartWithSlash() {
        assertEquals("Invalid request path", failure("GET hello HTTP/1.1\r\nHost: x\r\n\r\n").getMessage());
        assertEquals("Invalid request path", failure("GET * HTTP/1.1\r\nHost: x\r\n\r\n").getMessage());
    }

    @Test
    void rejectsNonAsciiBytesInRequestLine() {
        assertEquals(400, failure("GET /caf\u00e9 HTTP/1.1\r\nHost: x\r\n\r\n").getStatus().code());
    }

    @Test
    void rejectsUnsupportedHttpVersionWith505() {
        HttpParseException e = failure("GET /hello HTTP/1.0\r\nHost: x\r\n\r\n");
        assertEquals(505, e.getStatus().code());
        assertEquals("Unsupported HTTP version", e.getMessage());
        assertEquals(505, failure("GET /hello HTTP/2.0\r\nHost: x\r\n\r\n").getStatus().code());
    }

    @Test
    void rejectsMalformedHttpVersionWith400() {
        assertEquals(400, failure("GET /hello HTTP/x\r\nHost: x\r\n\r\n").getStatus().code());
        assertEquals(400, failure("GET /hello FTP/1.1\r\nHost: x\r\n\r\n").getStatus().code());
    }

    @Test
    void rejectsOverlongRequestLineWith414() {
        HttpRequestParser small = new HttpRequestParser(ServerConfig.builder().maxRequestLineBytes(32).build());
        HttpParseException e = failure(small, get("/" + "a".repeat(100)));
        assertEquals(414, e.getStatus().code());
    }

    @Test
    void rejectsInvalidPercentEncoding() {
        assertEquals(400, failure(get("/a%zz")).getStatus().code());
        assertEquals(400, failure(get("/a%2")).getStatus().code());
        assertEquals(400, failure(get("/a%")).getStatus().code());
        assertEquals(400, failure(get("/a%ff")).getStatus().code());
        assertEquals(400, failure(get("/ok?q=%zz")).getStatus().code());
    }

    @Test
    void rejectsEncodedControlCharactersInPath() {
        assertEquals("Invalid request path", failure(get("/a%00b")).getMessage());
        assertEquals("Invalid request path", failure(get("/a%0Ab")).getMessage());
    }

    // ---- headers -----------------------------------------------------

    @Test
    void rejectsHeaderWithoutColon() {
        assertEquals("Invalid HTTP header",
                failure("GET /hello HTTP/1.1\r\nHost: x\r\nBroken\r\n\r\n").getMessage());
        assertEquals("Invalid HTTP header",
                failure("GET /hello HTTP/1.1\r\nHost: x\r\n: value\r\n\r\n").getMessage());
    }

    @Test
    void rejectsWhitespaceBeforeHeaderColon() {
        assertEquals("Invalid HTTP header name",
                failure("GET /hello HTTP/1.1\r\nHost : x\r\n\r\n").getMessage());
    }

    @Test
    void rejectsObsoleteLineFolding() {
        assertEquals(400, failure("GET /hello HTTP/1.1\r\nHost: x\r\n folded\r\n\r\n").getStatus().code());
    }

    @Test
    void rejectsControlCharactersInHeaderValue() {
        assertEquals(400, failure("GET /hello HTTP/1.1\r\nHost: x\u0001y\r\n\r\n").getStatus().code());
    }

    @Test
    void rejectsMissingHostHeader() {
        HttpParseException e = failure("GET /hello HTTP/1.1\r\nAccept: */*\r\n\r\n");
        assertEquals(400, e.getStatus().code());
        assertEquals("Missing Host header", e.getMessage());
    }

    @Test
    void rejectsDuplicateHostAndContentLength() {
        assertEquals(400, failure("GET /hello HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n").getStatus().code());
        assertEquals(400, failure(post("Content-Length: 1\r\nContent-Length: 1\r\n", "x")).getStatus().code());
    }

    @Test
    void rejectsRequestEndingBeforeBlankLine() {
        assertEquals(400, failure("GET /hello HTTP/1.1\r\nHost: x\r\n").getStatus().code());
        assertEquals(400, failure("GET /hello HTTP/1.1\r\nHost: x").getStatus().code());
    }

    @Test
    void rejectsTooManyHeadersWith431() {
        HttpRequestParser small = new HttpRequestParser(ServerConfig.builder().maxHeaderCount(3).build());
        String raw = "GET /hello HTTP/1.1\r\nHost: x\r\nA: 1\r\nB: 2\r\nC: 3\r\n\r\n";
        assertEquals(431, failure(small, raw).getStatus().code());
    }

    @Test
    void rejectsOversizedHeaderBlockWith431() {
        HttpRequestParser small = new HttpRequestParser(ServerConfig.builder().maxHeaderBytes(40).build());
        String raw = "GET /hello HTTP/1.1\r\nHost: x\r\nA: 1234567890\r\nB: 1234567890\r\nC: 1234567890\r\n\r\n";
        assertEquals(431, failure(small, raw).getStatus().code());
    }

    @Test
    void rejectsOverlongSingleHeaderLineWith431() {
        HttpRequestParser small = new HttpRequestParser(ServerConfig.builder().maxHeaderBytes(40).build());
        String raw = "GET /hello HTTP/1.1\r\nHost: x\r\nA: " + "z".repeat(200) + "\r\n\r\n";
        assertEquals(431, failure(small, raw).getStatus().code());
    }

    // ---- body --------------------------------------------------------

    @Test
    void rejectsInvalidContentLength() {
        assertEquals("Invalid Content-Length", failure(post("Content-Length: abc\r\n", "")).getMessage());
        assertEquals("Invalid Content-Length", failure(post("Content-Length: 1 2\r\n", "")).getMessage());
        assertEquals("Invalid Content-Length", failure(post("Content-Length: +5\r\n", "")).getMessage());
        assertEquals("Invalid Content-Length", failure(post("Content-Length: \r\n", "")).getMessage());
        assertEquals("Invalid Content-Length", failure(post("Content-Length: 99999999999\r\n", "")).getMessage());
    }

    @Test
    void rejectsNegativeContentLength() {
        assertEquals("Content-Length cannot be negative", failure(post("Content-Length: -5\r\n", "")).getMessage());
    }

    @Test
    void rejectsOversizedBodyWith413BeforeReadingIt() {
        HttpParseException e = failure(post("Content-Length: 2000000\r\n", "tiny"));
        assertEquals(413, e.getStatus().code());
        assertEquals(413, failure(post("Content-Length: 2000000000\r\n", "")).getStatus().code());
    }

    @Test
    void rejectsBodyOneByteOverTheLimit() {
        HttpRequestParser small = new HttpRequestParser(ServerConfig.builder().maxBodyBytes(5).build());
        assertEquals(413, failure(small, post("Content-Length: 6\r\n", "123456")).getStatus().code());
    }

    @Test
    void rejectsIncompleteBody() {
        HttpParseException e = failure(post("Content-Length: 10\r\n", "short"));
        assertEquals(400, e.getStatus().code());
        assertEquals("Request body is incomplete", e.getMessage());
    }

    @Test
    void rejectsBodyThatIsNotValidUtf8() {
        byte[] head = "POST /users HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[head.length + 2];
        System.arraycopy(head, 0, raw, 0, head.length);
        raw[head.length] = (byte) 0xC3;
        raw[head.length + 1] = (byte) 0x28;
        HttpParseException e = assertThrows(HttpParseException.class, () -> parse(parser, raw));
        assertEquals(400, e.getStatus().code());
    }

    @Test
    void rejectsTransferEncoding() {
        HttpParseException e = failure(post("Transfer-Encoding: chunked\r\n", "0\r\n\r\n"));
        assertEquals(501, e.getStatus().code());
        assertEquals(400, failure(post("Transfer-Encoding: chunked\r\nContent-Length: 5\r\n", "0\r\n\r\n")).getStatus().code());
    }

    // ---- time limits -------------------------------------------------

    @Test
    void mapsSocketTimeoutTo408() {
        InputStream stalled = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new SocketTimeoutException("Read timed out");
            }
        };
        HttpParseException e = assertThrows(HttpParseException.class, () -> parser.parse(stalled));
        assertEquals(408, e.getStatus().code());
    }

    @Test
    void cutsOffClientsThatTrickleBytesSlowly() {
        HttpRequestParser strict = new HttpRequestParser(ServerConfig.builder().requestTimeoutMillis(60).build());
        byte[] data = get("/hello").getBytes(StandardCharsets.US_ASCII);
        InputStream slow = new InputStream() {
            private int index;

            @Override
            public int read() throws IOException {
                return index < data.length ? data[index++] : -1;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                int value = read();
                if (value < 0) {
                    return -1;
                }
                target[offset] = (byte) value;
                return 1;
            }
        };
        HttpParseException e = assertThrows(HttpParseException.class, () -> strict.parse(slow));
        assertEquals(408, e.getStatus().code());
    }

    // ---- helpers under test ------------------------------------------

    @Test
    void percentDecodeHandlesPlusAccordingToContext() throws Exception {
        assertEquals("a b", HttpRequestParser.percentDecode("a+b", true));
        assertEquals("a+b", HttpRequestParser.percentDecode("a+b", false));
        assertEquals("\u20ac", HttpRequestParser.percentDecode("%E2%82%AC", false));
    }
}
