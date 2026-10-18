package com.sathwik.webserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Parses one HTTP/1.1 request from a byte stream.
 *
 * <p>The parser works on bytes (not characters), so {@code Content-Length} is always
 * interpreted as a byte count and bodies are decoded as strict UTF-8. Every size and
 * time limit comes from {@link ServerConfig}. Anything malformed or over a limit is
 * reported as an {@link HttpParseException} carrying the status code to send back.
 *
 * <p>Deliberate limitations: only origin-form targets ({@code /path?query}), no chunked
 * request bodies, no {@code Expect: 100-continue} handling, no pipelining.
 * A parser instance holds no per-request state and can be shared between threads.
 */
public final class HttpRequestParser {

    private static final String SUPPORTED_VERSION = "HTTP/1.1";
    private static final Pattern VERSION_PATTERN = Pattern.compile("HTTP/[0-9]\\.[0-9]");
    private static final Pattern NEGATIVE_NUMBER = Pattern.compile("-[0-9]+");
    private static final int MAX_CONTENT_LENGTH_DIGITS = 10;

    private final ServerConfig config;

    public HttpRequestParser(ServerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Reads and parses one request.
     *
     * @throws HttpParseException if the request is malformed, too large, too slow or unsupported
     * @throws IOException        if the connection fails for a reason other than a read timeout
     */
    public HttpRequest parse(InputStream in) throws IOException, HttpParseException {
        Objects.requireNonNull(in, "in");
        RequestInput input = new RequestInput(in, config.getRequestTimeoutMillis());
        try {
            return parseRequest(input);
        } catch (SocketTimeoutException e) {
            throw new HttpParseException(HttpStatus.REQUEST_TIMEOUT, "Request timed out");
        }
    }

    private HttpRequest parseRequest(RequestInput input) throws IOException, HttpParseException {

        // 1. Request line: METHOD SP target SP version
        String requestLine = readLine(input, config.getMaxRequestLineBytes(),
                HttpStatus.URI_TOO_LONG, "Request line too long");
        if (requestLine == null || requestLine.isBlank()) {
            throw bad("Empty request");
        }
        requireVisibleAscii(requestLine);

        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw bad("Invalid HTTP request line");
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];

        if (!HttpSyntax.isToken(method)) {
            throw bad("Invalid HTTP method");
        }
        if (!target.startsWith("/")) {
            throw bad("Invalid request path");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw bad("Invalid HTTP version");
        }
        if (!SUPPORTED_VERSION.equals(version)) {
            throw new HttpParseException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED, "Unsupported HTTP version");
        }

        // 2. Path and query string
        String rawPath = target;
        String rawQuery = null;
        int questionMark = target.indexOf('?');
        if (questionMark >= 0) {
            rawPath = target.substring(0, questionMark);
            rawQuery = target.substring(questionMark + 1);
        }
        String path = percentDecode(rawPath, false);
        if (containsControlCharacter(path)) {
            throw bad("Invalid request path");
        }
        Map<String, String> queryParameters = parseQuery(rawQuery);

