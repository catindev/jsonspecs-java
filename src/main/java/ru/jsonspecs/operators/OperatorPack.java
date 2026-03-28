package ru.jsonspecs.operators;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A named collection of check and predicate operators.
 *
 * <p>Use {@link #standard()} to get the built-in operator pack,
 * then extend it with {@link #withCheck} or {@link #withPredicate}
 * to add custom operators.
 *
 * <pre>{@code
 * OperatorPack operators = OperatorPack.standard()
 *     .withCheck("valid_inn", myInnValidator)
 *     .withPredicate("is_pep", myPepChecker);
 * Engine engine = Engine.create(operators);
 * }</pre>
 */
public final class OperatorPack {

    private final Map<String, CheckOperator>     check;
    private final Map<String, PredicateOperator> predicate;

    public OperatorPack(Map<String, CheckOperator> check,
                        Map<String, PredicateOperator> predicate) {
        this.check     = Map.copyOf(Objects.requireNonNull(check));
        this.predicate = Map.copyOf(Objects.requireNonNull(predicate));
    }

    public Map<String, CheckOperator>     check()     { return check; }
    public Map<String, PredicateOperator> predicate() { return predicate; }

    public boolean hasCheck(String name)     { return check.containsKey(name); }
    public boolean hasPredicate(String name) { return predicate.containsKey(name); }

    public CheckOperator     getCheck(String name)     { return check.get(name); }
    public PredicateOperator getPredicate(String name) { return predicate.get(name); }

    /** Return a new OperatorPack with an additional check operator. */
    public OperatorPack withCheck(String name, CheckOperator op) {
        Map<String, CheckOperator> m = new HashMap<>(check);
        m.put(name, op);
        return new OperatorPack(m, predicate);
    }

    /** Return a new OperatorPack with an additional predicate operator. */
    public OperatorPack withPredicate(String name, PredicateOperator op) {
        Map<String, PredicateOperator> m = new HashMap<>(predicate);
        m.put(name, op);
        return new OperatorPack(check, m);
    }

    /** Built-in operator pack with all standard operators. */
    public static OperatorPack standard() {
        return StandardOperators.build();
    }
}
