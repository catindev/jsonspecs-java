package io.jsonspecs.compiler;

import java.util.List;

/** Compiled (pre-resolved) form of a pipeline artifact. */
public record CompiledPipeline(
    String id,
    boolean entrypoint,
    boolean strict,
    String strictCode,
    String strictMessage,
    List<String> requiredContext,
    List<CompiledStep> steps
) {}
