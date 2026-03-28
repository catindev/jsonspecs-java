package ru.jsonspecs.operators;

import java.util.Map;

/**
 * A check operator: validates a field and returns a structured result.
 *
 * <p>Implement this interface to add custom check operators.
 * The {@code rule} map is the full rule artifact (all JSON fields available).
 * Read the field name from {@code rule.get("field")} and look up the value
 * using {@code ctx.get(field)}.
 *
 * <pre>{@code
 * CheckOperator validInn = (rule, ctx) -> {
 *     DeepGet.Result r = ctx.get((String) rule.get("field"));
 *     if (!r.ok()) return CheckResult.fail();
 *     String inn = String.valueOf(r.value());
 *     return InnValidator.isValid(inn) ? CheckResult.ok() : CheckResult.fail(inn);
 * };
 * }</pre>
 */
@FunctionalInterface
public interface CheckOperator {
    CheckResult apply(Map<String, Object> rule, OperatorContext ctx);
}
