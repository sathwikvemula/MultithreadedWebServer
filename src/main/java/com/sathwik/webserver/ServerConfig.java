package com.sathwik.webserver;

/**
 * Immutable server settings. Use {@link #defaults()} or {@link #builder()}.
 *
 * <p>Every resource the server consumes is bounded by one of these values: threads
 * ({@code poolSize}), waiting connections ({@code queueCapacity}), time
 * ({@code readTimeoutMillis}, {@code requestTimeoutMillis}, {@code writeTimeoutMillis}) and memory per request
 * ({@code maxRequestLineBytes}, {@code maxHeaderBytes}, {@code maxHeaderCount},
 * {@code maxBodyBytes}).
 */
public final class ServerConfig {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8010;
    public static final int DEFAULT_POOL_SIZE = 10;
    public static final int DEFAULT_QUEUE_CAPACITY = 100;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 5_000;
    public static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000;
    public static final int DEFAULT_WRITE_TIMEOUT_MILLIS = 10_000;
    public static final int DEFAULT_MAX_REQUEST_LINE_BYTES = 8 * 1024;
    public static final int DEFAULT_MAX_HEADER_BYTES = 16 * 1024;
    public static final int DEFAULT_MAX_HEADER_COUNT = 100;
    public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;
    public static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final String host;
    private final int port;
    private final int poolSize;
    private final int queueCapacity;
    private final int readTimeoutMillis;
    private final int requestTimeoutMillis;
    private final int writeTimeoutMillis;
    private final int maxRequestLineBytes;
    private final int maxHeaderBytes;
    private final int maxHeaderCount;
    private final int maxBodyBytes;
    private final int shutdownTimeoutSeconds;

    private ServerConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.poolSize = b.poolSize;
        this.queueCapacity = b.queueCapacity;
        this.readTimeoutMillis = b.readTimeoutMillis;
        this.requestTimeoutMillis = b.requestTimeoutMillis;
        this.writeTimeoutMillis = b.writeTimeoutMillis;
        this.maxRequestLineBytes = b.maxRequestLineBytes;
        this.maxHeaderBytes = b.maxHeaderBytes;
        this.maxHeaderCount = b.maxHeaderCount;
        this.maxBodyBytes = b.maxBodyBytes;
        this.shutdownTimeoutSeconds = b.shutdownTimeoutSeconds;
    }

    public static ServerConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHost() {
        return host;
    }

    /** The TCP port to listen on. 0 asks the operating system for a free port (useful in tests). */
    public int getPort() {
        return port;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    /** Maximum time a single socket read may block. */
    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    /** Maximum total time allowed to receive one complete request. */
    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    /** Maximum time allowed to write one response before the connection is closed. */
    public int getWriteTimeoutMillis() {
        return writeTimeoutMillis;
    }

    public int getMaxRequestLineBytes() {
        return maxRequestLineBytes;
    }

    public int getMaxHeaderBytes() {
        return maxHeaderBytes;
    }

    public int getMaxHeaderCount() {
        return maxHeaderCount;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    @Override
    public String toString() {
        return "ServerConfig{host='" + host + "', port=" + port
                + ", poolSize=" + poolSize
                + ", queueCapacity=" + queueCapacity
                + ", readTimeoutMillis=" + readTimeoutMillis
                + ", requestTimeoutMillis=" + requestTimeoutMillis
                + ", writeTimeoutMillis=" + writeTimeoutMillis
                + ", maxRequestLineBytes=" + maxRequestLineBytes
                + ", maxHeaderBytes=" + maxHeaderBytes
                + ", maxHeaderCount=" + maxHeaderCount
                + ", maxBodyBytes=" + maxBodyBytes
                + ", shutdownTimeoutSeconds=" + shutdownTimeoutSeconds + '}';
    }

    /** Fluent builder. {@link #build()} validates every value. */
    public static final class Builder {

        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private int poolSize = DEFAULT_POOL_SIZE;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private int readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;
        private int requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS;
        private int writeTimeoutMillis = DEFAULT_WRITE_TIMEOUT_MILLIS;
        private int maxRequestLineBytes = DEFAULT_MAX_REQUEST_LINE_BYTES;
        private int maxHeaderBytes = DEFAULT_MAX_HEADER_BYTES;
        private int maxHeaderCount = DEFAULT_MAX_HEADER_COUNT;
        private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private int shutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder readTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public Builder requestTimeoutMillis(int requestTimeoutMillis) {
            this.requestTimeoutMillis = requestTimeoutMillis;
            return this;
        }

        public Builder writeTimeoutMillis(int writeTimeoutMillis) {
            this.writeTimeoutMillis = writeTimeoutMillis;
            return this;
        }

        public Builder maxRequestLineBytes(int maxRequestLineBytes) {
            this.maxRequestLineBytes = maxRequestLineBytes;
            return this;
        }

        public Builder maxHeaderBytes(int maxHeaderBytes) {
            this.maxHeaderBytes = maxHeaderBytes;
            return this;
        }

        public Builder maxHeaderCount(int maxHeaderCount) {
            this.maxHeaderCount = maxHeaderCount;
            return this;
        }

        public Builder maxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        public Builder shutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
            this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
            return this;
        }

        public ServerConfig build() {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (port < 0 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            requirePositive("poolSize", poolSize);
            requirePositive("queueCapacity", queueCapacity);
            requirePositive("readTimeoutMillis", readTimeoutMillis);
            requirePositive("requestTimeoutMillis", requestTimeoutMillis);
            requirePositive("writeTimeoutMillis", writeTimeoutMillis);
            requirePositive("maxRequestLineBytes", maxRequestLineBytes);
            requirePositive("maxHeaderBytes", maxHeaderBytes);
            requirePositive("maxHeaderCount", maxHeaderCount);
            if (maxBodyBytes < 0) {
                throw new IllegalArgumentException("maxBodyBytes must not be negative");
            }
            if (shutdownTimeoutSeconds < 0) {
                throw new IllegalArgumentException("shutdownTimeoutSeconds must not be negative");
            }
            return new ServerConfig(this);
        }

        private static void requirePositive(String name, int value) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be at least 1");
            }
        }
    }
}
