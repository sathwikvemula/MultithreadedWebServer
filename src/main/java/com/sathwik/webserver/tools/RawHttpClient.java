package com.sathwik.webserver.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A small HTTP/1.1 client built directly on {@link Socket}, used by {@link Client} and
 * {@link LoadTest}. It sends exactly one request per connection ({@code Connection: close})
 * and reads exactly one response.
 *
 * <p>Safety properties:
 * <ul>
 *   <li>The request is assembled as bytes: CRLF line endings, {@code Content-Length} counted
 *       in UTF-8 bytes, and the body written as-is (no newline is appended).</li>
 *   <li>Connect, per-read and total time limits apply; a slow server cannot hold the client forever.</li>
 *   <li>Response status line, headers and body are size-limited and parsed strictly.</li>
 *   <li>Socket and streams are always closed (try-with-resources).</li>
 * </ul>
 *
 * <p>Not supported: chunked or other {@code Transfer-Encoding} responses, TLS, redirects,
 * keep-alive. Interim 1xx responses are reported as the response, not skipped.
 *
 * <p>Instances are immutable and thread-safe; {@link #send} may be called concurrently.
 */
public final class RawHttpClient {

    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 5_000;
    public static final int DEFAULT_TOTAL_TIMEOUT_MILLIS = 30_000;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    private static final int MAX_STATUS_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_HEADER_COUNT = 100;
    private static final int MAX_LENGTH_DIGITS = 18;

    private static final Pattern VERSION_PATTERN = Pattern.compile("HTTP/[0-9]\\.[0-9]");
    private static final String TOKEN_SPECIALS = "!#$%&'*+-.^_`|~";

    /** Headers the client writes itself; callers may not supply them. */
    private static final java.util.Set<String> MANAGED_HEADERS =
            java.util.Set.of("host", "content-length", "connection", "transfer-encoding");

    /** The parsed first line of a response. */
    record StatusLine(String version, int statusCode, String reasonPhrase) {
    }

    /** A complete, parsed response. */
    public record Response(
            String version,
            int statusCode,
            String reasonPhrase,
            Map<String, String> headers,
            byte[] body,
            long elapsedNanos
    ) {
        public Response {
            Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            copy.putAll(headers);
            headers = Collections.unmodifiableMap(copy);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /** The body decoded as UTF-8 (malformed sequences become U+FFFD). */
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String statusLine() {
            return reasonPhrase.isEmpty()
                    ? version + " " + statusCode
                    : version + " " + statusCode + " " + reasonPhrase;
        }
    }

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final int totalTimeoutMillis;
    private final int maxResponseBytes;

    public RawHttpClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS,
                DEFAULT_TOTAL_TIMEOUT_MILLIS, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public RawHttpClient(
            int connectTimeoutMillis,
            int readTimeoutMillis,
            int totalTimeoutMillis,
            int maxResponseBytes
    ) {
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1 || totalTimeoutMillis < 1 || maxResponseBytes < 0) {
            throw new IllegalArgumentException("Timeouts must be at least 1 ms and the size limit must not be negative");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Sends one request and reads the response.
     *
     * @param headers extra request headers (may be null); {@code Host}, {@code Content-Length},
     *                {@code Connection} and {@code Transfer-Encoding} are managed by the client
     * @param body    request body, or null for none
     * @throws IllegalArgumentException if the method, path, host or headers are invalid
     * @throws SocketTimeoutException   on connect, read or total timeout
     * @throws ProtocolException        if the response is malformed, truncated or too large
     * @throws IOException              for any other connection failure
     */
    public Response send(
            String host,
            int port,
            String method,
            String path,
            Map<String, String> headers,
            byte[] body
    ) throws IOException {
        byte[] request = buildRequest(host, port, method, path, headers, body);

        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);

            // If the server answers early and closes (for example 413) our write may fail; the
            // response may still be readable, so remember the write error and try to read first.
            IOException writeError = null;
            try {
                OutputStream out = socket.getOutputStream();
                out.write(request);
                out.flush();
            } catch (IOException e) {
                writeError = e;
            }

            try {
                ResponseReader reader = new ResponseReader(
                        socket.getInputStream(), deadlineNanos, totalTimeoutMillis);
                return readResponse(reader, method, startNanos);
            } catch (IOException readError) {
                if (writeError != null) {
                    writeError.addSuppressed(readError);
                    throw writeError;
                }
                throw readError;
            }
        }
    }

    // ------------------------------------------------------------------
    // Request building
    // ------------------------------------------------------------------

    /**
     * Builds the exact bytes of a request. Header order: Host, caller headers,
     * Content-Length (when there is a body, or for POST/PUT/PATCH without one), Connection.
     */
    static byte[] buildRequest(
            String host,
            int port,
            String method,
            String path,
            Map<String, String> headers,
            byte[] body
    ) {
        validateHost(host);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535: " + port);
        }
        validateMethod(method);
        validatePath(path);

        String hostHeader = (host.indexOf(':') >= 0 && !host.startsWith("["))
                ? "[" + host + "]"
                : host;

        StringBuilder head = new StringBuilder(256);
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(hostHeader).append(':').append(port).append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                validateHeader(header.getKey(), header.getValue());
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        if (body != null) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        } else if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            head.append("Content-Length: 0\r\n");
        }
        head.append("Connection: close\r\n\r\n");

        byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (body == null || body.length == 0) {
            return headBytes;
        }
        byte[] all = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, all, 0, headBytes.length);
        System.arraycopy(body, 0, all, headBytes.length, body.length);
        return all;
    }

    static void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank");
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                throw new IllegalArgumentException("Host contains illegal characters: " + printable(host, 60));
            }
        }
    }

    static void validateMethod(String method) {
        if (!isToken(method)) {
            throw new IllegalArgumentException("Invalid HTTP method: " + printable(String.valueOf(method), 40));
        }
    }

    /** The path must be absolute and already percent-encoded (visible ASCII only). */
    static void validatePath(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with '/': " + printable(String.valueOf(path), 60));
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                throw new IllegalArgumentException(
                        "Path must be percent-encoded ASCII without spaces or control characters: "
                                + printable(path, 60));
            }
        }
    }

    static void validateHeader(String name, String value) {
        if (!isToken(name)) {
            throw new IllegalArgumentException("Invalid header name: " + printable(String.valueOf(name), 40));
        }
        if (MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Header is set by the client and cannot be overridden: " + name);
        }
        if (value == null) {
            throw new IllegalArgumentException("Header value must not be null: " + name);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7E)) {
                throw new IllegalArgumentException(
                        "Header value for " + name + " must be printable ASCII (no line breaks or control characters)");
            }
        }
    }

    static boolean isToken(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphanumeric && TOKEN_SPECIALS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Response parsing
    // ------------------------------------------------------------------

    /**
     * Parses {@code HTTP/1.1 200 OK}. The reason phrase may be empty or contain spaces.
     *
     * @throws ProtocolException if the line is not a valid status line
     */
    static StatusLine parseStatusLine(String line) throws ProtocolException {
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            throw malformedStatusLine(line);
        }
        String version = line.substring(0, firstSpace);
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw malformedStatusLine(line);
        }
        String rest = line.substring(firstSpace + 1);
        int secondSpace = rest.indexOf(' ');
        String codeText = secondSpace < 0 ? rest : rest.substring(0, secondSpace);
        String reason = secondSpace < 0 ? "" : rest.substring(secondSpace + 1);

        if (codeText.length() != 3) {
            throw malformedStatusLine(line);
        }
        for (int i = 0; i < 3; i++) {
            char c = codeText.charAt(i);
            if (c < '0' || c > '9') {
                throw malformedStatusLine(line);
            }
        }
        int code = Integer.parseInt(codeText);
        if (code < 100 || code > 599) {
            throw malformedStatusLine(line);
        }
        return new StatusLine(version, code, reason);
    }

    private static ProtocolException malformedStatusLine(String line) {
        return new ProtocolException("Malformed HTTP status line: " + printable(line, 80));
    }

    private Response readResponse(ResponseReader reader, String method, long startNanos) throws IOException {
        String statusText = reader.readLine(MAX_STATUS_LINE_BYTES, "status line");
        if (statusText == null) {
            throw new ProtocolException("Connection closed before any response was received");
        }
        StatusLine status = parseStatusLine(statusText);

        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int headerBytes = 0;
        int headerCount = 0;
        while (true) {
            String line = reader.readLine(MAX_HEADER_LINE_BYTES, "headers");
            if (line == null) {
                throw new ProtocolException("Connection closed before the end of the response headers");
            }
            if (line.isEmpty()) {
                break;
            }
            headerBytes += line.length() + 2;
            headerCount++;
            if (headerBytes > MAX_HEADER_BYTES || headerCount > MAX_HEADER_COUNT) {
                throw new ProtocolException("Response headers are too large");
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || !isToken(line.substring(0, colon))) {
                throw new ProtocolException("Malformed response header: " + printable(line, 80));
            }
            String name = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            String existing = headers.get(name);
            headers.put(name, existing == null ? value : existing + ", " + value);
        }

        byte[] body = readBody(reader, method, status, headers);
        return new Response(
                status.version(),
                status.statusCode(),
                status.reasonPhrase(),
                headers,
                body,
                System.nanoTime() - startNanos);
    }

    private byte[] readBody(
            ResponseReader reader,
            String method,
            StatusLine status,
            Map<String, String> headers
    ) throws IOException {
        int code = status.statusCode();
        if (method.equals("HEAD") || code < 200 || code == 204 || code == 304) {
            return new byte[0];
        }
        String transferEncoding = headers.get("Transfer-Encoding");
        if (transferEncoding != null) {
            throw new ProtocolException(
                    "Transfer-Encoding '" + printable(transferEncoding, 40) + "' responses are not supported");
        }

        String contentLength = headers.get("Content-Length");
        if (contentLength != null) {
            long length = parseLength(contentLength);
            if (length > maxResponseBytes) {
                throw new ProtocolException(
                        "Response body of " + length + " bytes exceeds the limit of " + maxResponseBytes + " bytes");
            }
            byte[] data = new byte[(int) length];
            int received = reader.readFully(data);
            if (received < length) {
                throw new ProtocolException(
                        "Connection closed after " + received + " of " + length + " body bytes");
            }
            return data;
        }

        // No length given: the body ends when the server closes the connection.
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = reader.readSome(chunk)) >= 0) {
            if (collected.size() + count > maxResponseBytes) {
                throw new ProtocolException("Response body exceeds the limit of " + maxResponseBytes + " bytes");
            }
            collected.write(chunk, 0, count);
        }
        return collected.toByteArray();
    }

    private static long parseLength(String value) throws ProtocolException {
        if (value.isEmpty() || value.length() > MAX_LENGTH_DIGITS) {
            throw new ProtocolException("Invalid Content-Length in response: " + printable(value, 30));
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new ProtocolException("Invalid Content-Length in response: " + printable(value, 30));
            }
        }
        return Long.parseLong(value);
    }

    // ------------------------------------------------------------------
    // Error descriptions and safe display
    // ------------------------------------------------------------------

    /** A short, stable, human-readable description of a failure (also used to group failures). */
    public static String describeFailure(IOException e) {
        String message = e.getMessage() == null ? "" : printable(e.getMessage(), 120);
        if (e instanceof SocketTimeoutException) {
            return message.isEmpty() ? "Timed out" : message;
        }
        if (e instanceof UnknownHostException) {
            return "Unknown host: " + message;
        }
        if (e instanceof ProtocolException) {
            return "Protocol error: " + message;
        }
        if (e instanceof java.net.ConnectException) {
            return "Connection failed: " + message;
        }
        if (e instanceof SocketException) {
            return "Socket error: " + message;
        }
        return e.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }

    /** Replaces control characters with '?' and shortens the text, for use inside messages. */
    static String printable(String text, int maxLength) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxLength) + 3);
        for (int i = 0; i < text.length() && out.length() < maxLength; i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        if (out.length() < text.length()) {
            out.append("...");
        }
        return out.toString();
    }

    /**
     * Prepares untrusted server text for a terminal: control characters other than tab,
     * carriage return and line feed are replaced with '?' so a server cannot send terminal
     * escape sequences.
     */
    static String sanitizeForDisplay(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean unsafe = (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F;
            if (unsafe && out == null) {
                out = new StringBuilder(text.length());
                out.append(text, 0, i);
            }
            if (out != null) {
                out.append(unsafe ? '?' : c);
            }
        }
        return out == null ? text : out.toString();
    }

    // ------------------------------------------------------------------
    // Buffered input with an overall deadline
    // ------------------------------------------------------------------

    private static final class ResponseReader {

        private final InputStream in;
        private final long deadlineNanos;
        private final int totalTimeoutMillis;
        private final byte[] buffer = new byte[8192];
        private int position;
        private int limit;

        ResponseReader(InputStream in, long deadlineNanos, int totalTimeoutMillis) {
            this.in = in;
            this.deadlineNanos = deadlineNanos;
            this.totalTimeoutMillis = totalTimeoutMillis;
        }

        /** Reads one line (LF or CRLF terminated) as ISO-8859-1; null if the stream ended first. */
        String readLine(int maxBytes, String what) throws IOException {
            StringBuilder line = new StringBuilder(64);
            while (true) {
                int b = read();
                if (b < 0) {
                    if (line.length() == 0) {
                        return null;
                    }
                    throw new ProtocolException("Connection closed in the middle of the " + what);
                }
                if (b == '\n') {
                    int length = line.length();
                    if (length > 0 && line.charAt(length - 1) == '\r') {
                        line.setLength(length - 1);
                    }
                    if (line.length() > maxBytes) {
                        throw new ProtocolException("The " + what + " is too long");
                    }
                    return line.toString();
                }
                line.append((char) b);
                if (line.length() > maxBytes + 1) {
                    throw new ProtocolException("The " + what + " is too long");
                }
            }
        }

        /** Fills the array as far as possible; returns how many bytes were read. */
        int readFully(byte[] target) throws IOException {
            int offset = 0;
            while (offset < target.length) {
                int count = readSome(target, offset, target.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            return offset;
        }

        int readSome(byte[] target) throws IOException {
            return readSome(target, 0, target.length);
        }

        private int readSome(byte[] target, int offset, int length) throws IOException {
            if (position >= limit && !fill()) {
                return -1;
            }
            int count = Math.min(limit - position, length);
            System.arraycopy(buffer, position, target, offset, count);
            position += count;
            return count;
        }

        private int read() throws IOException {
            if (position >= limit && !fill()) {
                return -1;
            }
            return buffer[position++] & 0xFF;
        }

        private boolean fill() throws IOException {
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new SocketTimeoutException("Total timeout of " + totalTimeoutMillis + " ms exceeded");
            }
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
    }
}
