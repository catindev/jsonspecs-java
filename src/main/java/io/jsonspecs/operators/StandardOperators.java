package io.jsonspecs.operators;

import io.jsonspecs.util.DeepGet;
import io.jsonspecs.util.ValueComparator;
import io.jsonspecs.util.WildcardExpander;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Built-in operator pack. Registered via {@link OperatorPack#standard()}.
 *
 * <p>Each operator follows the contract:
 * <ul>
 *   <li>Read {@code rule.get("field")} for the target field path</li>
 *   <li>Use {@code ctx.get(field)} to fetch the value from the flat payload</li>
 *   <li>Return {@link CheckResult#ok()} or {@link CheckResult#fail(Object)}</li>
 *   <li>Wrap unexpected errors in {@link CheckResult#exception(Throwable)}</li>
 * </ul>
 */
public final class StandardOperators {

    private StandardOperators() {}

    static OperatorPack build() {
        Map<String, CheckOperator>     check     = new HashMap<>();
        Map<String, PredicateOperator> predicate = new HashMap<>();

        // ── simple checks ────────────────────────────────────────────────────

        check.put("not_empty", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                return isEmpty(r.value()) ? CheckResult.fail(r.value()) : CheckResult.ok();
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("is_empty", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.ok();
                return isEmpty(r.value()) ? CheckResult.ok() : CheckResult.fail(r.value());
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("equals", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                Object expected = rule.get("value");
                boolean match = looseEquals(r.value(), expected);
                return match ? CheckResult.ok() : CheckResult.fail(r.value());
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("not_equals", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                Object expected = rule.get("value");
                boolean match = looseEquals(r.value(), expected);
                return match ? CheckResult.fail(r.value()) : CheckResult.ok();
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("contains", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                String s = str(r.value());
                String sub = str(rule.get("value"));
                return s.contains(sub) ? CheckResult.ok() : CheckResult.fail(s);
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("matches_regex", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                String s       = str(r.value());
                String pattern = str(rule.get("value"));
                String flags   = rule.get("flags") instanceof String f ? f : "";
                // Pattern is pre-compiled at compile time; re-compile here is safe (cached by JVM)
                Pattern re = flags.isEmpty() ? Pattern.compile(pattern) : Pattern.compile(pattern, parseFlags(flags));
                return re.matcher(s).find() ? CheckResult.ok() : CheckResult.fail(s);
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("greater_than", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                Integer cmp = ValueComparator.compare(r.value(), rule.get("value"));
                return cmp != null && cmp > 0 ? CheckResult.ok() : CheckResult.fail(r.value());
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("less_than", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                Integer cmp = ValueComparator.compare(r.value(), rule.get("value"));
                return cmp != null && cmp < 0 ? CheckResult.ok() : CheckResult.fail(r.value());
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("length_equals", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                int len = str(r.value()).length();
                int expected = toInt(rule.get("value"));
                return len == expected ? CheckResult.ok() : CheckResult.fail(len);
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("length_max", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.ok();
                int len = str(r.value()).length();
                int max = toInt(rule.get("value"));
                return len <= max ? CheckResult.ok() : CheckResult.fail(len);
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("in_dictionary", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                String dictId = str(rule.get("dictionary"));
                Map<String, Object> dict = ctx.dictionaries().get(dictId);
                if (dict == null) return CheckResult.exception(new IllegalStateException("Dictionary not found: " + dictId));
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) dict.get("values");
                if (values == null) return CheckResult.exception(new IllegalStateException("Dictionary has no 'values': " + dictId));
                boolean found = values.stream().anyMatch(v -> looseEquals(v, r.value()));
                return found ? CheckResult.ok() : CheckResult.fail(r.value());
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("any_filled", (rule, ctx) -> {
            try {
                @SuppressWarnings("unchecked")
                List<String> fields = rule.get("fields") instanceof List<?> l
                    ? (List<String>) l : List.of();
                if (fields.isEmpty()) return CheckResult.exception(
                    new IllegalArgumentException("any_filled requires 'fields' array"));
                boolean ok = fields.stream().anyMatch(f -> {
                    var r = ctx.get(f);
                    return r.ok() && !isEmpty(r.value());
                });
                return ok ? CheckResult.ok() : CheckResult.fail();
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        // ── field vs field checks ─────────────────────────────────────────────

        check.put("field_equals_field", (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp == 0));
        check.put("field_not_equals_field", (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp != 0));
        check.put("field_greater_than_field", (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp > 0));
        check.put("field_less_than_field", (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp < 0));
        check.put("field_greater_or_equal_than_field", (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp >= 0));
        check.put("field_less_or_equal_than_field", (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp <= 0));

        // ── predicates (mirror of checks, returning PredicateResult) ─────────

        predicate.put("not_empty", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                return r.ok() && !isEmpty(r.value()) ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("is_empty", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                return !r.ok() || isEmpty(r.value()) ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("equals", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.UNDEFINED;
                return looseEquals(r.value(), rule.get("value")) ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("not_equals", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.UNDEFINED;
                return !looseEquals(r.value(), rule.get("value")) ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("contains", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.FALSE;
                return str(r.value()).contains(str(rule.get("value"))) ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("matches_regex", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.FALSE;
                String pattern = str(rule.get("value"));
                String flags   = rule.get("flags") instanceof String f ? f : "";
                Pattern re = flags.isEmpty() ? Pattern.compile(pattern) : Pattern.compile(pattern, parseFlags(flags));
                return re.matcher(str(r.value())).find() ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("greater_than", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(r.value(), rule.get("value"));
                return cmp != null && cmp > 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("less_than", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(r.value(), rule.get("value"));
                return cmp != null && cmp < 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("in_dictionary", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.FALSE;
                String dictId = str(rule.get("dictionary"));
                Map<String, Object> dict = ctx.dictionaries().get(dictId);
                if (dict == null) return PredicateResult.exception(new IllegalStateException("Dictionary not found: " + dictId));
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) dict.get("values");
                boolean found = values != null && values.stream().anyMatch(v -> looseEquals(v, r.value()));
                return found ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_equals_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var r2 = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !r2.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), r2.value());
                return cmp != null && cmp == 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_not_equals_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var r2 = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !r2.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), r2.value());
                return cmp != null && cmp != 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_greater_or_equal_than_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var r2 = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !r2.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), r2.value());
                return cmp != null && cmp >= 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_less_or_equal_than_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var r2 = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !r2.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), r2.value());
                return cmp != null && cmp <= 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        return new OperatorPack(check, predicate);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String field(Map<String, Object> rule) {
        return str(rule.get("field"));
    }

    static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    static boolean isEmpty(Object v) {
        return v == null || "".equals(v);
    }

    static boolean looseEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        // Compare as strings for mixed number/string cases
        return String.valueOf(a).equals(String.valueOf(b));
    }

    static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Expected integer, got: " + v); }
    }

    private static CheckResult fieldCompare(Map<String, Object> rule, OperatorContext ctx,
                                             java.util.function.Predicate<Integer> test) {
        try {
            var l = ctx.get(field(rule));
            var r = ctx.get(str(rule.get("value_field")));
            if (!l.ok() || !r.ok()) return CheckResult.fail();
            Integer cmp = ValueComparator.compare(l.value(), r.value());
            if (cmp == null) return CheckResult.fail(l.value());
            return test.test(cmp) ? CheckResult.ok() : CheckResult.fail(l.value());
        } catch (Exception e) { return CheckResult.exception(e); }
    }

    static int parseFlags(String flags) {
        int f = 0;
        if (flags.contains("i")) f |= Pattern.CASE_INSENSITIVE;
        if (flags.contains("m")) f |= Pattern.MULTILINE;
        if (flags.contains("s")) f |= Pattern.DOTALL;
        return f;
    }
}
