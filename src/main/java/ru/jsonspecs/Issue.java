package ru.jsonspecs;

import java.util.Objects;

/**
 * A single validation issue produced during pipeline execution.
 *
 * <p>Stable public API.
 */
public final class Issue {

    private final String kind     = "ISSUE";
    private final String level;   // WARNING | ERROR | EXCEPTION
    private final String code;
    private final String message;
    private final String field;
    private final String ruleId;
    private final Object expected;
    private final Object actual;
    private final String stepId;
    private final String pipelineId;

    private Issue(Builder b) {
        this.level      = Objects.requireNonNull(b.level, "level");
        this.code       = b.code;
        this.message    = b.message;
        this.field      = b.field;
        this.ruleId     = b.ruleId;
        this.expected   = b.expected;
        this.actual     = b.actual;
        this.stepId     = b.stepId;
        this.pipelineId = b.pipelineId;
    }

    public String getKind()       { return kind; }
    public String getLevel()      { return level; }
    public String getCode()       { return code; }
    public String getMessage()    { return message; }
    public String getField()      { return field; }
    public String getRuleId()     { return ruleId; }
    public Object getExpected()   { return expected; }
    public Object getActual()     { return actual; }
    public String getStepId()     { return stepId; }
    public String getPipelineId() { return pipelineId; }

    @Override public String toString() {
        return "[" + level + "] " + code + " on " + field + ": " + message;
    }

    public static Builder builder(String level) { return new Builder(level); }

    public static final class Builder {
        private final String level;
        private String code, message, field, ruleId, stepId, pipelineId;
        private Object expected, actual;

        private Builder(String level) { this.level = level; }

        public Builder code(String v)       { this.code = v; return this; }
        public Builder message(String v)    { this.message = v; return this; }
        public Builder field(String v)      { this.field = v; return this; }
        public Builder ruleId(String v)     { this.ruleId = v; return this; }
        public Builder expected(Object v)   { this.expected = v; return this; }
        public Builder actual(Object v)     { this.actual = v; return this; }
        public Builder stepId(String v)     { this.stepId = v; return this; }
        public Builder pipelineId(String v) { this.pipelineId = v; return this; }
        public Issue build()                { return new Issue(this); }
    }
}
