package ru.jsonspecs;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link Engine#runPipeline}.
 *
 * <h3>Status values</h3>
 * <ul>
 *   <li>{@code OK}               — all checks passed; no issues of any level.</li>
 *   <li>{@code OK_WITH_WARNINGS} — all required checks passed, but WARNING-level issues
 *       were raised. {@link #getControl()} returns {@code CONTINUE}.</li>
 *   <li>{@code ERROR}            — one or more ERROR-level issues were raised. The pipeline
 *       executed all steps (ERROR does not stop execution). {@link #getControl()} returns
 *       {@code STOP} to signal that the calling orchestrator should halt its own flow.</li>
 *   <li>{@code EXCEPTION}        — an EXCEPTION-level rule or strict pipeline boundary fired,
 *       stopping execution immediately. Subsequent steps were not evaluated.
 *       {@link #getControl()} returns {@code STOP}.</li>
 *   <li>{@code ABORT}            — an unexpected engine fault occurred (operator threw,
 *       missing artifact at runtime, etc.). {@link #getError()} contains the message.
 *       {@link #getErrorStack()} contains the stack trace unless safe mode is enabled.
 *       {@link #getControl()} returns {@code STOP}.</li>
 * </ul>
 *
 * <h3>Control signal</h3>
 * <p>{@link #getControl()} is a convenience for orchestrators: {@code CONTINUE} means the
 * payload is acceptable and the orchestrator flow may proceed; {@code STOP} means it should
 * not. {@code OK} and {@code OK_WITH_WARNINGS} both yield {@code CONTINUE}; everything
 * else yields {@code STOP}.
 *
 * <p>Stable public API.
 */
public final class PipelineResult {

    public enum Status  { OK, OK_WITH_WARNINGS, ERROR, EXCEPTION, ABORT }
    public enum Control { CONTINUE, STOP }

    private final Status           status;
    private final Control          control;
    private final List<Issue>      issues;
    private final List<TraceEntry> trace;
    private final String           errorMessage;
    private final String           errorStack;

    private PipelineResult(Builder b) {
        this.status       = Objects.requireNonNull(b.status);
        this.control      = Objects.requireNonNull(b.control);
        this.issues       = List.copyOf(b.issues);
        this.trace        = List.copyOf(b.trace);
        this.errorMessage = b.errorMessage;
        this.errorStack   = b.errorStack;
    }

    public Status           getStatus()     { return status; }
    public Control          getControl()    { return control; }
    public List<Issue>      getIssues()     { return issues; }
    public List<TraceEntry> getTrace()      { return trace; }

    /**
     * Non-null only when {@code status == ABORT}.
     * Contains a short description of the engine fault.
     */
    public String getError()      { return errorMessage; }

    /**
     * Non-null only when {@code status == ABORT} and safe mode is not enabled.
     * Contains the full stack trace of the engine fault.
     * Will be {@code null} when {@link RunOptions#SAFE} is used.
     *
     * @see RunOptions#SAFE
     */
    public String getErrorStack() { return errorStack; }

    /** {@code true} for {@code OK} and {@code OK_WITH_WARNINGS}. */
    public boolean isOk()      { return status == Status.OK || status == Status.OK_WITH_WARNINGS; }

    /** {@code true} for {@code ERROR} and {@code EXCEPTION}. */
    public boolean hasErrors() { return status == Status.ERROR || status == Status.EXCEPTION; }

    @Override public String toString() {
        return "PipelineResult{status=" + status + ", control=" + control +
               ", issues=" + issues.size() + ", trace=" + trace.size() + "}";
    }

    static Builder builder() { return new Builder(); }

    static final class Builder {
        Status status;
        Control control;
        List<Issue>      issues = List.of();
        List<TraceEntry> trace  = List.of();
        String errorMessage, errorStack;

        Builder status(Status v)            { this.status = v;       return this; }
        Builder control(Control v)          { this.control = v;      return this; }
        Builder issues(List<Issue> v)       { this.issues = v;       return this; }
        Builder trace(List<TraceEntry> v)   { this.trace = v;        return this; }
        Builder errorMessage(String v)      { this.errorMessage = v; return this; }
        Builder errorStack(String v)        { this.errorStack = v;   return this; }
        PipelineResult build()              { return new PipelineResult(this); }
    }
}
