package io.jsonspecs.operators;

import java.util.List;

/**
 * Result returned by a {@link CheckOperator}.
 *
 * <p>Stable public API. Operators must return one of the listed permitted types.
 */
public sealed interface CheckResult
        permits CheckResult.Ok, CheckResult.Fail, CheckResult.Fails, CheckResult.Exception {

    record Ok() implements CheckResult {}

    record Fail(String field, Object actual) implements CheckResult {}

    /** Multiple per-element failures from a wildcard EACH aggregate. */
    record Fails(List<Fail> failures) implements CheckResult {}

    record Exception(Throwable error) implements CheckResult {}

    static CheckResult ok()                              { return new Ok(); }
    static CheckResult fail()                            { return new Fail(null, null); }
    static CheckResult fail(Object actual)               { return new Fail(null, actual); }
    static CheckResult fail(String field, Object actual) { return new Fail(field, actual); }
    static CheckResult exception(Throwable e)            { return new Exception(e); }
}
