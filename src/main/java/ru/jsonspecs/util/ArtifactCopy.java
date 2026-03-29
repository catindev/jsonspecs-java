package ru.jsonspecs.util;

import java.util.*;

/**
 * Deep-copy utility for artifact maps passed into {@code Engine.compile()}.
 *
 * <h3>Why this exists</h3>
 * <p>Artifacts arrive as caller-owned {@code Map<String,Object>} objects.
 * {@code Collections.unmodifiableMap} protects the top-level registry map,
 * but the <em>values</em> (the artifact maps themselves) remain the caller's
 * references. A mutation after compile would silently change compiled behaviour.
 *
 * <p>This class produces a fully independent, immutable snapshot of each artifact
 * map so that {@link ru.jsonspecs.CompiledRules} is genuinely thread-safe and
 * immutable regardless of what the caller does afterward.
 *
 * <h3>Copying rules</h3>
 * <ul>
 *   <li>Scalar values ({@code String}, {@code Number}, {@code Boolean}, {@code null})
 *       are shared — they are already immutable in Java.</li>
 *   <li>{@code Map} values are recursively deep-copied and wrapped in
 *       {@link Collections#unmodifiableMap}.</li>
 *   <li>{@code List} values are recursively deep-copied and wrapped in
 *       {@link Collections#unmodifiableList}.</li>
 * </ul>
 *
 * <p><b>Internal API.</b>
 */
public final class ArtifactCopy {

    private ArtifactCopy() {}

    /**
     * Create a deep immutable copy of an artifact map.
     *
     * @param map the artifact map to copy (may be {@code null})
     * @return an unmodifiable deep copy; never {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> map) {
        if (map == null) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> m) return deepCopy((Map<String, Object>) m);
        if (v instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l.size());
            for (Object item : l) copy.add(copyValue(item));
            return Collections.unmodifiableList(copy);
        }
        // String, Number, Boolean — immutable; share the reference
        return v;
    }
}
