package io.jsonspecs.compiler;

import io.jsonspecs.CompilationException;
import io.jsonspecs.CompileOptions;
import io.jsonspecs.operators.OperatorPack;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles a list of artifact maps into an executable {@link Compiled} bundle.
 *
 * <h3>Compilation phases</h3>
 * <ol>
 *   <li>Build registry — deduplication, build maps, collect dictionaries</li>
 *   <li>Validate schema — per-artifact structural validation</li>
 *   <li>Validate code uniqueness — duplicate error codes across check rules</li>
 *   <li>Validate refs — resolve all cross-artifact references</li>
 *   <li>Build conditions — compile-time normalization of conditions</li>
 *   <li>Build pipelines — compile-time normalization of pipelines</li>
 *   <li>Validate DAG — cycle detection in pipeline call graph</li>
 * </ol>
 *
 * <p>Each phase accumulates all errors before throwing, so callers receive
 * the complete list of issues rather than just the first one.
 */
public final class Compiler {

    private final OperatorPack operators;

    public Compiler(OperatorPack operators) {
        this.operators = Objects.requireNonNull(operators);
    }

    @SuppressWarnings("unchecked")
    public Compiled compile(List<Map<String, Object>> artifacts, CompileOptions options) {
        Map<String, String> sources = options != null && options.sources() != null
            ? options.sources() : Map.of();

        // Phase 1: registry
        var phase1 = buildRegistry(artifacts);
        throwIfErrors(phase1.errors());

        var registry     = phase1.registry();
        var dictionaries = phase1.dictionaries();

        // Phase 2: schema
        var schemaErrors = validateSchema(artifacts, dictionaries, sources);
        throwIfErrors(schemaErrors);

        // Phase 3: code uniqueness
        var codeErrors = validateCodeUniqueness(artifacts, sources);
        throwIfErrors(codeErrors);

        // Phase 4: refs
        var refErrors = validateRefs(artifacts, registry, sources);
        throwIfErrors(refErrors);

        // Phase 5: build conditions
        var conditions = buildConditions(artifacts);

        // Phase 6: build pipelines
        var pipelines = buildPipelines(artifacts);

        // Phase 7: DAG
        var dagErrors = validateDAG(registry, pipelines, conditions);
        throwIfErrors(dagErrors);

        return new Compiled(
            Collections.unmodifiableMap(registry),
            Collections.unmodifiableMap(dictionaries),
            Collections.unmodifiableMap(pipelines),
            Collections.unmodifiableMap(conditions),
            operators,
            sources
        );
    }

    // ── Phase 1: Registry ─────────────────────────────────────────────────────

    record Phase1Result(
        Map<String, Map<String, Object>> registry,
        Map<String, Map<String, Object>> dictionaries,
        List<String> errors
    ) {}

    @SuppressWarnings("unchecked")
    private Phase1Result buildRegistry(List<Map<String, Object>> artifacts) {
        Map<String, Map<String, Object>> registry     = new LinkedHashMap<>();
        Map<String, Map<String, Object>> dictionaries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (var a : artifacts) {
            String id   = str(a.get("id"));
            String type = str(a.get("type"));

            if (id.isBlank()) { errors.add("Artifact must have non-empty id"); continue; }
            if (type.isBlank()) { errors.add("Artifact " + id + " must have type"); continue; }
            if (!a.containsKey("description") || str(a.get("description")).isBlank())
                errors.add("Artifact " + id + " must have description");
            if (registry.containsKey(id)) { errors.add("Duplicate artifact id: " + id); continue; }
            registry.put(id, a);
            if ("dictionary".equals(type)) dictionaries.put(id, a);
        }
        return new Phase1Result(registry, dictionaries, errors);
    }

    // ── Phase 2: Schema ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> validateSchema(List<Map<String, Object>> artifacts,
                                         Map<String, Map<String, Object>> dictionaries,
                                         Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        Set<String> validLevels = Set.of("WARNING", "ERROR", "EXCEPTION");

