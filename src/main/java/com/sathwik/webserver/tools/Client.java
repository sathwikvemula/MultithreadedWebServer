package com.sathwik.webserver.tools;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A command-line HTTP client for talking to the server (or any HTTP/1.1 server).
 *
 * <p>Run it from the packaged jar:
 * <pre>
 * java -cp target\multithreaded-web-server-1.0.0-SNAPSHOT.jar com.sathwik.webserver.tools.Client --path /hello
 * </pre>
 *
 * <p>Exit codes: {@code 0} the response was 2xx; {@code 1} a response was received but was not
 * 2xx; {@code 2} invalid command line; {@code 3} connection, timeout or protocol failure.
 */
public final class Client {

    static final int EXIT_OK = 0;
    static final int EXIT_HTTP_ERROR = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_FAILURE = 3;

    /** Largest request body that {@code --body-file} will load. */
    static final long MAX_BODY_FILE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--host", "--port", "--method", "--path", "--body", "--body-file", "--content-type", "--header",
            "--connect-timeout-ms", "--read-timeout-ms", "--total-timeout-ms", "--max-response-bytes");
    private static final Set<String> FLAG_OPTIONS = Set.of("--no-headers", "--help");

    private static final String USAGE = """
            Usage: java -cp <jar> com.sathwik.webserver.tools.Client [options]

            Sends one HTTP/1.1 request and prints the response.

            Options (each takes a value, as "--name value" or "--name=value"):
              --host <name>               Server host (default 127.0.0.1)
              --port <number>             Server port (default 8010)
              --method <name>             HTTP method (default GET, or POST when a body is given)
              --path <path>               Request path and query, percent-encoded (default /hello)
              --body <text>               Request body, sent as UTF-8 bytes. Non-ASCII text may be altered
                                          by your OS code page before Java sees it; use --body-file for that.
              --body-file <file>          Request body read from a file (up to 10 MiB), sent byte-for-byte
              --content-type <type>       Content-Type for the body (default text/plain; charset=utf-8)
              --header "Name: value"      Extra request header; may be repeated
              --connect-timeout-ms <n>    Connect timeout (default %d)
              --read-timeout-ms <n>       Longest a single read may block (default %d)
              --total-timeout-ms <n>      Longest the whole request may take (default %d)
              --max-response-bytes <n>    Reject larger response bodies (default %d)
            Flags:
              --no-headers                Print only the response body
              --help                      Show this help

            Exit codes: 0 = 2xx response, 1 = other HTTP status, 2 = bad command line,
                        3 = connection, timeout or protocol failure.
            """.formatted(
            RawHttpClient.DEFAULT_CONNECT_TIMEOUT_MILLIS,
            RawHttpClient.DEFAULT_READ_TIMEOUT_MILLIS,
            RawHttpClient.DEFAULT_TOTAL_TIMEOUT_MILLIS,
            RawHttpClient.DEFAULT_MAX_RESPONSE_BYTES);

    private Client() {
    }

    /** Everything the command line can configure. */
    static final class Options {

        final String host;
        final int port;
        final String method;
        final String path;
        final byte[] body;
        final Map<String, String> headers;
        final int connectTimeoutMillis;
        final int readTimeoutMillis;
        final int totalTimeoutMillis;
        final int maxResponseBytes;
        final boolean showHeaders;

        private Options(
                String host, int port, String method, String path, byte[] body, Map<String, String> headers,
                int connectTimeoutMillis, int readTimeoutMillis, int totalTimeoutMillis,
                int maxResponseBytes, boolean showHeaders
        ) {
            this.host = host;
            this.port = port;
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
            this.totalTimeoutMillis = totalTimeoutMillis;
            this.maxResponseBytes = maxResponseBytes;
            this.showHeaders = showHeaders;
        }

        /**
         * @throws IllegalArgumentException with a user-readable message for any invalid input
         */
        static Options parse(String[] args) {
            CommandLine cl = CommandLine.parse(args, VALUE_OPTIONS, FLAG_OPTIONS);

            String host = cl.has("--host") ? cl.last("--host") : "127.0.0.1";
            RawHttpClient.validateHost(host);
            int port = cl.integer("--port", 8010, 1, 65_535);

            String path = cl.has("--path") ? cl.last("--path") : "/hello";
            RawHttpClient.validatePath(path);

            if (cl.has("--body") && cl.has("--body-file")) {
                throw new IllegalArgumentException("Use either --body or --body-file, not both");
            }
            byte[] body = null;
            if (cl.has("--body")) {
                body = cl.last("--body").getBytes(StandardCharsets.UTF_8);
            } else if (cl.has("--body-file")) {
                body = readBodyFile(cl.last("--body-file"));
            }

            String method = cl.has("--method")
                    ? cl.last("--method").toUpperCase(Locale.ROOT)
                    : (body != null ? "POST" : "GET");
            RawHttpClient.validateMethod(method);

            Map<String, String> headers = new LinkedHashMap<>();
            for (String header : cl.all("--header")) {
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("--header expects \"Name: value\" but got: " + header);
                }
                putHeader(headers, header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
            if (cl.has("--content-type")) {
                putHeader(headers, "Content-Type", cl.last("--content-type"));
            }
            if (body != null && !containsHeader(headers, "Content-Type")) {
                putHeader(headers, "Content-Type", "text/plain; charset=utf-8");
            }

            return new Options(
                    host, port, method, path, body, headers,
                    cl.integer("--connect-timeout-ms", RawHttpClient.DEFAULT_CONNECT_TIMEOUT_MILLIS, 1, 3_600_000),
                    cl.integer("--read-timeout-ms", RawHttpClient.DEFAULT_READ_TIMEOUT_MILLIS, 1, 3_600_000),
                    cl.integer("--total-timeout-ms", RawHttpClient.DEFAULT_TOTAL_TIMEOUT_MILLIS, 1, 3_600_000),
                    cl.integer("--max-response-bytes", RawHttpClient.DEFAULT_MAX_RESPONSE_BYTES, 0, Integer.MAX_VALUE),
                    !cl.flag("--no-headers"));
        }

        private static void putHeader(Map<String, String> headers, String name, String value) {
            RawHttpClient.validateHeader(name, value);
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            headers.put(name, value);
        }

        private static boolean containsHeader(Map<String, String> headers, String name) {
            return headers.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name));
        }

        private static byte[] readBodyFile(String fileName) {
            Path file = Paths.get(fileName);
            try {
                long size = Files.size(file);
                if (size > MAX_BODY_FILE_BYTES) {
                    throw new IllegalArgumentException(
                            "Body file is larger than " + MAX_BODY_FILE_BYTES + " bytes: " + fileName);
                }
                return Files.readAllBytes(file);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read body file " + fileName + ": " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int exitCode = run(args, out, err);
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    /** Runs the client and returns the process exit code. Separated from {@link #main} for testing. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                out.print(USAGE);
                return EXIT_OK;
            }
        }

        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            err.println("Run with --help for usage.");
            return EXIT_USAGE;
        }

        RawHttpClient client = new RawHttpClient(
                options.connectTimeoutMillis,
                options.readTimeoutMillis,
                options.totalTimeoutMillis,
                options.maxResponseBytes);

        RawHttpClient.Response response;
        try {
            response = client.send(
                    options.host, options.port, options.method, options.path, options.headers, options.body);
        } catch (IOException e) {
            err.println("Error: request to " + options.host + ":" + options.port + " failed: "
                    + RawHttpClient.describeFailure(e));
            return EXIT_FAILURE;
        }

        print(response, options.showHeaders, out);
        return response.statusCode() >= 200 && response.statusCode() <= 299 ? EXIT_OK : EXIT_HTTP_ERROR;
    }

    private static void print(RawHttpClient.Response response, boolean showHeaders, PrintStream out) {
        String body = RawHttpClient.sanitizeForDisplay(response.bodyText());
        if (showHeaders) {
            out.println(RawHttpClient.sanitizeForDisplay(response.statusLine()));
            for (Map.Entry<String, String> header : response.headers().entrySet()) {
                out.println(RawHttpClient.sanitizeForDisplay(header.getKey() + ": " + header.getValue()));
            }
            out.println();
            out.print(body);
            if (!body.isEmpty() && !body.endsWith("\n")) {
                out.println();
            }
        } else {
            out.print(body);
        }
        out.flush();
    }
}
