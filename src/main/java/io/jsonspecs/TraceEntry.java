package io.jsonspecs;

import java.time.Instant;
import java.util.Map;

/**
 * A single entry in the execution trace produced during pipeline execution.
 *
 * <p>Stable public API.
 */
public final class TraceEntry {

    private final String kind     = "TRACE";
    private final String message;
    private final String scope;
    private final Map<String, Object> data;
    private final String ts;

    public TraceEntry(String message, String scope, Map<String, Object> data) {
        this.message = message;
        this.scope   = scope;
        this.data    = data == null ? Map.of() : Map.copyOf(data);
        this.ts      = Instant.now().toString();
    }

    public String getKind()              { return kind; }
    public String getMessage()           { return message; }
    public String getScope()             { return scope; }
    public Map<String, Object> getData() { return data; }
    public String getTs()                { return ts; }

    @Override public String toString() {
        return ts + " [" + scope + "] " + message;
    }
}