        // 3. Headers
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int headerBytes = 0;
        int headerCount = 0;
        while (true) {
            String line = readLine(input, config.getMaxHeaderBytes(),
                    HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, "Header line too long");
            if (line == null) {
                throw bad("Unexpected end of headers");
            }
            if (line.isEmpty()) {
                break;
            }
            headerBytes += line.length() + 2;
            headerCount++;
            if (headerBytes > config.getMaxHeaderBytes() || headerCount > config.getMaxHeaderCount()) {
                throw new HttpParseException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE,
                        "Request headers too large");
            }
            addHeader(line, headers);
        }

        if (!headers.containsKey("Host")) {
            throw bad("Missing Host header");
        }

        // 4. Body framing
        String transferEncoding = headers.get("Transfer-Encoding");
        String contentLengthHeader = headers.get("Content-Length");
        if (transferEncoding != null) {
            if (contentLengthHeader != null) {
                throw bad("Content-Length and Transfer-Encoding cannot both be present");
            }
            throw new HttpParseException(HttpStatus.NOT_IMPLEMENTED, "Transfer-Encoding is not supported");
        }

        String body = "";
        if (contentLengthHeader != null) {
            long contentLength = parseContentLength(contentLengthHeader);
            if (contentLength > config.getMaxBodyBytes()) {
                throw new HttpParseException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large");
            }
            if (contentLength > 0) {
                body = readBody(input, (int) contentLength);
            }
        }

        return new HttpRequest(method, path, version, headers, queryParameters, body);
    }

    // ------------------------------------------------------------------
    // Headers and body
    // ------------------------------------------------------------------

    private static void addHeader(String line, Map<String, String> headers) throws HttpParseException {
        char first = line.charAt(0);
        if (first == ' ' || first == '\t') {
            throw bad("Obsolete line folding is not supported");
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw bad("Invalid HTTP header");
        }
        String name = line.substring(0, colon);
        if (!HttpSyntax.isToken(name)) {
            // Also rejects whitespace between the name and the colon.
            throw bad("Invalid HTTP header name");
        }
        String value = trimWhitespace(line.substring(colon + 1));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                throw bad("Invalid character in HTTP header value");
            }
        }

        String existing = headers.get(name);
        if (existing == null) {
            headers.put(name, value);
        } else if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Host")) {
            throw bad("Duplicate " + name + " header");
        } else {
            // Repeated list-style headers are equivalent to one comma-separated header.
            headers.put(name, existing + ", " + value);
        }
    }

    private static long parseContentLength(String value) throws HttpParseException {
        if (NEGATIVE_NUMBER.matcher(value).matches()) {
            throw bad("Content-Length cannot be negative");
        }
        if (value.isEmpty() || value.length() > MAX_CONTENT_LENGTH_DIGITS) {
            throw bad("Invalid Content-Length");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw bad("Invalid Content-Length");
            }
        }
        return Long.parseLong(value);
    }

    private static String readBody(RequestInput input, int length) throws IOException, HttpParseException {
        byte[] data = new byte[length];
        if (!input.readFully(data)) {
            throw bad("Request body is incomplete");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException e) {
            throw bad("Request body is not valid UTF-8");
        }
    }

    // ------------------------------------------------------------------
    // Line reading
    // ------------------------------------------------------------------

    /**
     * Reads one line terminated by LF or CRLF. Returns null if the stream ended before
     * any byte was read. Bytes are mapped one-to-one to chars (ISO-8859-1).
     */
    private static String readLine(
            RequestInput input,
            int maxBytes,
            HttpStatus tooLongStatus,
            String tooLongMessage
    ) throws IOException, HttpParseException {
        StringBuilder line = new StringBuilder(64);
        while (true) {
            int b = input.read();
            if (b < 0) {
                if (line.length() == 0) {
                    return null;
                }
                throw bad("Unexpected end of request");
            }
            if (b == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                if (line.length() > maxBytes) {
                    throw new HttpParseException(tooLongStatus, tooLongMessage);
                }
                return line.toString();
            }
            line.append((char) b);
            if (line.length() > maxBytes + 1) {
                throw new HttpParseException(tooLongStatus, tooLongMessage);
            }
        }
    }

    private static void requireVisibleAscii(String requestLine) throws HttpParseException {
        for (int i = 0; i < requestLine.length(); i++) {
            char c = requestLine.charAt(i);
            if (c != ' ' && (c < 0x21 || c > 0x7E)) {
                throw bad("Invalid HTTP request line");
            }
        }
    }

    // ------------------------------------------------------------------
    // Query string and percent-decoding
    // ------------------------------------------------------------------

    private static Map<String, String> parseQuery(String rawQuery) throws HttpParseException {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawKey = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            String key = percentDecode(rawKey, true);
            if (key.isEmpty()) {
                continue;
            }
            // If a key repeats, the last value wins.
            parameters.put(key, percentDecode(rawValue, true));
        }
        return parameters;
    }

    /**
     * Decodes %XX sequences as UTF-8. In query strings a plus sign means a space;
     * in paths it is an ordinary character. The input must be plain ASCII.
     */
    static String percentDecode(String text, boolean plusMeansSpace) throws HttpParseException {
        boolean needsDecoding = text.indexOf('%') >= 0 || (plusMeansSpace && text.indexOf('+') >= 0);
        if (!needsDecoding) {
            return text;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                if (i + 2 >= text.length()) {
                    throw bad("Invalid percent-encoding");
                }
                int high = Character.digit(text.charAt(i + 1), 16);
                int low = Character.digit(text.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw bad("Invalid percent-encoding");
                }
                out.write((high << 4) | low);
                i += 2;
            } else if (c == '+' && plusMeansSpace) {
                out.write(' ');
            } else {
                out.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw bad("Invalid percent-encoding");
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean containsControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static String trimWhitespace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == ' ' || text.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return text.substring(start, end);
    }

    private static HttpParseException bad(String message) {
        return new HttpParseException(HttpStatus.BAD_REQUEST, message);
    }

    // ------------------------------------------------------------------
    // Input with an overall deadline
    // ------------------------------------------------------------------

    /**
     * A small buffered reader that also enforces the overall request deadline. The
     * deadline is checked before every read from the underlying stream, so a client
     * that trickles bytes slowly is cut off after {@code requestTimeoutMillis}
     * (plus at most one socket read timeout).
     */
    private static final class RequestInput {

        private final InputStream in;
        private final long startNanos = System.nanoTime();
        private final long timeoutNanos;
        private final byte[] buffer = new byte[4096];
        private int position;
        private int limit;

        RequestInput(InputStream in, int requestTimeoutMillis) {
            this.in = in;
            this.timeoutNanos = requestTimeoutMillis * 1_000_000L;
        }

        /** Returns the next byte (0-255), or -1 at end of stream. */
        int read() throws IOException, HttpParseException {
            if (position >= limit && !fill()) {
                return -1;
            }
            return buffer[position++] & 0xFF;
        }

        /** Fills the array completely; returns false if the stream ends first. */
        boolean readFully(byte[] target) throws IOException, HttpParseException {
            int offset = 0;
            while (offset < target.length) {
                if (position < limit) {
                    int count = Math.min(limit - position, target.length - offset);
                    System.arraycopy(buffer, position, target, offset, count);
                    position += count;
                    offset += count;
                } else {
                    checkDeadline();
                    int count = in.read(target, offset, target.length - offset);
                    if (count < 0) {
                        return false;
                    }
                    offset += count;
                }
            }
            return true;
        }

        private boolean fill() throws IOException, HttpParseException {
            checkDeadline();
            int count;
            do {
                count = in.read(buffer, 0, buffer.length);
            } while (count == 0);
            if (count < 0) {
                return false;
            }
            position = 0;
            limit = count;
            return true;
        }

        private void checkDeadline() throws HttpParseException {
            if (System.nanoTime() - startNanos >= timeoutNanos) {
                throw new HttpParseException(HttpStatus.REQUEST_TIMEOUT, "Request timed out");
            }
        }
    }
}
