package ru.jsonspecs.util;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Converts values to a comparable form for {@code greater_than}, {@code less_than},
 * and {@code field_*_field} operators.
 *
 * <p>Tries numeric parsing first, then ISO date (YYYY-MM-DD). Returns null if neither works.
 */
public final class ValueComparator {

    private ValueComparator() {}

    public sealed interface Comparable permits Comparable.Num, Comparable.Date {
        record Num(double value)     implements Comparable {}
        record Date(LocalDate value) implements Comparable {}
    }

    /** Convert a raw value to a Comparable, or null if not numeric/date. */
    public static Comparable toComparable(Object raw) {
        if (raw == null) return null;
        // Try number
        if (raw instanceof Number n) return new Comparable.Num(n.doubleValue());
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return new Comparable.Num(Double.parseDouble(s));
        } catch (NumberFormatException ignored) {}
        // Try ISO date
        try {
            return new Comparable.Date(LocalDate.parse(s));
        } catch (DateTimeParseException ignored) {}
        return null;
    }

    /** Returns negative, zero, or positive. Returns null if not comparable. */
    public static Integer compare(Object left, Object right) {
        Comparable l = toComparable(left);
        Comparable r = toComparable(right);
        if (l == null || r == null) return null;
        if (l instanceof Comparable.Num ln && r instanceof Comparable.Num rn) {
            return Double.compare(ln.value(), rn.value());
        }
        if (l instanceof Comparable.Date ld && r instanceof Comparable.Date rd) {
            return ld.value().compareTo(rd.value());
        }
        return null; // mixed types
    }
}
