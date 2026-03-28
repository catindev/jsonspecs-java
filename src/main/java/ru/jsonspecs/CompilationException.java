package ru.jsonspecs;

import java.util.List;

/**
 * Thrown by {@link Engine#compile} when artifacts are invalid.
 * Contains the full list of all errors found — not just the first one.
 *
 * <p>Stable public API.
 *
 * <pre>{@code
 * try {
 *     Compiled compiled = engine.compile(artifacts);
 * } catch (CompilationException e) {
 *     e.getErrors().forEach(System.err::println);
 * }
 * }</pre>
 */
public final class CompilationException extends RuntimeException {

    private final List<String> errors;

    public CompilationException(List<String> errors) {
        super(buildMessage(errors));
        this.errors = List.copyOf(errors);
    }

    /** All error messages collected across all compilation phases. */
    public List<String> getErrors() { return errors; }

    private static String buildMessage(List<String> errors) {
        StringBuilder sb = new StringBuilder("Compilation failed with ")
            .append(errors.size()).append(" error(s):\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(errors.get(i)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
