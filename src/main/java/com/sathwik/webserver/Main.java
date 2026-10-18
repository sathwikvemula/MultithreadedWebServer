package com.sathwik.webserver;

import java.io.IOException;

/**
 * Command-line entry point. Starts the server with the demo routes and runs until it
 * receives Ctrl+C (or the JVM is otherwise asked to exit), then shuts down gracefully.
 */
public final class Main {

    private static final String USAGE = """
            Usage: java -jar multithreaded-web-server-<version>.jar [options]

            Options (each takes a value, as "--name value" or "--name=value"):
              --host <address>              Address to bind (default %s; use 0.0.0.0 for all interfaces)
              --port <number>               TCP port, 0 = any free port (default %d)
              --threads <n>                 Worker threads (default %d)
              --queue <n>                   Connections allowed to wait for a worker (default %d)
              --read-timeout-ms <n>         Max time one socket read may block (default %d)
              --request-timeout-ms <n>      Max time to receive a whole request (default %d)
              --write-timeout-ms <n>        Max time to write one response (default %d)
              --max-request-line-bytes <n>  Request line limit (default %d)
              --max-header-bytes <n>        Total header size limit (default %d)
              --max-header-count <n>        Maximum number of headers (default %d)
              --max-body-bytes <n>          Request body limit (default %d)
              --shutdown-timeout-s <n>      Grace period before forced shutdown (default %d)
              -h, --help                    Show this help
            """.formatted(
            ServerConfig.DEFAULT_HOST,
            ServerConfig.DEFAULT_PORT,
            ServerConfig.DEFAULT_POOL_SIZE,
            ServerConfig.DEFAULT_QUEUE_CAPACITY,
            ServerConfig.DEFAULT_READ_TIMEOUT_MILLIS,
            ServerConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS,
            ServerConfig.DEFAULT_WRITE_TIMEOUT_MILLIS,
            ServerConfig.DEFAULT_MAX_REQUEST_LINE_BYTES,
            ServerConfig.DEFAULT_MAX_HEADER_BYTES,
            ServerConfig.DEFAULT_MAX_HEADER_COUNT,
            ServerConfig.DEFAULT_MAX_BODY_BYTES,
            ServerConfig.DEFAULT_SHUTDOWN_TIMEOUT_SECONDS);

    private Main() {
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.print(USAGE);
                return;
            }
        }

        ServerConfig config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            System.exit(2);
            return;
        }

        Server server = new Server(config, DemoRoutes.create());
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Could not start the server on " + config.getHost() + ":"
                    + config.getPort() + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        // The JDK's own logging cleanup can swallow log messages written during JVM shutdown,
        // so the hook confirms completion with a plain line instead of the logger.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            System.err.println("Server stopped.");
        }, "shutdown-hook"));

        try {
            server.awaitStopped();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop();
        }
    }

    /**
     * Converts command-line options into a validated {@link ServerConfig}.
     *
     * @throws IllegalArgumentException for unknown options, missing or non-numeric values,
     *                                  or values outside the allowed range
     */
    static ServerConfig parseArgs(String[] args) {
        ServerConfig.Builder builder = ServerConfig.builder();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String name;
            String value;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                name = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            } else {
                name = arg;
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + name);
                }
                value = args[++i];
            }
            apply(builder, name, value);
        }
        return builder.build();
    }

    private static void apply(ServerConfig.Builder builder, String name, String value) {
        switch (name) {
            case "--host" -> builder.host(value);
            case "--port" -> builder.port(number(name, value));
            case "--threads" -> builder.poolSize(number(name, value));
            case "--queue" -> builder.queueCapacity(number(name, value));
            case "--read-timeout-ms" -> builder.readTimeoutMillis(number(name, value));
            case "--request-timeout-ms" -> builder.requestTimeoutMillis(number(name, value));
            case "--write-timeout-ms" -> builder.writeTimeoutMillis(number(name, value));
            case "--max-request-line-bytes" -> builder.maxRequestLineBytes(number(name, value));
            case "--max-header-bytes" -> builder.maxHeaderBytes(number(name, value));
            case "--max-header-count" -> builder.maxHeaderCount(number(name, value));
            case "--max-body-bytes" -> builder.maxBodyBytes(number(name, value));
            case "--shutdown-timeout-s" -> builder.shutdownTimeoutSeconds(number(name, value));
            default -> throw new IllegalArgumentException("Unknown option: " + name);
        }
    }

    private static int number(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " expects a whole number but got: " + value);
        }
    }
}