        for (var a : artifacts) {
            String id   = str(a.get("id"));
            String type = str(a.get("type"));
            String loc  = where(id, sources);

            switch (type) {
                case "rule" -> {
                    String role = str(a.get("role"));
                    if (!Set.of("check", "predicate").contains(role))
                        errors.add("Rule " + loc + ": role must be 'check' or 'predicate'");

                    if (str(a.get("operator")).isBlank())
                        errors.add("Rule " + loc + ": operator is required");
                    else {
                        String op = str(a.get("operator"));
                        boolean exists = "check".equals(role)
                            ? operators.hasCheck(op)
                            : operators.hasPredicate(op);
                        if (!exists)
                            errors.add("Rule " + loc + ": unknown " + role + " operator '" + op + "'");
                    }

                    if ("check".equals(role)) {
                        String level = str(a.get("level"));
                        if (!validLevels.contains(level))
                            errors.add("Check rule " + loc + ": level must be WARNING|ERROR|EXCEPTION");
                        if (str(a.get("code")).isBlank())
                            errors.add("Check rule " + loc + ": code required");
                        if (str(a.get("message")).isBlank())
                            errors.add("Check rule " + loc + ": message required");
                    }

                    // Validate regex at compile time
                    if ("matches_regex".equals(a.get("operator"))) {
                        String val = str(a.get("value"));
                        if (val.isBlank()) {
                            errors.add("Rule " + loc + ": matches_regex requires value (regex string)");
                        } else {
                            try {
                                String flags = a.get("flags") instanceof String f ? f : "";
                                int flagBits = parseFlags(flags);
                                Pattern.compile(val, flagBits);
                            } catch (PatternSyntaxException e) {
                                errors.add("Rule " + loc + ": matches_regex has invalid regex pattern — " + e.getMessage());
                            }
                        }
                    }
                }

                case "pipeline" -> {
                    Object flow = a.get("flow");
                    if (!(flow instanceof List<?> f) || f.isEmpty())
                        errors.add("Pipeline " + loc + ": flow must be non-empty array");
                    if (!(a.get("strict") instanceof Boolean))
                        errors.add("Pipeline " + loc + ": strict must be explicitly set to true|false");
                    if (!(a.get("entrypoint") instanceof Boolean))
                        errors.add("Pipeline " + loc + ": entrypoint must be explicitly set to true|false");
                    if (Boolean.TRUE.equals(a.get("strict"))) {
                        if (str(a.get("message")).isBlank())
                            errors.add("Pipeline " + loc + ": message is required when strict=true");
                    }
                    if (flow instanceof List<?> steps) {
                        for (var s : steps) {
                            if (!(s instanceof Map<?, ?> sm)) {
                                errors.add("Pipeline " + id + ": each flow step must be an object"); continue;
                            }
                            try { stepKind((Map<String, Object>) sm); }
                            catch (Exception e) { errors.add("Pipeline " + id + ": " + e.getMessage()); }
                        }
                    }
                }

                case "condition" -> {
                    if (!a.containsKey("when"))
                        errors.add("Condition " + loc + ": when is required");
                    else {
                        try { WhenExpr.parse(a.get("when")); }
                        catch (Exception e) { errors.add("Condition " + loc + ": invalid when — " + e.getMessage()); }
                    }
                    Object steps = a.get("steps");
                    if (!(steps instanceof List<?> sl) || sl.isEmpty())
                        errors.add("Condition " + loc + ": steps must be non-empty array");
                }

                case "dictionary" -> {
                    if (!(a.get("values") instanceof List<?>))
                        errors.add("Dictionary " + loc + ": values must be an array");
                }
            }
        }
        return errors;
    }

    // ── Phase 3: Code uniqueness ──────────────────────────────────────────────

    private List<String> validateCodeUniqueness(List<Map<String, Object>> artifacts,
                                                 Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        for (var a : artifacts) {
            if (!"rule".equals(a.get("type"))) continue;
            if (!"check".equals(a.get("role"))) continue;
            String code = str(a.get("code"));
            if (code.isBlank()) continue;
            String id = str(a.get("id"));
            if (seen.containsKey(code))
                errors.add("Duplicate error code '" + code + "' in rules: " + seen.get(code) + " and " + id);
            else
                seen.put(code, id);
        }
        return errors;
    }

    // ── Phase 4: Refs ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> validateRefs(List<Map<String, Object>> artifacts,
                                       Map<String, Map<String, Object>> registry,
                                       Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        for (var a : artifacts) {
            String type = str(a.get("type"));
            String id   = str(a.get("id"));

            if ("pipeline".equals(type) && a.get("flow") instanceof List<?> flow) {
                for (var s : flow) {
                    if (s instanceof Map<?, ?> sm)
                        errors.addAll(validateStepRef(id, (Map<String, Object>) sm, registry, sources));
                }
            }
            if ("condition".equals(type)) {
                errors.addAll(validateWhenRefs(id, a.get("when"), registry, sources));
                if (a.get("steps") instanceof List<?> steps) {
                    for (var s : steps) {
                        if (s instanceof Map<?, ?> sm)
                            errors.addAll(validateStepRef(id, (Map<String, Object>) sm, registry, sources));
                    }
                }
            }
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private List<String> validateStepRef(String scopeId, Map<String, Object> step,
                                          Map<String, Map<String, Object>> registry,
                                          Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        try {
            String kind = stepKind(step);
            String ref  = str(step.get(kind));
            String resolvedId = resolveRef(ref, scopeId);
            var target = registry.get(resolvedId);
            if (target == null)
                errors.add("Unresolved " + kind + " ref '" + ref + "' in " + scopeId);
            else if (!kind.equals(str(target.get("type"))))
                errors.add("Ref '" + ref + "' in " + scopeId + " resolves to wrong type: expected " + kind + ", got " + str(target.get("type")));
        } catch (Exception e) {
            errors.add("Step in " + scopeId + ": " + e.getMessage());
        }
        return errors;
    }

    private List<String> validateWhenRefs(String condId, Object when,
                                           Map<String, Map<String, Object>> registry,
                                           Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        try {
            WhenExpr expr = WhenExpr.parse(when);
            collectPredicateRefs(expr, condId, registry, errors);
        } catch (Exception e) {
            errors.add("Condition " + condId + ": invalid when — " + e.getMessage());
        }
        return errors;
    }

    private void collectPredicateRefs(WhenExpr expr, String scopeId,
                                       Map<String, Map<String, Object>> registry,
                                       List<String> errors) {
        switch (expr) {
            case WhenExpr.Single s -> {
                String id = resolveRef(s.predicateId(), scopeId);
                var target = registry.get(id);
                if (target == null)
                    errors.add("Unresolved predicate ref '" + s.predicateId() + "' in condition " + scopeId);
                else if (!"rule".equals(target.get("type")) || !"predicate".equals(target.get("role")))
                    errors.add("Ref '" + s.predicateId() + "' in condition " + scopeId + " must be a predicate rule");
            }
            case WhenExpr.All a -> a.items().forEach(i -> collectPredicateRefs(i, scopeId, registry, errors));
            case WhenExpr.Any a -> a.items().forEach(i -> collectPredicateRefs(i, scopeId, registry, errors));
        }
    }

    // ── Phase 5: Build conditions ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, CompiledCondition> buildConditions(List<Map<String, Object>> artifacts) {
        Map<String, CompiledCondition> result = new LinkedHashMap<>();
        AtomicInteger counter = new AtomicInteger();

        for (var a : artifacts) {
            if (!"condition".equals(a.get("type"))) continue;
            String id    = str(a.get("id"));
            WhenExpr when = WhenExpr.parse(a.get("when"));
            List<CompiledStep> steps = buildSteps((List<Map<String, Object>>) a.get("steps"), id, counter);
            result.put(id, new CompiledCondition(id, when, steps));
        }
        return result;
    }

    // ── Phase 6: Build pipelines ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, CompiledPipeline> buildPipelines(List<Map<String, Object>> artifacts) {
        Map<String, CompiledPipeline> result = new LinkedHashMap<>();
        AtomicInteger counter = new AtomicInteger();

        for (var a : artifacts) {
            if (!"pipeline".equals(a.get("type"))) continue;
            String id         = str(a.get("id"));
            boolean entry     = Boolean.TRUE.equals(a.get("entrypoint"));
            boolean strict    = Boolean.TRUE.equals(a.get("strict"));
            String strictCode = str(a.get("strictCode"));
            String strictMsg  = str(a.get("message"));
            List<String> ctx  = a.get("required_context") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
            List<CompiledStep> steps = buildSteps((List<Map<String, Object>>) a.get("flow"), id, counter);
            result.put(id, new CompiledPipeline(id, entry, strict, strictCode, strictMsg, ctx, steps));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<CompiledStep> buildSteps(List<Map<String, Object>> raw, String scopeId, AtomicInteger counter) {
        if (raw == null) return List.of();
        List<CompiledStep> steps = new ArrayList<>();
        for (var s : raw) {
            String stepId = scopeId + ":step:" + counter.getAndIncrement();
            String kind   = stepKind(s);
            String ref    = str(s.get(kind));
            String resolvedId = resolveRef(ref, scopeId);
            steps.add(switch (kind) {
                case "rule"      -> new CompiledStep.RuleStep(resolvedId, stepId, ref);
                case "pipeline"  -> new CompiledStep.PipelineStep(resolvedId, stepId);
                case "condition" -> new CompiledStep.ConditionStep(resolvedId, stepId);
                default -> throw new IllegalStateException("Unknown step kind: " + kind);
            });
        }
        return List.copyOf(steps);
    }

    // ── Phase 7: DAG ──────────────────────────────────────────────────────────

    private List<String> validateDAG(Map<String, Map<String, Object>> registry,
                                      Map<String, CompiledPipeline> pipelines,
                                      Map<String, CompiledCondition> conditions) {
        List<String> errors = new ArrayList<>();
        // Build adjacency: pipeline → list of called pipeline ids
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (var entry : pipelines.entrySet()) {
            adj.put(entry.getKey(), calledPipelines(entry.getValue().steps(), conditions));
        }
        // DFS cycle detection
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String id : adj.keySet()) {
            detectCycles(id, adj, visited, inStack, errors);
        }
        return errors;
    }

    private List<String> calledPipelines(List<CompiledStep> steps,
                                          Map<String, CompiledCondition> conditions) {
        List<String> result = new ArrayList<>();
        for (var step : steps) {
            switch (step) {
                case CompiledStep.PipelineStep ps -> result.add(ps.pipelineId());
                case CompiledStep.ConditionStep cs -> {
                    var cond = conditions.get(cs.conditionId());
                    if (cond != null) result.addAll(calledPipelines(cond.steps(), conditions));
                }
                default -> {}
            }
        }
        return result;
    }

    private void detectCycles(String node, Map<String, List<String>> adj,
                               Set<String> visited, Set<String> inStack, List<String> errors) {
        if (inStack.contains(node)) {
            errors.add("Cycle detected in pipeline call graph involving: " + node);
            return;
        }
        if (visited.contains(node)) return;
        visited.add(node);
        inStack.add(node);
        for (String neighbour : adj.getOrDefault(node, List.of())) {
            detectCycles(neighbour, adj, visited, inStack, errors);
        }
        inStack.remove(node);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void throwIfErrors(List<String> errors) {
        if (!errors.isEmpty()) throw new CompilationException(errors);
    }

    static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    static String where(String id, Map<String, String> sources) {
        String src = sources.get(id);
        return src != null ? id + " (" + src + ")" : id;
    }

    public static String resolveRef(String ref, String scopeId) {
        if (ref.startsWith("library.") || ref.startsWith("internal.") || ref.startsWith("entrypoints."))
            return ref;
        if (ref.contains(".")) return ref; // treat as absolute
        return scopeId + "." + ref;        // scoped local ref
    }

    @SuppressWarnings("unchecked")
    static String stepKind(Map<String, Object> step) {
        List<String> allowed = List.of("rule", "pipeline", "condition");
        List<String> present = allowed.stream().filter(step::containsKey).toList();
        if (present.size() != 1)
            throw new IllegalArgumentException(
                "Step must contain exactly one of rule|pipeline|condition. Got: " + step.keySet());
        return present.get(0);
    }

    static int parseFlags(String flags) {
        int f = 0;
        if (flags.contains("i")) f |= Pattern.CASE_INSENSITIVE;
        if (flags.contains("m")) f |= Pattern.MULTILINE;
        if (flags.contains("s")) f |= Pattern.DOTALL;
        return f;
    }
}
