package io.jsonspecs.compiler;

import io.jsonspecs.operators.OperatorPack;

import java.util.Map;

/**
 * Opaque compiled artifact bundle returned by {@code Engine.compile()}.
 *
 * <p>Thread-safe and reusable. Compile once, run many times.
 */
public record Compiled(
    Map<String, Map<String, Object>> registry,
    Map<String, Map<String, Object>> dictionaries,
    Map<String, CompiledPipeline>    pipelines,
    Map<String, CompiledCondition>   conditions,
    OperatorPack                     operators,
    Map<String, String>              sources
) {}
