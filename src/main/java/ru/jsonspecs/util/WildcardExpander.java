package ru.jsonspecs.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands wildcard field patterns (containing {@code [*]}) to concrete keys
 * by matching them against the flat payload map's key set.
 *
 * <p>Example: pattern {@code "items[*].name"} matches keys
 * {@code "items[0].name"}, {@code "items[1].name"}, ... sorted by index.
 */
public final class WildcardExpander {

    private WildcardExpander() {}

    public static boolean isWildcard(String field) {
        return field != null && field.contains("[*]");
    }

    /**
     * Expand a wildcard pattern to all matching concrete keys in the payload,
     * sorted by their numeric index tuple.
     */
    public static List<String> expand(String pattern, Set<String> payloadKeys) {
        Pattern regex = wildcardToRegex(pattern);
        record Match(String key, List<Integer> indexes) {}

        List<Match> matches = new ArrayList<>();
        for (String key : payloadKeys) {
            Matcher m = regex.matcher(key);
            if (m.matches()) {
                List<Integer> indexes = new ArrayList<>();
                for (int g = 1; g <= m.groupCount(); g++) {
                    indexes.add(Integer.parseInt(m.group(g)));
                }
                matches.add(new Match(key, indexes));
            }
        }

        // Sort by index tuple (outer index first)
        matches.sort(Comparator.comparing(
            match -> match.indexes(),
            (a, b) -> {
                for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                    int ai = i < a.size() ? a.get(i) : 0;
                    int bi = i < b.size() ? b.get(i) : 0;
                    if (ai != bi) return Integer.compare(ai, bi);
                }
                return 0;
            }
        ));

        return matches.stream().map(Match::key).toList();
    }

    /** Convert a wildcard pattern to a regex that captures each [*] as a numeric group. */
    static Pattern wildcardToRegex(String pattern) {
        String[] parts = pattern.split("\\[\\*\\]", -1);
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            sb.append(Pattern.quote(parts[i]));
            if (i < parts.length - 1) sb.append("\\[(\\d+)\\]");
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }
}
