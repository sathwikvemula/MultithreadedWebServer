package com.sathwik.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void noArgumentsGivesTheDefaults() {
        ServerConfig config = Main.parseArgs(new String[0]);
        ServerConfig defaults = ServerConfig.defaults();
        assertEquals(defaults.getHost(), config.getHost());
        assertEquals(defaults.getPort(), config.getPort());
        assertEquals(defaults.getPoolSize(), config.getPoolSize());
        assertEquals(defaults.getQueueCapacity(), config.getQueueCapacity());
    }

    @Test
    void acceptsSeparateAndEqualsSignValues() {
        ServerConfig config = Main.parseArgs(new String[] {"--port", "9090", "--threads=4", "--host", "0.0.0.0"});
        assertEquals(9090, config.getPort());
        assertEquals(4, config.getPoolSize());
        assertEquals("0.0.0.0", config.getHost());
    }

    @Test
    void mapsEveryNumericOption() {
        ServerConfig config = Main.parseArgs(new String[] {
                "--queue", "7",
                "--read-timeout-ms", "11",
                "--request-timeout-ms", "12",
                "--write-timeout-ms", "13",
                "--max-request-line-bytes", "14",
                "--max-header-bytes", "15",
                "--max-header-count", "16",
                "--max-body-bytes", "17",
                "--shutdown-timeout-s", "18"});
        assertEquals(7, config.getQueueCapacity());
        assertEquals(11, config.getReadTimeoutMillis());
        assertEquals(12, config.getRequestTimeoutMillis());
        assertEquals(13, config.getWriteTimeoutMillis());
        assertEquals(14, config.getMaxRequestLineBytes());
        assertEquals(15, config.getMaxHeaderBytes());
        assertEquals(16, config.getMaxHeaderCount());
        assertEquals(17, config.getMaxBodyBytes());
        assertEquals(18, config.getShutdownTimeoutSeconds());
    }

    @Test
    void rejectsUnknownOptions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[] {"--bogus", "1"}));
        assertTrue(e.getMessage().contains("--bogus"));
    }

    @Test
    void rejectsMissingValues() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"--port"}));
    }

    @Test
    void rejectsNonNumericValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[] {"--port", "eighty"}));
        assertTrue(e.getMessage().contains("--port"));
    }

    @Test
    void rejectsPositionalArguments() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"8080"}));
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"--threads", "0"}));
    }
}
