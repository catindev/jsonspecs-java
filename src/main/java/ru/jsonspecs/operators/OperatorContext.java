package ru.jsonspecs.operators;

import ru.jsonspecs.util.DeepGet;

import java.util.Map;
import java.util.Set;

/**
 * Context passed to every operator at runtime.
 *
 * <h3>Stable operator contract (mirrors jsonspecs JS v1.1.0)</h3>
 * <p>Custom operators should interact with the payload through the helpers
 * on this class rather than working with {@link #payload()} directly:
 *
 * <ul>
 *   <li>{@link #get(String)} — look up a field; returns a result object with
 *       {@code ok} and {@code value}. Supports {@code $context.*} paths.</li>
 *   <li>{@link #has(String)} — returns {@code true} when the field is present
 *       in the payload. Equivalent to {@code get(path).ok()}.</li>
 *   <li>{@link #getDictionary(String)} — look up a compiled dictionary by id.</li>
 *   <li>{@link #payloadKeys()} — the set of flat payload keys. Useful for wildcard
 *       expansion in advanced operators.</li>
 *   <li>{@link #payload()} — the raw flat map. Available for diagnostics and
 *       advanced use cases. Prefer {@link #get(String)} / {@link #has(String)}
 *       in new operators.</li>
 * </ul>
 *
 * <h3>Example — custom check operator</h3>
 * <pre>{@code
 * OperatorPack ops = OperatorPack.standard()
 *     .withCheck("valid_inn", (rule, ctx) -> {
 *         DeepGet.Result r = ctx.get((String) rule.get("field"));
 *         if (!r.ok()) return CheckResult.fail();
 *         String inn = String.valueOf(r.value());
 *         // ... validate INN checksum ...
 *         return valid ? CheckResult.ok() : CheckResult.fail(inn);
 *     });
 * }</pre>
 *
 * <h3>Example — using has() as a guard</h3>
 * <pre>{@code
 * .withPredicate("is_resident", (rule, ctx) -> {
 *     if (!ctx.has((String) rule.get("field"))) return PredicateResult.UNDEFINED;
 *     // ... evaluate ...
 * });
 * }</pre>
 */
public record OperatorContext(
    Map<String, Object>              payload,
    Map<String, Map<String, Object>> dictionaries
) {
    /**
     * Look up a field in the flat payload.
     *
     * <p>Supports {@code $context.*} prefix for runtime context fields
     * stored under the {@code __context} key.
     *
     * @param field dot-notation field path, e.g. {@code "person.firstName"} or
     *              {@code "$context.merchantId"}
     * @return a result object; call {@code .ok()} to check presence and {@code .value()}
     *         to retrieve the value
     */
    public DeepGet.Result get(String field) {
        return DeepGet.get(payload, field);
    }

    /**
     * Check whether a field is present in the flat payload.
     *
     * <p>Equivalent to {@code get(field).ok()}. Useful as a lightweight guard
     * before performing more expensive operations.
     *
     * @param field dot-notation field path, optionally prefixed with {@code $context.}
     * @return {@code true} if the field exists (value may be {@code null});
     *         {@code false} if the field is absent
     */
    public boolean has(String field) {
        return DeepGet.get(payload, field).ok();
    }

    /**
     * Look up a compiled dictionary artifact by id.
     *
     * @param id the dictionary artifact id (e.g. {@code "document_type_codes"})
     * @return the dictionary artifact map, or {@code null} when no dictionary
     *         with the given id exists in the compiled bundle
     */
    public Map<String, Object> getDictionary(String id) {
        return dictionaries.get(id);
    }

    /**
     * The set of flat payload keys available in the current execution.
     *
     * <p>Useful for advanced operators that need to enumerate fields or
     * implement their own wildcard expansion logic.
     *
     * <p>The {@code __context} key is excluded — use {@link #get(String)} with
     * a {@code $context.*} path to access context fields.
     */
    public Set<String> payloadKeys() {
        return payload.keySet();
    }
}
