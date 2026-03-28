package ru.jsonspecs;

import ru.jsonspecs.compiler.Compiled;

/**
 * An opaque, immutable, thread-safe handle to a compiled artifact bundle.
 *
 * <p>Returned by {@link Engine#compile}. Pass to {@link Engine#runPipeline}.
 *
 * <p><b>Stable public API.</b> Covered by semantic versioning.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Compile once — reuse for every request
 * CompiledRules compiled = engine.compile(artifacts);
 *
 * // Run many times, safe to share across threads
 * PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload);
 * }</pre>
 */
public final class CompiledRules {

    private final Compiled internal;

    CompiledRules(Compiled internal) {
        this.internal = internal;
    }

    /**
     * Returns the internal compiled bundle.
     * Package-private — only {@link Engine} and {@link Runner} need this.
     */
    Compiled internal() {
        return internal;
    }
}
