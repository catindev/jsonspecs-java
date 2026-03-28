package ru.jsonspecs;

import ru.jsonspecs.compiler.Compiled;
import ru.jsonspecs.compiler.Compiler;
import ru.jsonspecs.operators.OperatorPack;
import ru.jsonspecs.util.DeepGet;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main entry point for jsonspecs.
 *
 * <h2>Stable public API</h2>
 * <ul>
 *   <li>{@link #create(OperatorPack)}</li>
 *   <li>{@link #compile(List)}, {@link #compile(List, CompileOptions)}</li>
 *   <li>{@link #runPipeline(Compiled, String, Map)},
 *       {@link #runPipeline(Compiled, String, Map, RunOptions)}</li>
 *   <li>{@link OperatorPack#standard()}</li>
 *   <li>{@link CompilationException}</li>
 *   <li>{@link DeepGet#get(Map, String)}</li>
 * </ul>
 *
 * <p>Everything under internal packages is not part of the stable API.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Engine engine = Engine.create(OperatorPack.standard());
 * Compiled compiled = engine.compile(artifacts);
 * PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload);
 * if (!result.isOk()) {
 *     result.getIssues().forEach(i ->
 *         System.out.println(i.getCode() + ": " + i.getMessage()));
 * }
 * }</pre>
 */
public final class Engine {

    private final Compiler compiler;

    private Engine(OperatorPack operators) {
        this.compiler = new Compiler(Objects.requireNonNull(operators, "operators must not be null"));
    }

    /**
     * Create an engine bound to the given operator pack.
     *
     * @param operators use {@link OperatorPack#standard()} for built-in operators
     */
    public static Engine create(OperatorPack operators) {
        return new Engine(operators);
    }

    /**
     * Compile a list of artifact maps.
     *
     * @throws CompilationException with the full error list if any artifact is invalid
     */
    public Compiled compile(List<Map<String, Object>> artifacts) {
        return compiler.compile(artifacts, CompileOptions.defaults());
    }

    /**
     * Compile with explicit options (e.g. source file map for better error messages).
     */
    public Compiled compile(List<Map<String, Object>> artifacts, CompileOptions options) {
        return compiler.compile(artifacts, options);
    }

    /**
     * Execute a named pipeline against a payload.
     * Never throws — engine faults are returned as {@code status = ABORT}.
     */
    public PipelineResult runPipeline(Compiled compiled, String pipelineId,
                                       Map<String, Object> payload) {
        return Runner.runPipeline(compiled, pipelineId, payload, RunOptions.DEFAULT);
    }

    /**
     * Execute a named pipeline with explicit run options.
     *
     * @param options use {@link RunOptions#NO_TRACE} to suppress trace collection
     */
    public PipelineResult runPipeline(Compiled compiled, String pipelineId,
                                       Map<String, Object> payload, RunOptions options) {
        return Runner.runPipeline(compiled, pipelineId, payload, options);
    }
}
