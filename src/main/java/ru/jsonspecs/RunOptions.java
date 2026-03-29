package ru.jsonspecs;

/**
 * Options for {@link Engine#runPipeline}.
 *
 * <p>Stable public API.
 *
 * <h3>Predefined options</h3>
 * <ul>
 *   <li>{@link #DEFAULT}  — trace on, full diagnostics</li>
 *   <li>{@link #NO_TRACE} — trace off, full diagnostics</li>
 *   <li>{@link #SAFE}     — trace on, stack traces suppressed (for production embedding)</li>
 * </ul>
 */
public final class RunOptions {

    private final boolean trace;
    private final boolean safeMode;

    private RunOptions(boolean trace, boolean safeMode) {
        this.trace    = trace;
        this.safeMode = safeMode;
    }

    /** Default options: trace enabled, full diagnostics including stack traces on ABORT. */
    public static final RunOptions DEFAULT  = new RunOptions(true,  false);

    /**
     * Trace collection disabled.
     * Reduces overhead on hot paths where trace data is not used.
     * Stack traces on ABORT are still included.
     */
    public static final RunOptions NO_TRACE = new RunOptions(false, false);

    /**
     * Safe mode for production embedding: trace on, but ABORT results do not include
     * internal stack traces in {@link PipelineResult#getErrorStack()}.
     * The error message in {@link PipelineResult#getError()} is still included.
     *
     * <p>Use this when {@code PipelineResult} is serialised into API responses
     * or logs that should not expose internal engine internals.
     */
    public static final RunOptions SAFE     = new RunOptions(true,  true);

    /** Whether trace collection is enabled. */
    public boolean isTrace()    { return trace; }

    /**
     * Whether safe mode is enabled.
     * In safe mode, {@link PipelineResult#getErrorStack()} returns {@code null} on ABORT.
     */
    public boolean isSafeMode() { return safeMode; }

    /** Create options with explicit trace flag; safeMode is false. */
    public static RunOptions withTrace(boolean trace) {
        return new RunOptions(trace, false);
    }
}
