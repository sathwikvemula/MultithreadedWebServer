package com.sathwik.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable HTTP response. The body is encoded to UTF-8 bytes exactly once, so
 * the {@code Content-Length} header always matches the bytes that are written.
 */
public final class HttpResponse {

    /** Headers the server writes itself; callers may not override them. */
    private static final Set<String> MANAGED_HEADERS = Set.of(
            "content-length", "content-type", "connection",
            "transfer-encoding", "x-content-type-options");

    private final int statusCode;
    private final String statusMessage;
    private final String contentType;
    private final String body;
    private final byte[] bodyBytes;
    private final Map<String, String> headers;

    public HttpResponse(
            int statusCode,
            String statusMessage,
            String contentType,
            String body
    ) {
        this(statusCode, statusMessage, contentType, body, Map.of());
    }

    private HttpResponse(
            int statusCode,
            String statusMessage,
            String contentType,
            String body,
            Map<String, String> headers
    ) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("Invalid status code: " + statusCode);
        }
        this.statusCode = statusCode;
        this.statusMessage = requireHeaderSafe("status message", statusMessage);
        this.contentType = withCharset(requireHeaderSafe("content type", contentType));
        this.body = body == null ? "" : body;
        this.bodyBytes = this.body.getBytes(StandardCharsets.UTF_8);
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /** A plain-text response using the standard reason phrase for the status. */
    public static HttpResponse text(HttpStatus status, String body) {
        Objects.requireNonNull(status, "status");
        return new HttpResponse(status.code(), status.reason(), "text/plain", body);
    }

    /** Returns a copy of this response with one extra header (for example {@code Allow}). */
    public HttpResponse withHeader(String name, String value) {
        if (!HttpSyntax.isToken(name)) {
            throw new IllegalArgumentException("Invalid header name: " + name);
        }
        if (MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Header is managed by the server: " + name);
        }
        requireHeaderSafe("header value", value);

        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        copy.put(name, value);
        return new HttpResponse(statusCode, statusMessage, contentType, body, copy);
    }
   /**
 * Returns an equivalent response with an empty body.
 */
public HttpResponse withoutBody() {
    return new HttpResponse(
            statusCode,
            statusMessage,
            contentType,
            "",
            headers
    );
}

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusLine() {
        return "HTTP/1.1 " + statusCode + " " + statusMessage;
    }

    public String getContentType() {
        return contentType;
    }

    public String getBody() {
        return body;
    }

    public byte[] getBodyBytes() {
        return bodyBytes.clone();
    }

    /** The body length in bytes (not characters). */
    public int getContentLength() {
        return bodyBytes.length;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Writes the complete response using CRLF line endings, then flushes.
     * The connection is always closed after one response ({@code Connection: close}).
     */
    public void writeTo(OutputStream out) throws IOException {
        StringBuilder head = new StringBuilder(160);
        head.append(getStatusLine()).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("X-Content-Type-Options: nosniff\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bodyBytes);
        out.flush();
    }

    private static String requireHeaderSafe(String label, String value) {
        Objects.requireNonNull(value, label);
        if (!HttpSyntax.isHeaderSafe(value)) {
            throw new IllegalArgumentException(label + " contains illegal characters");
        }
        return value;
    }

    private static String withCharset(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.startsWith("text/") && !lower.contains("charset=")) {
            return contentType + "; charset=utf-8";
        }
        return contentType;
    }
}
