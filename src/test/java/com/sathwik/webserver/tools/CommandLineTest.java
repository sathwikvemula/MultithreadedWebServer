package com.sathwik.webserver.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandLineTest {

    private static final Set<String> VALUES = Set.of("--port", "--header", "--name");
    private static final Set<String> FLAGS = Set.of("--quiet");

    private static CommandLine parse(String... args) {
        return CommandLine.parse(args, VALUES, FLAGS);
    }

    @Test
    void acceptsSeparateAndEqualsSignValues() {
        CommandLine cl = parse("--port", "9090", "--name=ada");
        assertEquals("9090", cl.last("--port"));
        assertEquals("ada", cl.last("--name"));
    }

    @Test
    void valuesMayContainEqualsSigns() {
        assertEquals("a=b=c", parse("--name=a=b=c").last("--name"));
    }

    @Test
    void repeatableOptionsKeepEveryValueInOrder() {
        CommandLine cl = parse("--header", "A: 1", "--header=B: 2");
        assertEquals(List.of("A: 1", "B: 2"), cl.all("--header"));
        assertEquals("B: 2", cl.last("--header"));
    }

    @Test
    void absentOptionsAreReportedAsAbsent() {
        CommandLine cl = parse();
        assertFalse(cl.has("--port"));
        assertNull(cl.last("--port"));
        assertTrue(cl.all("--header").isEmpty());
        assertFalse(cl.flag("--quiet"));
    }

    @Test
    void flagsTakeNoValue() {
        assertTrue(parse("--quiet").flag("--quiet"));
        assertThrows(IllegalArgumentException.class, () -> parse("--quiet=yes"));
    }

    @Test
    void rejectsUnknownOptionsMissingValuesAndPositionalArguments() {
        assertEquals("Unknown option: --bogus",
                assertThrows(IllegalArgumentException.class, () -> parse("--bogus", "1")).getMessage());
        assertEquals("Missing value for --port",
                assertThrows(IllegalArgumentException.class, () -> parse("--port")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> parse("8080"));
        assertThrows(IllegalArgumentException.class, () -> parse("--"));
        assertThrows(IllegalArgumentException.class, () -> parse("-p", "1"));
    }

    @Test
    void integerParsingUsesDefaultsAndChecksTheRange() {
        assertEquals(7, parse().integer("--port", 7, 1, 100));
        assertEquals(50, parse("--port", " 50 ").integer("--port", 7, 1, 100));

        IllegalArgumentException notNumber = assertThrows(IllegalArgumentException.class,
                () -> parse("--port", "eighty").integer("--port", 7, 1, 100));
        assertTrue(notNumber.getMessage().contains("--port"));
        assertTrue(notNumber.getMessage().contains("eighty"));

        assertThrows(IllegalArgumentException.class, () -> parse("--port", "0").integer("--port", 7, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> parse("--port", "101").integer("--port", 7, 1, 100));
        assertThrows(IllegalArgumentException.class, () -> parse("--port", "99999999999").integer("--port", 7, 1, 100));
    }
}
