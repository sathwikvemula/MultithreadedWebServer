package com.sathwik.webserver.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal parser for {@code --name value} and {@code --name=value} options, shared by
 * {@link Client} and {@link LoadTest}. Every error is an {@link IllegalArgumentException}
 * with a message that can be shown to the user as-is.
 */
final class CommandLine {

    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final Set<String> flags = new LinkedHashSet<>();

    private CommandLine() {
    }

    /**
     * @param valueOptions options that take a value; they may be repeated
     * @param flagOptions  options that take no value
     */
    static CommandLine parse(String[] args, Set<String> valueOptions, Set<String> flagOptions) {
        CommandLine result = new CommandLine();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || arg.length() == 2) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String name = arg;
            String inlineValue = null;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                name = arg.substring(0, equals);
                inlineValue = arg.substring(equals + 1);
            }

            if (flagOptions.contains(name)) {
                if (inlineValue != null) {
                    throw new IllegalArgumentException(name + " does not take a value");
                }
                result.flags.add(name);
            } else if (valueOptions.contains(name)) {
                String value = inlineValue;
                if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for " + name);
                    }
                    value = args[++i];
                }
                result.values.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            } else {
                throw new IllegalArgumentException("Unknown option: " + name);
            }
        }
        return result;
    }

    boolean has(String name) {
        return values.containsKey(name);
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    /** The last value given for the option, or null if it was not given. */
    String last(String name) {
        List<String> list = values.get(name);
        return list == null ? null : list.get(list.size() - 1);
    }

    /** All values given for a repeatable option, in order. */
    List<String> all(String name) {
        return values.getOrDefault(name, List.of());
    }

    int integer(String name, int defaultValue, int min, int max) {
        String raw = last(name);
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " expects a whole number but got: " + raw);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }
}
