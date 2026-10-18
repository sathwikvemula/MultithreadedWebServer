package com.sathwik.webserver;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deliberately strict raw-socket HTTP client for the integration tests. It rejects
 * responses with bare-LF line endings or a Content-Length that does not match the body.
 */
final class TestHttpClient {

    static final class Response {
        final int status;
        final String reason;
        final Map<String, String> headers;
        final String body;
        final int bodyLength;

        Response(int status, String reason, Map<String, String> headers, String body, int bodyLength) {
            this.status = status;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
            this.bodyLength = bodyLength;
        }
    }

    private TestHttpClient() {
    }

    static Socket open(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        return socket;
    }

    static void write(Socket socket, String raw) throws IOException {
        socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    static Response send(int port, String raw) throws IOException {
        return send(port, raw.getBytes(StandardCharsets.UTF_8));
    }

    static Response send(int port, byte[] raw) throws IOException {
        try (Socket socket = open(port)) {
            socket.getOutputStream().write(raw);
            socket.getOutputStream().flush();
            return read(socket);
        }
    }

    static Response get(int port, String path) throws IOException {
        return send(port, "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    static Response post(int port, String path, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + bodyBytes.length
                + "\r\nConnection: close\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] all = Arrays.copyOf(headBytes, headBytes.length + bodyBytes.length);
        System.arraycopy(bodyBytes, 0, all, headBytes.length, bodyBytes.length);
        return send(port, all);
    }

    /** Reads until the server closes the connection, then parses the response strictly. */
    static Response read(Socket socket) throws IOException {
        return parse(socket.getInputStream().readAllBytes());
    }

    static Response parse(byte[] all) {
        int split = -1;
        for (int i = 0; i + 3 < all.length; i++) {
            if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                split = i;
                break;
            }
        }
        if (split < 0) {
            throw new AssertionError("Response has no CRLFCRLF header terminator: "
                    + new String(all, StandardCharsets.ISO_8859_1));
        }
        String head = new String(all, 0, split, StandardCharsets.ISO_8859_1);
        if (head.replace("\r\n", "").indexOf('\n') >= 0) {
            throw new AssertionError("Response uses bare LF line endings: " + head);
        }
        String[] lines = head.split("\r\n", -1);
        String[] statusParts = lines[0].split(" ", 3);
        if (statusParts.length != 3 || !statusParts[0].equals("HTTP/1.1")) {
            throw new AssertionError("Malformed status line: " + lines[0]);
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new AssertionError("Malformed header line: " + lines[i]);
            }
            headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1).trim());
        }
        byte[] bodyBytes = Arrays.copyOfRange(all, split + 4, all.length);
        String declared = headers.get("Content-Length");
        if (declared == null || Integer.parseInt(declared) != bodyBytes.length) {
            throw new AssertionError("Content-Length " + declared + " does not match body of "
                    + bodyBytes.length + " bytes");
        }
        return new Response(
                Integer.parseInt(statusParts[1]),
                statusParts[2],
                headers,
                new String(bodyBytes, StandardCharsets.UTF_8),
                bodyBytes.length);
    }
}
