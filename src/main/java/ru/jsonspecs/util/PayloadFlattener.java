package ru.jsonspecs.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a nested JSON object (Map) to a flat dot-notation map.
 *
 * <p>The engine operates on flat payloads internally. Callers may pass either
 * nested JSON or pre-flattened maps — both are accepted by
 * {@link ru.jsonspecs.Engine#runPipeline}. This function is idempotent:
 * a pre-flattened map passes through unchanged.
 *
 * <h3>Conversion rules</h3>
 * <ul>
 *   <li>Nested objects: keys joined with {@code "."}</li>
 *   <li>Arrays: elements indexed as {@code [0]}, {@code [1]}, ...</li>
 *   <li>Scalar values: stored as-is</li>
 *   <li>The {@code "__context"} key is passed through untouched at the root level</li>
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
 * <h3>Empty array semantics</h3>
 * <p>An empty array at a nested path is stored as {@code prefix → []} in the flat map,
 * rather than disappearing. This differs from non-empty arrays, which are expanded into
 * {@code prefix[0]}, {@code prefix[1]}, ... without a {@code prefix} key.
 *
 * <p>Consequence: operators that reference a field like {@code "items"} see:
 * <ul>
 *   <li>{@code items = []}  → key {@code "items"} present with value {@code []}</li>
 *   <li>{@code items = [x]} → key {@code "items[0]"} present; key {@code "items"} absent</li>
 * </ul>
 *
 * <p>Per spec, {@code false}, {@code 0}, and {@code []} are not considered empty values,
 * so {@code not_empty("items")} passes for an empty array. This is intentional and
 * matches the Node.js reference implementation. Use wildcard rules ({@code "items[*].field"})
 * to validate the contents of arrays.
 *
 * <p><b>Internal API.</b>
 */
public final class PayloadFlattener {

    private PayloadFlattener() {}

    /**
     * Flatten a nested payload map.
     * This method is idempotent: a pre-flattened map passes through unchanged.
     *
     * @param input the payload map (may be {@code null})
     * @return a flat dot-notation map; never {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> flatten(Map<String, Object> input) {
        if (input == null) return Map.of();
        Map<String, Object> result = new HashMap<>();

        // Pass __context through untouched — the engine resolves $context.* fields from it
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
                // Store empty array under the prefix key so operators can detect it.
                // Non-empty arrays are NOT stored under the prefix — only as indexed keys.
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
