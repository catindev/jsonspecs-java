package ru.jsonspecs.compiler;

import java.util.List;

/** Compiled (pre-resolved) form of a pipeline artifact. *
 * <p><b>Internal API.</b> This class is an implementation detail of the jsonspecs engine.
 * It is not part of the stable public API and may change without notice between versions.
 * Use {@link ru.jsonspecs.Engine} as the only entry point.
 */
public record CompiledPipeline(
    String id,
    boolean entrypoint,
    boolean strict,
    String strictCode,
    String strictMessage,
    List<String> requiredContext,
    List<CompiledStep> steps
) {}
