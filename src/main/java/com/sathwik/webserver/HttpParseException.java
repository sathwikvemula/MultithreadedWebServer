package com.sathwik.webserver;

import java.util.Objects;

/**
 * Thrown when a request cannot be parsed. It carries the HTTP status the server
 * should answer with (for example 400, 413 or 505) and a short, safe message that
 * can be sent back to the client.
 */
public class HttpParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public HttpParseException(HttpStatus status, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
    }

    public HttpStatus getStatus() {
        return status;
    }
}
