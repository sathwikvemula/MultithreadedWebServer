package com.sathwik.webserver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An immutable, parsed HTTP request.
 *
 * <p>Header names are case-insensitive ({@code getHeader("content-type")} finds
 * {@code Content-Type}). The maps returned by the getters cannot be modified.
 */
public final class HttpRequest {

    private final String method;
    private final String path;
    private final String version;
    private final Map<String, String> headers;
    private final Map<String, String> queryParameters;
    private final String body;

    public HttpRequest(
            String method,
            String path,
            String version,
            Map<String, String> headers,
            Map<String, String> queryParameters,
            String body
    ) {
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.version = Objects.requireNonNull(version, "version");

        Map<String, String> headerCopy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headerCopy.putAll(headers);
        }
        this.headers = Collections.unmodifiableMap(headerCopy);

        Map<String, String> queryCopy = new LinkedHashMap<>();
        if (queryParameters != null) {
            queryCopy.putAll(queryParameters);
        }
        this.queryParameters = Collections.unmodifiableMap(queryCopy);

        this.body = body == null ? "" : body;
    }

    public String getMethod() {
        return method;
    }

    /** The percent-decoded path, without the query string. */
    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    /** Case-insensitive header lookup; returns null if the header is absent. */
    public String getHeader(String name) {
        return name == null ? null : headers.get(name);
    }

    public Map<String, String> getQueryParameters() {
        return queryParameters;
    }

    public String getQueryParameter(String name) {
        return queryParameters.get(name);
    }

    /** The request body decoded as UTF-8; empty if the request had no body. */
    public String getBody() {
        return body;
    }

    /** Deliberately omits headers and body so secrets do not end up in logs. */
    @Override
    public String toString() {
        return "HttpRequest{method='" + method + '\''
                + ", path='" + path + '\''
                + ", version='" + version + '\''
                + ", headerCount=" + headers.size()
                + ", queryParameterCount=" + queryParameters.size()
                + ", bodyChars=" + body.length() + '}';
    }
}
