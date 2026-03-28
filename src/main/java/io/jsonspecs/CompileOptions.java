package io.jsonspecs;

import java.util.Map;

/**
 * Options for {@link Engine#compile}.
 */
public record CompileOptions(
    /**
     * Optional source map: artifact id → file path.
     * Used in compilation error messages to show which file contains the invalid artifact.
     */
    Map<String, String> sources
) {
    public static CompileOptions defaults() {
        return new CompileOptions(Map.of());
    }

    public static CompileOptions withSources(Map<String, String> sources) {
        return new CompileOptions(Map.copyOf(sources));
    }
}
