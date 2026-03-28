package ru.jsonspecs.compiler;

import java.util.List;
import java.util.Map;

/**
 * Normalized form of a condition's {@code when} clause.
 *
 * <p>Source JSON shapes:
 * <pre>
 *   "when": "predicate.id"                  → Single("predicate.id")
 *   "when": { "all": ["a", "b"] }           → All([Single("a"), Single("b")])
 *   "when": { "any": ["a", { "all": ... }]} → Any([...])
 * </pre>
 */
public sealed interface WhenExpr
    permits WhenExpr.Single, WhenExpr.All, WhenExpr.Any {

    record Single(String predicateId) implements WhenExpr {}
    record All(List<WhenExpr> items)  implements WhenExpr {}
    record Any(List<WhenExpr> items)  implements WhenExpr {}

    /**
     * Parse a raw {@code when} value from an artifact map.
     *
     * @throws IllegalArgumentException if the shape is unrecognized
     */
    @SuppressWarnings("unchecked")
    static WhenExpr parse(Object raw) {
        if (raw instanceof String s) {
            if (s.isBlank()) throw new IllegalArgumentException("when string must be non-empty");
            return new Single(s);
        }
        if (raw instanceof Map<?, ?> map) {
            if (map.containsKey("all")) {
                List<Object> items = (List<Object>) map.get("all");
                if (items == null || items.isEmpty())
                    throw new IllegalArgumentException("when.all must be non-empty array");
                return new All(items.stream().map(WhenExpr::parse).toList());
            }
            if (map.containsKey("any")) {
                List<Object> items = (List<Object>) map.get("any");
                if (items == null || items.isEmpty())
                    throw new IllegalArgumentException("when.any must be non-empty array");
                return new Any(items.stream().map(WhenExpr::parse).toList());
            }
        }
        throw new IllegalArgumentException(
            "Invalid when clause: expected string or {all:[...]} or {any:[...]}");
    }
}
