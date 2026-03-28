package io.jsonspecs.util;

import java.util.Map;

/**
 * Field lookup in a flat payload map.
 *
 * <p>The engine always works with flat (dot-notation) maps.
 * Use {@link PayloadFlattener} to convert nested JSON to flat form before passing to operators.
 *
 * <p>Supports {@code $context.key} prefix to look up runtime context fields
 * stored under the reserved {@code __context} key in the payload.
 *
 * <p>This is a stable public helper — safe to use inside custom operators.
 */
public final class DeepGet {

    private static final String CONTEXT_PREFIX = "$context.";
    private static final String CONTEXT_KEY    = "__context";

    private DeepGet() {}

    public sealed interface Result permits Result.Found, Result.NotFound {
        record Found(Object value) implements Result {
            public boolean ok() { return true; }
        }
        record NotFound() implements Result {
            public boolean ok() { return false; }
            public Object value() { return null; }
        }

        default boolean ok()    { return false; }
        default Object  value() { return null; }
    }

    /**
     * Look up {@code field} in {@code payload}.
     *
     * @param payload flat dot-notation map (may contain {@code __context} key)
     * @param field   field path, optionally prefixed with {@code $context.}
     * @return {@link Result.Found} if the key exists (value may be null),
     *         {@link Result.NotFound} otherwise
     */
    @SuppressWarnings("unchecked")
    public static Result get(Map<String, Object> payload, String field) {
        if (field == null || payload == null) return new Result.NotFound();

        if (field.startsWith(CONTEXT_PREFIX)) {
            String contextKey = field.substring(CONTEXT_PREFIX.length());
            Object ctx = payload.get(CONTEXT_KEY);
            if (!(ctx instanceof Map)) return new Result.NotFound();
            Map<String, Object> ctxMap = (Map<String, Object>) ctx;
            if (!ctxMap.containsKey(contextKey)) return new Result.NotFound();
            return new Result.Found(ctxMap.get(contextKey));
        }

        if (!payload.containsKey(field)) return new Result.NotFound();
        return new Result.Found(payload.get(field));
    }
}
