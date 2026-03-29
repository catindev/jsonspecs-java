package ru.jsonspecs.operators;

import ru.jsonspecs.util.RegexFlags;
import ru.jsonspecs.util.ValueComparator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Built-in operator pack. Registered via {@link OperatorPack#standard()}.
 *
 * <p><b>Internal API.</b>
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
                return strictEquals(r.value(), rule.get("value"))
                    ? CheckResult.ok() : CheckResult.fail(r.value());
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("not_equals", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                return strictEquals(r.value(), rule.get("value"))
                    ? CheckResult.fail(r.value()) : CheckResult.ok();
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("contains", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                String s = str(r.value()), sub = str(rule.get("value"));
                return s.contains(sub) ? CheckResult.ok() : CheckResult.fail(s);
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("matches_regex", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                String s = str(r.value()), pattern = str(rule.get("value"));
                String flags = rule.get("flags") instanceof String f ? f : "";
                Pattern re = flags.isEmpty() ? Pattern.compile(pattern)
                    : Pattern.compile(pattern, RegexFlags.parse(flags));
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
                int len = str(r.value()).length(), expected = toInt(rule.get("value"));
                return len == expected ? CheckResult.ok() : CheckResult.fail(len);
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        // FIX: absent field → FAIL (was incorrectly OK)
        check.put("length_max", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                int len = str(r.value()).length(), max = toInt(rule.get("value"));
                return len <= max ? CheckResult.ok() : CheckResult.fail(len);
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        // FIX: dictionary is {type,id} object; entries key (not values); object entry formats
        check.put("in_dictionary", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return CheckResult.fail();
                String dictId = resolveDictId(rule);
                Map<String, Object> dict = ctx.getDictionary(dictId);
                if (dict == null)
                    return CheckResult.exception(new IllegalStateException("Dictionary not found: " + dictId));
                @SuppressWarnings("unchecked")
                List<Object> entries = (List<Object>) dict.get("entries");
                if (entries == null)
                    return CheckResult.exception(new IllegalStateException("Dictionary has no 'entries': " + dictId));
                return entries.stream().anyMatch(e -> matchEntry(e, r.value()))
                    ? CheckResult.ok() : CheckResult.fail(r.value());
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        check.put("any_filled", (rule, ctx) -> {
            try {
                @SuppressWarnings("unchecked")
                List<String> fields = rule.get("fields") instanceof List<?> l
                    ? (List<String>) l : List.of();
                if (fields.isEmpty())
                    return CheckResult.exception(
                        new IllegalArgumentException("any_filled requires 'fields' array"));
                boolean ok = fields.stream().anyMatch(f -> {
                    var res = ctx.get(f);
                    return res.ok() && !isEmpty(res.value());
                });
                return ok ? CheckResult.ok() : CheckResult.fail();
            } catch (Exception e) { return CheckResult.exception(e); }
        });

        // ── field vs field checks ─────────────────────────────────────────────

        check.put("field_equals_field",                (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp == 0));
        check.put("field_not_equals_field",            (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp != 0));
        check.put("field_greater_than_field",          (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp > 0));
        check.put("field_less_than_field",             (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp < 0));
        check.put("field_greater_or_equal_than_field", (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp >= 0));
        check.put("field_less_or_equal_than_field",    (rule, ctx) -> fieldCompare(rule, ctx, cmp -> cmp <= 0));

        // ── predicates ────────────────────────────────────────────────────────

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
                return strictEquals(r.value(), rule.get("value"))
                    ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("not_equals", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.UNDEFINED;
                return !strictEquals(r.value(), rule.get("value"))
                    ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("contains", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.FALSE;
                return str(r.value()).contains(str(rule.get("value")))
                    ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("matches_regex", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.FALSE;
                String pattern = str(rule.get("value"));
                String flags = rule.get("flags") instanceof String f ? f : "";
                Pattern re = flags.isEmpty() ? Pattern.compile(pattern)
                    : Pattern.compile(pattern, RegexFlags.parse(flags));
                return re.matcher(str(r.value())).find()
                    ? PredicateResult.TRUE : PredicateResult.FALSE;
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

        // FIX: uses resolveDictId + entries + matchEntry
        predicate.put("in_dictionary", (rule, ctx) -> {
            try {
                var r = ctx.get(field(rule));
                if (!r.ok()) return PredicateResult.FALSE;
                String dictId = resolveDictId(rule);
                Map<String, Object> dict = ctx.getDictionary(dictId);
                if (dict == null)
                    return PredicateResult.exception(new IllegalStateException("Dictionary not found: " + dictId));
                @SuppressWarnings("unchecked")
                List<Object> entries = (List<Object>) dict.get("entries");
                boolean found = entries != null && entries.stream().anyMatch(e -> matchEntry(e, r.value()));
                return found ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_equals_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var rv = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !rv.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), rv.value());
                return cmp != null && cmp == 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_not_equals_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var rv = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !rv.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), rv.value());
                return cmp != null && cmp != 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_greater_or_equal_than_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var rv = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !rv.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), rv.value());
                return cmp != null && cmp >= 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        predicate.put("field_less_or_equal_than_field", (rule, ctx) -> {
            try {
                var l = ctx.get(field(rule)); var rv = ctx.get(str(rule.get("value_field")));
                if (!l.ok() || !rv.ok()) return PredicateResult.UNDEFINED;
                Integer cmp = ValueComparator.compare(l.value(), rv.value());
                return cmp != null && cmp <= 0 ? PredicateResult.TRUE : PredicateResult.FALSE;
            } catch (Exception e) { return PredicateResult.exception(e); }
        });

        return new OperatorPack(check, predicate);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String field(Map<String, Object> rule) { return str(rule.get("field")); }

    /**
     * Extract dictionary id from rule's {@code "dictionary"} field.
     * Canonical form: {@code {"type":"static","id":"currencies"}}.
     * Also accepts plain string id as fallback.
     */
    @SuppressWarnings("unchecked")
    private static String resolveDictId(Map<String, Object> rule) {
        Object raw = rule.get("dictionary");
        if (raw instanceof Map<?, ?> m) return str(((Map<String, Object>) m).get("id"));
        return str(raw);
    }

    /**
     * Match a dictionary entry against a field value.
     * Supports three entry forms: plain scalar, {@code {"code":...}}, {@code {"value":...}}.
     */
    @SuppressWarnings("unchecked")
    static boolean matchEntry(Object entry, Object value) {
        if (entry instanceof Map<?, ?> m) {
            var em = (Map<String, Object>) m;
            if (em.containsKey("code"))  return strictEquals(em.get("code"),  value);
            if (em.containsKey("value")) return strictEquals(em.get("value"), value);
            return false;
        }
        return strictEquals(entry, value);
    }

    static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    static boolean isEmpty(Object v) { return v == null || "".equals(v); }

    /**
     * Strict equality — mirrors JS {@code ===}:
     * no string-to-number coercion, but cross-numeric-type comparison is supported
     * ({@code Integer(1) == Long(1) == Double(1.0)}).
     */
    static boolean strictEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (a instanceof Number na && b instanceof Number nb)
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        return false;
    }

    static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) throw new IllegalArgumentException("Expected integer, got null");
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Expected integer, got: " + v); }
    }

    private static CheckResult fieldCompare(Map<String, Object> rule, OperatorContext ctx,
                                             Predicate<Integer> test) {
        try {
            var l = ctx.get(field(rule));
            var r = ctx.get(str(rule.get("value_field")));
            if (!l.ok() || !r.ok()) return CheckResult.fail();
            Integer cmp = ValueComparator.compare(l.value(), r.value());
            if (cmp == null) return CheckResult.fail(l.value());
            return test.test(cmp) ? CheckResult.ok() : CheckResult.fail(l.value());
        } catch (Exception e) { return CheckResult.exception(e); }
    }
}
