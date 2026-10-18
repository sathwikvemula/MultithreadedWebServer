package com.sathwik.webserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

/**
 * Small helpers for closing client sockets cleanly.
 *
 * <p>Why draining matters: if a socket is closed while the client's data is still
 * unread, the operating system may reset the connection, and the client can lose the
 * response (for example a 413) before it reads it. Half-closing the output side and
 * discarding a bounded amount of input lets the client see the response first.
 */
final class Sockets {

    private static final int BUFFER_SIZE = 4096;

    private Sockets() {
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful can be done if closing fails.
        }
    }

    /**
     * Signals end-of-response, discards up to {@code maxBytes} of unread input for at
     * most {@code maxMillis}, then closes the socket. May block for up to {@code maxMillis}.
     */
    static void drainAndClose(Socket socket, int maxBytes, int maxMillis) {
        try {
            socket.shutdownOutput();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            long deadline = System.nanoTime() + maxMillis * 1_000_000L;
            int total = 0;
            while (total < maxBytes) {
                long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    break;
                }
                socket.setSoTimeout((int) remainingMillis);
                int count = in.read(buffer);
                if (count < 0) {
                    break;
                }
                total += count;
            }
        } catch (IOException ignored) {
            // Timed out, reset or already closed: the connection is finished either way.
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Like {@link #drainAndClose} but never blocks: it only discards input that has
     * already arrived. Used on the accept thread, which must not wait for any client.
     */
    static void drainAvailableAndClose(Socket socket, int maxBytes) {
        try {
            socket.shutdownOutput();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            int available;
            while (total < maxBytes && (available = in.available()) > 0) {
                int count = in.read(buffer, 0, Math.min(available, buffer.length));
                if (count < 0) {
                    break;
                }
                total += count;
            }
        } catch (IOException ignored) {
            // The connection is finished either way.
        } finally {
            closeQuietly(socket);
        }
    }
}
