package ru.jsonspecs.compiler;

import ru.jsonspecs.CompilationException;
import ru.jsonspecs.CompileOptions;
import ru.jsonspecs.operators.OperatorPack;
import ru.jsonspecs.util.ArtifactCopy;
import ru.jsonspecs.util.RegexFlags;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles a list of artifact maps into an executable {@link Compiled} bundle.
 *
 * <h3>Immutability guarantee</h3>
 * <p>All artifact maps are deep-copied on entry to {@link #compile} before any phase
 * runs. Mutations to the caller's original maps after compile complete are isolated
 * and cannot affect the compiled snapshot.
 *
 * <h3>Compilation phases</h3>
 * <ol>
 *   <li>Deep-copy all artifacts — isolation from caller mutations</li>
 *   <li>Build registry — deduplication, collect dictionaries</li>
 *   <li>Validate schema — per-artifact structural validation (strict)</li>
 *   <li>Validate code uniqueness — duplicate error codes across check rules</li>
 *   <li>Validate refs — resolve all cross-artifact references</li>
 *   <li>Build conditions — compile-time normalization of conditions</li>
 *   <li>Build pipelines — compile-time normalization of pipelines</li>
 *   <li>Validate DAG — cycle detection in pipeline call graph</li>
 * </ol>
 *
 * <p>Each phase accumulates <em>all</em> errors before throwing, so callers receive
 * the complete list of issues rather than just the first one.
 *
 * <p><b>Internal API.</b>
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

        // Phase 1: deep copy — all subsequent phases work with immutable copies
        List<Map<String, Object>> safe = artifacts == null ? List.of()
            : artifacts.stream().map(ArtifactCopy::deepCopy).toList();

        // Phase 2: registry
        var phase2 = buildRegistry(safe);
        throwIfErrors(phase2.errors());
        var registry     = phase2.registry();
        var dictionaries = phase2.dictionaries();

        // Phase 3: schema
        throwIfErrors(validateSchema(safe, dictionaries, sources));

        // Phase 4: code uniqueness
        throwIfErrors(validateCodeUniqueness(safe, sources));

        // Phase 5: refs
        throwIfErrors(validateRefs(safe, registry, sources));

        // Phase 6: build conditions
        var conditions = buildConditions(safe);

        // Phase 7: build pipelines
        var pipelines = buildPipelines(safe);

        // Phase 8: DAG
        throwIfErrors(validateDAG(pipelines, conditions));

        return new Compiled(
            Collections.unmodifiableMap(registry),
            Collections.unmodifiableMap(dictionaries),
            Collections.unmodifiableMap(pipelines),
            Collections.unmodifiableMap(conditions),
            operators,
            sources
        );
    }

    // ── Phase 2: Registry ─────────────────────────────────────────────────────

    record Phase2Result(
        Map<String, Map<String, Object>> registry,
        Map<String, Map<String, Object>> dictionaries,
        List<String> errors
    ) {}

    private Phase2Result buildRegistry(List<Map<String, Object>> artifacts) {
        Map<String, Map<String, Object>> registry     = new LinkedHashMap<>();
        Map<String, Map<String, Object>> dictionaries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (var a : artifacts) {
            String id   = str(a.get("id"));
            String type = str(a.get("type"));

            if (id.isBlank())   { errors.add("Artifact must have non-empty id"); continue; }
            if (type.isBlank()) { errors.add("Artifact " + id + " must have type"); continue; }
            if (!a.containsKey("description") || str(a.get("description")).isBlank())
                errors.add("Artifact " + id + " must have description");
            if (registry.containsKey(id)) {
                errors.add("Duplicate artifact id: " + id); continue;
            }
            // a is already a deep-copied immutable map (from Phase 1)
            registry.put(id, a);
            if ("dictionary".equals(type)) dictionaries.put(id, a);
        }
        return new Phase2Result(registry, dictionaries, errors);
    }

    // ── Phase 3: Schema ───────────────────────────────────────────────────────

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
                case "rule" -> validateRuleSchema(a, dictionaries, loc, validLevels, errors);
                case "pipeline" -> validatePipelineSchema(a, id, loc, errors);
                case "condition" -> validateConditionSchema(a, loc, errors);
                case "dictionary" -> validateDictionarySchema(a, loc, errors);
            }
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void validateRuleSchema(Map<String, Object> a,
                                    Map<String, Map<String, Object>> dictionaries,
                                    String loc,
                                    Set<String> validLevels, List<String> errors) {
        String role = str(a.get("role"));
        if (!Set.of("check", "predicate").contains(role))
            errors.add("Rule " + loc + ": role must be 'check' or 'predicate'");

        // Operator existence
        if (str(a.get("operator")).isBlank()) {
            errors.add("Rule " + loc + ": operator is required");
        } else {
            String op = str(a.get("operator"));
            boolean exists = "check".equals(role) ? operators.hasCheck(op) : operators.hasPredicate(op);
            if (!exists)
                errors.add("Rule " + loc + ": unknown " + role + " operator '" + op + "'");
        }

        // check-only fields
        if ("check".equals(role)) {
            if (!validLevels.contains(str(a.get("level"))))
                errors.add("Check rule " + loc + ": level must be WARNING|ERROR|EXCEPTION");
            if (str(a.get("code")).isBlank())
                errors.add("Check rule " + loc + ": code required");
            if (str(a.get("message")).isBlank())
                errors.add("Check rule " + loc + ": message required");
        }

        // Predicate must NOT have level/code/message
        if ("predicate".equals(role)) {
            if (a.containsKey("level") || a.containsKey("code") || a.containsKey("message"))
                errors.add("Predicate rule " + loc + ": must not have level/code/message");
        }

        // Operator-specific compile-time validation
        validateOperatorParams(a, dictionaries, loc, errors);

        // Optional meta validation
        if (a.containsKey("meta") && !(a.get("meta") instanceof Map<?, ?>))
            errors.add("Rule " + loc + ": meta must be an object if provided");

        // Aggregate validation
        if (a.containsKey("aggregate"))
            validateAggregate(a, role, loc, errors);
    }

    private static final Set<String> FIELD_COMPARE_OPERATORS = Set.of(
        "field_less_than_field",
        "field_greater_than_field",
        "field_equals_field",
        "field_not_equals_field",
        "field_less_or_equal_than_field",
        "field_greater_or_equal_than_field"
    );

    private void validateOperatorParams(Map<String, Object> a,
                                        Map<String, Map<String, Object>> dictionaries,
                                        String loc,
                                        List<String> errors) {
        String operator = str(a.get("operator"));

        if ("any_filled".equals(operator)) {
            Object fieldsObj = a.get("fields");
            if (!(fieldsObj instanceof List<?> fields)
                || fields.isEmpty()
                || fields.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank())) {
                errors.add("Rule " + loc + ": any_filled requires fields[]");
            }
        }

        if ("in_dictionary".equals(operator)) {
            Object dictObj = a.get("dictionary");
            if (!(dictObj instanceof Map<?, ?> rawDict)) {
                errors.add("Rule " + loc + ": in_dictionary requires dictionary{type:static,id}");
            } else {
                Object type = rawDict.get("type");
                Object id = rawDict.get("id");
                if (!(type instanceof String sType) || !"static".equals(sType)
                    || !(id instanceof String sId) || sId.isBlank()) {
                    errors.add("Rule " + loc + ": in_dictionary requires dictionary{type:static,id}");
                } else if (!dictionaries.containsKey(sId)) {
                    errors.add("Rule " + loc + ": dictionary not found: " + sId);
                }
            }
        }

        if (FIELD_COMPARE_OPERATORS.contains(operator)) {
            String valueField = str(a.get("value_field"));
            if (valueField.isBlank()) {
                errors.add("Rule " + loc + ": " + operator + " requires value_field");
            }
        }

        if ("matches_regex".equals(operator)) {
            String val = str(a.get("value"));
            if (val.isBlank()) {
                errors.add("Rule " + loc + ": matches_regex requires value (regex string)");
            } else {
                try {
                    String flags = a.get("flags") instanceof String f ? f : "";
                    Pattern.compile(val, RegexFlags.parse(flags));
                } catch (PatternSyntaxException e) {
                    errors.add("Rule " + loc + ": matches_regex has invalid regex pattern — " + e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateAggregate(Map<String, Object> a, String role,
                                    String loc, List<String> errors) {
        if (!(a.get("aggregate") instanceof Map<?, ?> rawAgg)) {
            errors.add("Rule " + loc + ": aggregate must be an object");
            return;
        }
        Map<String, Object> agg = (Map<String, Object>) rawAgg;

        String modeRaw = str(agg.get("mode"));
        String mode    = modeRaw.isBlank() ? null : modeRaw.toUpperCase();

        if ("check".equals(role)) {
            Set<String> validModes = Set.of("EACH", "ALL", "COUNT", "MIN", "MAX");
            if (mode != null && !validModes.contains(mode))
                errors.add("Check rule " + loc + ": aggregate.mode must be one of EACH|ALL|COUNT|MIN|MAX, got '" + modeRaw + "'");
            if ("COUNT".equals(mode) && !agg.containsKey("value"))
                errors.add("Check rule " + loc + ": aggregate.mode COUNT requires aggregate.value (integer)");
        } else if ("predicate".equals(role)) {
            Set<String> validModes = Set.of("ANY", "ALL", "COUNT");
            if (mode != null && !validModes.contains(mode))
                errors.add("Predicate rule " + loc + ": aggregate.mode must be one of ANY|ALL|COUNT, got '" + modeRaw + "'");
            if ("COUNT".equals(mode) && !agg.containsKey("value"))
                errors.add("Predicate rule " + loc + ": aggregate.mode COUNT requires aggregate.value (integer)");
        }

        // onEmpty validation
        String onEmpty = str(agg.get("onEmpty")).toUpperCase();
        if (!onEmpty.isBlank()) {
            Set<String> checkOnEmpty     = Set.of("PASS", "FAIL", "UNDEFINED", "ERROR");
            Set<String> predicateOnEmpty = Set.of("TRUE", "FALSE", "UNDEFINED", "ERROR");
            Set<String> valid = "check".equals(role) ? checkOnEmpty : predicateOnEmpty;
            if (!valid.contains(onEmpty))
                errors.add("Rule " + loc + ": aggregate.onEmpty invalid value '" + agg.get("onEmpty")
                    + "'; valid for " + role + ": " + valid);
        }
    }

    @SuppressWarnings("unchecked")
    private void validatePipelineSchema(Map<String, Object> a, String id,
                                         String loc, List<String> errors) {
        Object flow = a.get("flow");
        if (!(flow instanceof List<?> f) || f.isEmpty())
            errors.add("Pipeline " + loc + ": flow must be non-empty array");
        if (!(a.get("strict") instanceof Boolean))
            errors.add("Pipeline " + loc + ": strict must be explicitly set to true|false");
        if (!(a.get("entrypoint") instanceof Boolean))
            errors.add("Pipeline " + loc + ": entrypoint must be explicitly set to true|false");
        if (Boolean.TRUE.equals(a.get("strict")) && str(a.get("message")).isBlank())
            errors.add("Pipeline " + loc + ": message is required when strict=true");
        if (Boolean.TRUE.equals(a.get("strict")) && a.containsKey("strictCode") && str(a.get("strictCode")).isBlank())
            errors.add("Pipeline " + loc + ": strictCode must be non-empty string if provided");

        // required_context: must be a list of non-blank strings
        if (a.containsKey("required_context")) {
            if (!(a.get("required_context") instanceof List<?> rc)) {
                errors.add("Pipeline " + loc + ": required_context must be an array of strings");
            } else {
                for (int i = 0; i < rc.size(); i++) {
                    Object item = rc.get(i);
                    if (!(item instanceof String s) || s.isBlank())
                        errors.add("Pipeline " + loc + ": required_context[" + i
                            + "] must be a non-blank string, got: " + item);
                }
            }
        }

        // Validate each flow step structure
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

    private void validateConditionSchema(Map<String, Object> a, String loc, List<String> errors) {
        if (!a.containsKey("when")) {
            errors.add("Condition " + loc + ": when is required");
        } else {
            try { WhenExpr.parse(a.get("when")); }
            catch (Exception e) { errors.add("Condition " + loc + ": invalid when — " + e.getMessage()); }
        }
        Object steps = a.get("steps");
        if (!(steps instanceof List<?> sl) || sl.isEmpty())
            errors.add("Condition " + loc + ": steps must be non-empty array");
    }

    @SuppressWarnings("unchecked")
    private void validateDictionarySchema(Map<String, Object> a, String loc, List<String> errors) {
        if (!(a.get("entries") instanceof List<?> entries)) {
            errors.add("Dictionary " + loc + ": entries must be an array");
            return;
        }
        if (entries.isEmpty()) {
            errors.add("Dictionary " + loc + ": entries must be non-empty");
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (entry instanceof Map<?, ?> m) {
                if (!m.containsKey("code") && !m.containsKey("value"))
                    errors.add("Dictionary " + loc + ": entries[" + i
                        + "] object must have a 'code' or 'value' key");
            } else if (!(entry instanceof String)
                    && !(entry instanceof Number)
                    && !(entry instanceof Boolean)) {
                errors.add("Dictionary " + loc + ": entries[" + i
                    + "] must be a string, number, boolean, or object with 'code'/'value'");
            }
        }
    }

    // ── Phase 4: Code uniqueness ──────────────────────────────────────────────

    private List<String> validateCodeUniqueness(List<Map<String, Object>> artifacts,
                                                 Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        for (var a : artifacts) {
            if (!"rule".equals(a.get("type")) || !"check".equals(a.get("role"))) continue;
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

    // ── Phase 5: Refs ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> validateRefs(List<Map<String, Object>> artifacts,
                                       Map<String, Map<String, Object>> registry,
                                       Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        for (var a : artifacts) {
            String type = str(a.get("type")), id = str(a.get("id"));
            String scopePipelineId = scopePipelineId(type, id);
            if ("pipeline".equals(type) && a.get("flow") instanceof List<?> flow)
                for (var s : flow)
                    if (s instanceof Map<?, ?> sm)
                        errors.addAll(validateStepRef(scopePipelineId, (Map<String, Object>) sm, registry, sources));
            if ("condition".equals(type)) {
                errors.addAll(validateWhenRefs(scopePipelineId, id, a.get("when"), registry, sources));
                if (a.get("steps") instanceof List<?> steps)
                    for (var s : steps)
                        if (s instanceof Map<?, ?> sm)
                            errors.addAll(validateStepRef(scopePipelineId, (Map<String, Object>) sm, registry, sources));
            }
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private List<String> validateStepRef(String scopePipelineId, Map<String, Object> step,
                                          Map<String, Map<String, Object>> registry,
                                          Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        try {
            String kind = stepKind(step);
            String ref  = str(step.get(kind));
            var target  = "pipeline".equals(kind)
                ? registry.get(ref)
                : registry.get(resolveRef(ref, scopePipelineId));
            if (target == null)
                errors.add("Unresolved " + kind + " ref '" + ref + "' in " + scopePipelineId);
            else if (!kind.equals(str(target.get("type"))))
                errors.add("Ref '" + ref + "' in " + scopePipelineId
                    + " resolves to wrong type: expected " + kind + ", got " + str(target.get("type")));
        } catch (Exception e) {
            errors.add("Step in " + scopePipelineId + ": " + e.getMessage());
        }
        return errors;
    }

    private List<String> validateWhenRefs(String scopePipelineId, String condId, Object when,
                                           Map<String, Map<String, Object>> registry,
                                           Map<String, String> sources) {
        List<String> errors = new ArrayList<>();
        try { collectPredicateRefs(WhenExpr.parse(when), scopePipelineId, condId, registry, errors); }
        catch (Exception e) { errors.add("Condition " + condId + ": invalid when — " + e.getMessage()); }
        return errors;
    }

    private void collectPredicateRefs(WhenExpr expr, String scopePipelineId, String condId,
                                       Map<String, Map<String, Object>> registry,
                                       List<String> errors) {
        switch (expr) {
            case WhenExpr.Single s -> {
                String id = resolveRef(s.predicateId(), scopePipelineId);
                var target = registry.get(id);
                if (target == null)
                    errors.add("Unresolved predicate ref '" + s.predicateId() + "' in condition " + condId);
                else if (!"rule".equals(target.get("type")) || !"predicate".equals(target.get("role")))
                    errors.add("Ref '" + s.predicateId() + "' in condition " + condId
                        + " must be a predicate rule");
            }
            case WhenExpr.All a -> a.items().forEach(i -> collectPredicateRefs(i, scopePipelineId, condId, registry, errors));
            case WhenExpr.Any a -> a.items().forEach(i -> collectPredicateRefs(i, scopePipelineId, condId, registry, errors));
        }
    }

    // ── Phase 6: Build conditions ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, CompiledCondition> buildConditions(List<Map<String, Object>> artifacts) {
        Map<String, CompiledCondition> result = new LinkedHashMap<>();
        int[] counter = {0};
        for (var a : artifacts) {
            if (!"condition".equals(a.get("type"))) continue;
            String id   = str(a.get("id"));
            String scopePipelineId = scopePipelineId("condition", id);
            WhenExpr when = WhenExpr.parse(a.get("when"));
            var steps = buildSteps((List<Map<String, Object>>) a.get("steps"), scopePipelineId, counter);
            result.put(id, new CompiledCondition(id, when, steps));
        }
        return result;
    }

    // ── Phase 7: Build pipelines ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, CompiledPipeline> buildPipelines(List<Map<String, Object>> artifacts) {
        Map<String, CompiledPipeline> result = new LinkedHashMap<>();
        int[] counter = {0};
        for (var a : artifacts) {
            if (!"pipeline".equals(a.get("type"))) continue;
            String id         = str(a.get("id"));
            boolean entry     = Boolean.TRUE.equals(a.get("entrypoint"));
            boolean strict    = Boolean.TRUE.equals(a.get("strict"));
            String strictCode = str(a.get("strictCode"));
            String strictMsg  = str(a.get("message"));
            List<String> ctx  = a.get("required_context") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
            var steps = buildSteps((List<Map<String, Object>>) a.get("flow"), id, counter);
            result.put(id, new CompiledPipeline(id, entry, strict, strictCode, strictMsg, ctx, steps));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<CompiledStep> buildSteps(List<Map<String, Object>> raw, String scopePipelineId, int[] counter) {
        if (raw == null) return List.of();
        List<CompiledStep> steps = new ArrayList<>();
        for (var s : raw) {
            String stepId = scopePipelineId + ":step:" + counter[0]++;
            String kind   = stepKind(s);
            String ref    = str(s.get(kind));
            steps.add(switch (kind) {
                case "rule"      -> new CompiledStep.RuleStep(resolveRef(ref, scopePipelineId), stepId, ref);
                case "pipeline"  -> new CompiledStep.PipelineStep(ref, stepId);
                case "condition" -> new CompiledStep.ConditionStep(resolveRef(ref, scopePipelineId), stepId);
                default -> throw new IllegalStateException("Unknown step kind: " + kind);
            });
        }
        return List.copyOf(steps);
    }

    // ── Phase 8: DAG ──────────────────────────────────────────────────────────

    private List<String> validateDAG(Map<String, CompiledPipeline> pipelines,
                                      Map<String, CompiledCondition> conditions) {
        List<String> errors = new ArrayList<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (var e : pipelines.entrySet())
            adj.put(e.getKey(), calledPipelines(e.getValue().steps(), conditions));
        Set<String> visited = new HashSet<>(), inStack = new HashSet<>();
        for (String id : adj.keySet())
            detectCycles(id, adj, visited, inStack, errors);
        return errors;
    }

    private List<String> calledPipelines(List<CompiledStep> steps,
                                          Map<String, CompiledCondition> conditions) {
        List<String> result = new ArrayList<>();
        for (var step : steps) {
            switch (step) {
                case CompiledStep.PipelineStep  ps -> result.add(ps.pipelineId());
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
            errors.add("Cycle detected in pipeline call graph involving: " + node); return;
        }
        if (visited.contains(node)) return;
        visited.add(node); inStack.add(node);
        for (String n : adj.getOrDefault(node, List.of()))
            detectCycles(n, adj, visited, inStack, errors);
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

    public static String resolveRef(String ref, String scopePipelineId) {
        if (ref.startsWith("library.") || ref.startsWith("internal.")
                || ref.startsWith("entrypoints."))
            return ref;
        if (ref.contains(".")) return ref;   // treat as absolute
        if (scopePipelineId == null || scopePipelineId.isBlank())
            throw new IllegalArgumentException("Cannot resolve scoped ref '" + ref + "' without pipeline scope");
        return scopePipelineId + "." + ref;  // scoped local ref
    }

    static String scopePipelineId(String artifactType, String artifactId) {
        if ("pipeline".equals(artifactType)) return artifactId;
        if ("condition".equals(artifactType)) {
            int idx = artifactId.lastIndexOf('.');
            return idx > 0 ? artifactId.substring(0, idx) : null;
        }
        return artifactId;
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
}
