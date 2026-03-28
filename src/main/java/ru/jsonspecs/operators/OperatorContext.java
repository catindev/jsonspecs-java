package ru.jsonspecs.operators;

import ru.jsonspecs.util.DeepGet;

import java.util.Map;

/**
 * Context passed to every operator at runtime.
 *
 * <p>The payload is always a flattened map (dot-notation keys → scalar values).
 * Use {@link #get(String)} to safely look up a field.
 */
public record OperatorContext(
    Map<String, Object> payload,
    Map<String, Map<String, Object>> dictionaries
) {
    /**
     * Look up a field in the flat payload.
     * Supports {@code $context.*} prefix for runtime context fields.
     */
    public DeepGet.Result get(String field) {
        return DeepGet.get(payload, field);
    }
}
