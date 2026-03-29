package ru.jsonspecs.util;

import java.util.regex.Pattern;

/**
 * Parses JavaScript-style regex flag strings into Java {@link Pattern} flag bitmasks.
 *
 * <p>Supported flags: {@code i} (CASE_INSENSITIVE), {@code m} (MULTILINE), {@code s} (DOTALL).
 *
 * <p><b>Internal API.</b>
 */
public final class RegexFlags {

    private RegexFlags() {}

    /** Parse a flag string such as {@code "im"} into a {@link Pattern} bitmask. */
    public static int parse(String flags) {
        if (flags == null || flags.isEmpty()) return 0;
        int f = 0;
        if (flags.contains("i")) f |= Pattern.CASE_INSENSITIVE;
        if (flags.contains("m")) f |= Pattern.MULTILINE;
        if (flags.contains("s")) f |= Pattern.DOTALL;
        return f;
    }
}
