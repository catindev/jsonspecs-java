package io.jsonspecs;

import java.util.List;
import java.util.Objects;

/**
 * Result of {@link Engine#runPipeline}.
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code OK} — all checks passed</li>
 *   <li>{@code OK_WITH_WARNINGS} — passed but WARNING-level issues present</li>
 *   <li>{@code ERROR} — one or more ERROR-level issues; pipeline ran to completion</li>
 *   <li>{@code EXCEPTION} — an EXCEPTION-level issue stopped the pipeline early</li>
 *   <li>{@code ABORT} — unexpected engine fault; {@link #getError()} contains details</li>
 * </ul>
 *
 * <p>Stable public API.
 */
public final class PipelineResult {

    public enum Status  { OK, OK_WITH_WARNINGS, ERROR, EXCEPTION, ABORT }
    public enum Control { CONTINUE, STOP }

    private final Status         status;
    private final Control        control;
    private final List<Issue>    issues;
    private final List<TraceEntry> trace;
    private final String         errorMessage;
    private final String         errorStack;

    private PipelineResult(Builder b) {
        this.status       = Objects.requireNonNull(b.status);
        this.control      = Objects.requireNonNull(b.control);
        this.issues       = List.copyOf(b.issues);
        this.trace        = List.copyOf(b.trace);
        this.errorMessage = b.errorMessage;
        this.errorStack   = b.errorStack;
    }

    public Status          getStatus()       { return status; }
    public Control         getControl()      { return control; }
    public List<Issue>     getIssues()       { return issues; }
    public List<TraceEntry> getTrace()       { return trace; }
    /** Non-null only when {@code status == ABORT}. */
    public String          getError()        { return errorMessage; }
    public String          getErrorStack()   { return errorStack; }

    public boolean isOk()       { return status == Status.OK || status == Status.OK_WITH_WARNINGS; }
    public boolean hasErrors()  { return status == Status.ERROR || status == Status.EXCEPTION; }

    @Override public String toString() {
        return "PipelineResult{status=" + status + ", control=" + control +
               ", issues=" + issues.size() + ", trace=" + trace.size() + "}";
    }

    static Builder builder() { return new Builder(); }

    static final class Builder {
        Status status;
        Control control;
        List<Issue> issues = List.of();
        List<TraceEntry> trace = List.of();
        String errorMessage, errorStack;

        Builder status(Status v)            { this.status = v; return this; }
        Builder control(Control v)          { this.control = v; return this; }
        Builder issues(List<Issue> v)       { this.issues = v; return this; }
        Builder trace(List<TraceEntry> v)   { this.trace = v; return this; }
        Builder errorMessage(String v)      { this.errorMessage = v; return this; }
        Builder errorStack(String v)        { this.errorStack = v; return this; }
        PipelineResult build()              { return new PipelineResult(this); }
    }
}
