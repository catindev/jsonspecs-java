package ru.jsonspecs.compiler;

import ru.jsonspecs.operators.OperatorPack;

import java.util.Map;

/**
 * Opaque compiled artifact bundle returned by {@code Engine.compile()}.
 *
 * <p>Thread-safe and reusable. Compile once, run many times.
 *
 * <p><b>Internal API.</b> This class is an implementation detail of the jsonspecs engine.
 * It is not part of the stable public API and may change without notice between versions.
 * Use {@link ru.jsonspecs.Engine} as the only entry point.
 */
public record Compiled(
    Map<String, Map<String, Object>> registry,
    Map<String, Map<String, Object>> dictionaries,
    Map<String, CompiledPipeline>    pipelines,
    Map<String, CompiledCondition>   conditions,
    OperatorPack                     operators,
    Map<String, String>              sources
) {}
