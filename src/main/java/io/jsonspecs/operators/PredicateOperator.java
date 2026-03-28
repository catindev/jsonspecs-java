package io.jsonspecs.operators;

import java.util.Map;

/**
 * A predicate operator: evaluates a condition and returns TRUE, FALSE, or UNDEFINED.
 * Predicates do not produce issues — they are used in condition {@code when} clauses.
 */
@FunctionalInterface
public interface PredicateOperator {
    PredicateResult apply(Map<String, Object> rule, OperatorContext ctx);
}
