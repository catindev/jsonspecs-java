package ru.jsonspecs;

/**
 * Options for {@link Engine#runPipeline}.
 *
 * <p>Stable public API.
 */
public final class RunOptions {

    private final boolean trace;

    private RunOptions(boolean trace) { this.trace = trace; }

    /** Default options: trace enabled. */
    public static final RunOptions DEFAULT = new RunOptions(true);

    /** Options with trace collection disabled (reduces overhead on hot paths). */
    public static final RunOptions NO_TRACE = new RunOptions(false);

    public boolean isTrace() { return trace; }

    public static RunOptions withTrace(boolean trace) { return new RunOptions(trace); }
}
