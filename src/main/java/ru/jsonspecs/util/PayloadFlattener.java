package ru.jsonspecs.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a nested JSON object (Map) to a flat dot-notation map.
 *
 * <p>The engine operates on flat payloads internally. Callers may pass either
 * nested JSON or pre-flattened maps — both are accepted by {@link Engine#runPipeline}.
 *
 * <h3>Conversion rules</h3>
 * <ul>
 *   <li>Nested objects: keys joined with {@code "."}</li>
 *   <li>Arrays: elements indexed as {@code [0]}, {@code [1]}, ...</li>
 *   <li>Scalar values: stored as-is</li>
 *   <li>The {@code "__context"} key is passed through untouched at any level</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 *   { "a": { "b": 1 } }             →  { "a.b": 1 }
 *   { "items": ["x", "y"] }         →  { "items[0]": "x", "items[1]": "y" }
 *   { "a": [{ "b": 1 }] }           →  { "a[0].b": 1 }
 *   { "__context": { "k": "v" } }   →  { "__context": { "k": "v" } }  (intact)
 * </pre>
 *
 * <p><b>Internal API.</b> This class is an implementation detail of the jsonspecs engine.
 * It is not part of the stable public API and may change without notice between versions.
 * Use {@link ru.jsonspecs.Engine} as the only entry point.
 */
public final class PayloadFlattener {

    private PayloadFlattener() {}

    /**
     * Flatten a nested payload map.
     * This method is idempotent: a pre-flattened map passes through unchanged.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> flatten(Map<String, Object> input) {
        if (input == null) return Map.of();
        Map<String, Object> result = new HashMap<>();

        // Pass __context through untouched
        if (input.containsKey("__context")) {
            result.put("__context", input.get("__context"));
        }

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if ("__context".equals(entry.getKey())) continue;
            flattenValue(entry.getKey(), entry.getValue(), result);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void flattenValue(String prefix, Object value, Map<String, Object> result) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                flattenValue(prefix + "." + entry.getKey(), entry.getValue(), result);
            }
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                result.put(prefix, List.of());
            } else {
                for (int i = 0; i < list.size(); i++) {
                    flattenValue(prefix + "[" + i + "]", list.get(i), result);
                }
            }
        } else {
            result.put(prefix, value);
        }
    }
}
