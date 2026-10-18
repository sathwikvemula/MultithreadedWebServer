package com.sathwik.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ServerConfigTest {

    @Test
    void defaultsMatchTheDocumentedValues() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals("127.0.0.1", config.getHost());
        assertEquals(8010, config.getPort());
        assertEquals(10, config.getPoolSize());
        assertEquals(100, config.getQueueCapacity());
        assertEquals(5_000, config.getReadTimeoutMillis());
        assertEquals(15_000, config.getRequestTimeoutMillis());
        assertEquals(10_000, config.getWriteTimeoutMillis());
        assertEquals(8 * 1024, config.getMaxRequestLineBytes());
        assertEquals(16 * 1024, config.getMaxHeaderBytes());
        assertEquals(100, config.getMaxHeaderCount());
        assertEquals(1024 * 1024, config.getMaxBodyBytes());
        assertEquals(5, config.getShutdownTimeoutSeconds());
    }

    @Test
    void builderOverridesIndividualValues() {
        ServerConfig config = ServerConfig.builder().port(0).poolSize(2).host("0.0.0.0").build();
        assertEquals(0, config.getPort());
        assertEquals(2, config.getPoolSize());
        assertEquals("0.0.0.0", config.getHost());
        assertEquals(100, config.getQueueCapacity());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().port(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().port(65_536).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().poolSize(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().queueCapacity(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().readTimeoutMillis(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().writeTimeoutMillis(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().maxBodyBytes(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().host(" ").build());
    }
}
