package com.sathwik.webserver.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A one-connection test server that answers with canned bytes, so the clients can be tested
 * against malformed, truncated, slow or silent servers and so the exact request bytes can be captured.
 */
final class FakeServer implements AutoCloseable {

    private interface Responder {
        void respond(FakeServer server, Socket socket) throws Exception;
    }

    private final ServerSocket serverSocket;
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private volatile byte[] request = new byte[0];

    private FakeServer(Responder responder) throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        if (responder != null) {
            Thread thread = new Thread(() -> serve(responder), "fake-server");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Reads the whole request, writes the response bytes, then closes the connection. */
    static FakeServer replying(byte[] response) throws IOException {
        return new FakeServer((server, socket) -> {
            server.readRequest(socket);
            socket.getOutputStream().write(response);
            socket.getOutputStream().flush();
        });
    }

    /** Like {@link #replying(byte[])} but takes the response as ISO-8859-1 text. */
    static FakeServer replying(String response) throws IOException {
        return replying(response.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Reads the request, then sends the response one byte at a time with a pause between bytes. */
    static FakeServer trickling(String response, long millisPerByte) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.ISO_8859_1);
        return new FakeServer((server, socket) -> {
            server.readRequest(socket);
            try {
                for (byte b : bytes) {
                    socket.getOutputStream().write(b);
                    socket.getOutputStream().flush();
                    Thread.sleep(millisPerByte);
                }
            } catch (IOException clientGone) {
                // The client gave up, which is what the test expects.
            }
        });
    }

    /** Listens (so connections succeed) but never accepts or answers: clients see a read timeout. */
    static FakeServer silent() throws IOException {
        return new FakeServer(null);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** The bytes of the request the client sent (head and body). */
    byte[] awaitRequest() throws InterruptedException {
        if (!requestReceived.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("The fake server never received a request");
        }
        return request;
    }

    String awaitRequestText() throws InterruptedException {
        return new String(awaitRequest(), StandardCharsets.ISO_8859_1);
    }

    private void serve(Responder responder) {
        try (Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(5_000);
            responder.respond(this, socket);
        } catch (Exception ignored) {
            // The server was closed or the client left; either way the test decides what to assert.
        }
    }

    private void readRequest(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        int tail = 0;
        int b;
        while (tail != 0x0D0A0D0A && (b = in.read()) >= 0) {
            collected.write(b);
            tail = (tail << 8) | b;
        }
        String head = collected.toString(StandardCharsets.ISO_8859_1);
        int length = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        for (int i = 0; i < length; i++) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            collected.write(next);
        }
        request = collected.toByteArray();
        requestReceived.countDown();
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Nothing useful can be done.
        }
    }
}
