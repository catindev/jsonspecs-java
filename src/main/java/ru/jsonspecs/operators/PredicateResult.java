package ru.jsonspecs.operators;

/** Result returned by a {@link PredicateOperator}. */
public sealed interface PredicateResult
    permits PredicateResult.True, PredicateResult.False,
            PredicateResult.Undefined, PredicateResult.Exception {

    record True()              implements PredicateResult {}
    record False()             implements PredicateResult {}
    record Undefined()         implements PredicateResult {}
    record Exception(Throwable error) implements PredicateResult {}

    PredicateResult TRUE      = new True();
    PredicateResult FALSE     = new False();
    PredicateResult UNDEFINED = new Undefined();

    static PredicateResult exception(Throwable e) { return new Exception(e); }

    default boolean isTrue()      { return this instanceof True; }
    default boolean isFalse()     { return this instanceof False || this instanceof Undefined; }
    default boolean isException() { return this instanceof Exception; }
}
