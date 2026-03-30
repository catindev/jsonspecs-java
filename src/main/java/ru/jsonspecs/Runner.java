package ru.jsonspecs;

import ru.jsonspecs.compiler.*;
import ru.jsonspecs.operators.*;
import ru.jsonspecs.util.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

/**
 * Executes a compiled pipeline against a flat payload.
 * Package-private — access via {@link Engine}.
 *
 * <p><b>Internal API.</b>
 */
final class Runner {

    private Runner() {}

    /** Internal step-execution control signal — never exposed in public API. */
    private enum ExecControl { CONTINUE, STOP }

    // ── entry point ───────────────────────────────────────────────────────────

    static PipelineResult runPipeline(Compiled compiled, String pipelineId,
                                      Map<String, Object> payload, RunOptions options) {
        boolean traceOn  = options == null || options.isTrace();
        boolean safeMode = options != null && options.isSafeMode();

        List<Issue>      issues = new ArrayList<>();
        List<TraceEntry> trace  = traceOn ? new ArrayList<>() : null;

        try {
            Map<String, Object> flat = PayloadFlattener.flatten(payload != null ? payload : Map.of());

            CompiledPipeline pipeline = compiled.pipelines().get(pipelineId);
            if (pipeline == null)
                throw new IllegalArgumentException("Pipeline not found: " + pipelineId);

            addTrace(trace, "pipeline:start",
                "pipeline:" + pipelineId,
                Map.of("pipelineId", pipelineId, "traceEnabled", traceOn));

            // required_context check
            if (checkRequiredContext(pipeline, flat, pipelineId, null, issues, trace))
                return finishPipeline("EXCEPTION", issues, trace, null, null, safeMode);

            OperatorContext ctx = new OperatorContext(flat, compiled.dictionaries());
            int pipelineIssuesStart = issues.size();
            ExecControl ctl = execSteps(compiled, pipeline.steps(), pipelineId, ctx, issues, trace);
            ctl = applyStrictBoundary(pipeline, pipelineIssuesStart, null, issues, trace, ctl);

            String status = computeStatus(issues);
            addTrace(trace, "pipeline:end",
                "pipeline:" + pipelineId,
                Map.of("pipelineId", pipelineId, "status", status, "control", ctl.name()));

            return finishPipeline(status, issues, trace, null, null, safeMode);

        } catch (Exception e) {
            addTrace(trace, "pipeline:abort",
                "pipeline:" + pipelineId,
                Map.of("pipelineId", pipelineId, "error", String.valueOf(e.getMessage())));
            String stack = safeMode ? null : stackTrace(e);
            return finishPipeline("ABORT", issues, trace, e.getMessage(), stack, safeMode);
        }
    }

    // ── required_context ─────────────────────────────────────────────────────

    private static boolean checkRequiredContext(CompiledPipeline pipeline,
                                                 Map<String, Object> flat,
                                                 String pipelineId, String stepId,
                                                 List<Issue> issues,
                                                 List<TraceEntry> trace) {
        List<String> missing = new ArrayList<>();
        for (String key : pipeline.requiredContext()) {
            if (!DeepGet.get(flat, "$context." + key).ok())
                missing.add(key);
        }
        if (missing.isEmpty()) return false;

        addTrace(trace, "pipeline:context:missing",
            "pipeline:" + pipelineId,
            Map.of("pipelineId", pipelineId, "missing", missing));

        for (String key : missing) {
            String code = "CTX." + toSnakeUpper(key) + ".REQUIRED";
            issues.add(Issue.builder("EXCEPTION")
                .code(code)
                .message("Missing required runtime context field: " + key)
                .field("$context." + key)
                .ruleId("pipeline:" + pipelineId)
                .pipelineId(pipelineId)
                .stepId(stepId)
                .build());
        }
        return true;
    }

    // ── step execution ────────────────────────────────────────────────────────

    private static ExecControl execSteps(Compiled compiled, List<CompiledStep> steps,
                                          String scopeId, OperatorContext ctx,
                                          List<Issue> issues, List<TraceEntry> trace) {
        for (CompiledStep step : steps) {
            ExecControl ctl = switch (step) {
                case CompiledStep.RuleStep      rs -> execRule(compiled, rs, ctx, issues, trace, scopeId);
                case CompiledStep.PipelineStep  ps -> execNestedPipeline(compiled, ps, ctx, issues, trace, scopeId);
                case CompiledStep.ConditionStep cs -> execCondition(compiled, cs, scopeId, ctx, issues, trace);
            };
            if (ctl == ExecControl.STOP) return ExecControl.STOP;
        }
        return ExecControl.CONTINUE;
    }

    // ── rule execution ────────────────────────────────────────────────────────

    private static ExecControl execRule(Compiled compiled, CompiledStep.RuleStep step,
                                         OperatorContext ctx, List<Issue> issues,
                                         List<TraceEntry> trace, String scopeId) {
        Map<String, Object> rule = compiled.registry().get(step.ruleId());
        if (rule == null) throw new IllegalStateException("Missing rule: " + step.ruleId());

        String role     = str(rule.get("role"));
        String operator = str(rule.get("operator"));
        String field    = str(rule.get("field"));

        // Predicates used as standalone steps don't produce issues (spec §8)
        if ("predicate".equals(role)) return ExecControl.CONTINUE;

        if (WildcardExpander.isWildcard(field))
            return execWildcardCheckInline(compiled, step, rule, field, operator, ctx, issues, trace, scopeId);

        CheckOperator op = compiled.operators().getCheck(operator);
        if (op == null) throw new IllegalStateException("Unknown check operator: " + operator);

        CheckResult result = op.apply(rule, ctx);

        String status = result instanceof CheckResult.Ok ? "OK" : "FAIL";
        addTrace(trace, "rule:result",
            scopeId + ":rule:" + step.ruleId(),
            Map.of("ruleId", step.ruleId(), "operator", operator,
                   "field", field, "status", status,
                   "level", str(rule.get("level"))));

        return applyCheckResult(result, rule, step, null, issues);
    }

    // ── wildcard check (inline single-pass implementation) ────────────────────
    @SuppressWarnings("unchecked")
    private static ExecControl execWildcardCheckInline(
            Compiled compiled, CompiledStep.RuleStep step,
            Map<String, Object> rule, String pattern, String operator,
            OperatorContext ctx, List<Issue> issues, List<TraceEntry> trace, String scopeId) {

        CheckOperator op  = compiled.operators().getCheck(operator);
        List<String>  keys = WildcardExpander.expand(pattern, ctx.payload().keySet());
        Map<String, Object> agg    = getAgg(rule);
        String mode    = str(agg.getOrDefault("mode", "EACH")).toUpperCase();
        String onEmpty = str(agg.getOrDefault("onEmpty", "PASS")).toUpperCase();

        addTrace(trace, "wildcard:expand",
            scopeId + ":rule:" + step.ruleId(),
            Map.of("pattern", pattern, "matchedCount", keys.size(),
                   "sample", keys.stream().limit(5).toList()));

        if (keys.isEmpty()) {
            ExecControl ctl = switch (onEmpty) {
                case "FAIL"  -> pushIssue(rule, step, pattern, null, issues);
                case "ERROR" -> throw new IllegalStateException("Wildcard matched 0 fields: " + pattern);
                default      -> ExecControl.CONTINUE;
            };
            addTrace(trace, "wildcard:aggregate",
                scopeId + ":rule:" + step.ruleId(),
                Map.of("pattern", pattern, "mode", mode, "matchedCount", 0,
                       "onEmpty", onEmpty, "result", ctl == ExecControl.STOP ? "FAIL" : "PASS"));
            return ctl;
        }

        ExecControl ctl;
        String resultLabel;

        switch (mode) {
            case "EACH", "ALL" -> {
                boolean summaryIssue = "ALL".equals(mode) && Boolean.TRUE.equals(agg.get("summaryIssue"));
                List<String> failedKeys = new ArrayList<>();
                for (String key : keys) {
                    Map<String, Object> ruleForKey = new HashMap<>(rule);
                    ruleForKey.put("field", key);
                    CheckResult r = op.apply(ruleForKey, ctx);
                    if (r instanceof CheckResult.Exception ex)
                        throw new RuntimeException("Operator error in rule " + step.ruleId(), ex.error());
                    if (!(r instanceof CheckResult.Ok)) failedKeys.add(key);
                }
                if (failedKeys.isEmpty()) {
                    ctl = ExecControl.CONTINUE; resultLabel = "OK";
                } else {
                    String level = str(rule.get("level"));
                    if (summaryIssue) {
                        issues.add(Issue.builder(level)
                            .code(str(rule.get("code"))).message(str(rule.get("message")))
                            .field(pattern).ruleId(step.ruleId())
                            .actual(failedKeys.size()).stepId(step.stepId()).build());
                    } else {
                        for (String fk : failedKeys) {
                            Object actual = ctx.get(fk).ok() ? ctx.get(fk).value() : null;
                            issues.add(Issue.builder(level)
                                .code(str(rule.get("code"))).message(str(rule.get("message")))
                                .field(fk).ruleId(step.ruleId())
                                .expected(rule.containsKey("value") ? rule.get("value")
                                    : rule.containsKey("dictionary") ? rule.get("dictionary") : null)
                                .actual(actual).stepId(step.stepId()).build());
                        }
                    }
                    ctl = "EXCEPTION".equals(level) ? ExecControl.STOP : ExecControl.CONTINUE;
                    resultLabel = "FAIL";
                }
            }
            case "COUNT" -> {
                int passCount = 0;
                for (String key : keys) {
                    Map<String, Object> r2 = new HashMap<>(rule); r2.put("field", key);
                    CheckResult r = op.apply(r2, ctx);
                    if (r instanceof CheckResult.Exception ex)
                        throw new RuntimeException("Operator error in rule " + step.ruleId(), ex.error());
                    if (r instanceof CheckResult.Ok) passCount++;
                }
                String countOp = str(agg.getOrDefault("op", ">="));
                int target = toInt(agg.get("value"));
                if (compareCount(countOp, passCount, target)) {
                    ctl = ExecControl.CONTINUE; resultLabel = "OK";
                } else {
                    ctl = pushIssue(rule, step, pattern, passCount, issues); resultLabel = "FAIL";
                }
            }
            case "MIN", "MAX" -> {
                record KeyComp(String key, ValueComparator.Comparable comp) {}
                List<KeyComp> vals = new ArrayList<>();
                for (String key : keys) {
                    DeepGet.Result got = ctx.get(key);
                    if (!got.ok()) continue;
                    ValueComparator.Comparable c = ValueComparator.toComparable(got.value());
                    if (c != null) vals.add(new KeyComp(key, c));
                }
                if (vals.isEmpty()) {
                    String oe = str(agg.getOrDefault("onEmpty", "PASS")).toUpperCase();
                    if ("FAIL".equals(oe)) {
                        ctl = pushIssue(rule, step, pattern, null, issues);
                        resultLabel = "FAIL";
                    } else if ("ERROR".equals(oe)) {
                        throw new IllegalStateException("Wildcard produced 0 comparable values: " + pattern);
                    } else {
                        ctl = ExecControl.CONTINUE;
                        resultLabel = "OK";
                    }
                    break;
                }
                Class<?> kind = vals.get(0).comp().getClass();
                if (!vals.stream().allMatch(v -> v.comp().getClass() == kind)) {
                    ctl = pushIssue(rule, step, pattern, null, issues); resultLabel = "FAIL"; break;
                }
                KeyComp picked = vals.get(0);
                for (KeyComp ck : vals) {
                    Integer cmp = ValueComparator.compare(ctx.get(ck.key()).value(), ctx.get(picked.key()).value());
                    if (cmp == null) continue;
                    if ("MIN".equals(mode) && cmp < 0) picked = ck;
                    if ("MAX".equals(mode) && cmp > 0) picked = ck;
                }
                Object pickedValue = ctx.get(picked.key()).value();
                Map<String, Object> syn = new HashMap<>();
                syn.put("__agg__", pickedValue);
                if (ctx.payload().containsKey("__context")) syn.put("__context", ctx.payload().get("__context"));
                OperatorContext synCtx = new OperatorContext(Collections.unmodifiableMap(syn), ctx.dictionaries());
                Map<String, Object> synRule = new HashMap<>(rule); synRule.put("field", "__agg__");
                CheckResult r = op.apply(synRule, synCtx);
                if (r instanceof CheckResult.Exception ex)
                    throw new RuntimeException("Operator error in rule " + step.ruleId(), ex.error());
                if (r instanceof CheckResult.Ok) {
                    ctl = ExecControl.CONTINUE; resultLabel = "OK";
                } else {
                    ctl = pushIssue(rule, step, pattern, pickedValue, issues); resultLabel = "FAIL";
                }
            }
            default -> throw new IllegalStateException(
                "Unsupported wildcard aggregate.mode: " + mode + " — should have been caught at compile time");
        }

        addTrace(trace, "wildcard:aggregate",
            scopeId + ":rule:" + step.ruleId(),
            Map.of("pattern", pattern, "mode", mode, "matchedCount", keys.size(),
                   "result", resultLabel));
        return ctl;
    }

    private static ExecControl applyCheckResult(CheckResult result, Map<String, Object> rule,
                                                  CompiledStep.RuleStep step, String overrideField,
                                                  List<Issue> issues) {
        return switch (result) {
            case CheckResult.Ok ignored -> ExecControl.CONTINUE;
            case CheckResult.Exception ex ->
                throw new RuntimeException("Operator error in rule " + step.ruleId(), ex.error());
            case CheckResult.Fails fs -> {
                ExecControl ctl = ExecControl.CONTINUE;
                for (CheckResult.Fail f : fs.failures())
                    if (pushIssue(rule, step, f.field(), f.actual(), issues) == ExecControl.STOP)
                        ctl = ExecControl.STOP;
                yield ctl;
            }
            case CheckResult.Fail f ->
                pushIssue(rule, step, overrideField != null ? overrideField : f.field(), f.actual(), issues);
        };
    }

    private static ExecControl pushIssue(Map<String, Object> rule, CompiledStep.RuleStep step,
                                          String field, Object actual, List<Issue> issues) {
        String level = str(rule.get("level"));
        Object expected = rule.containsKey("value") ? rule.get("value")
            : rule.containsKey("dictionary") ? rule.get("dictionary") : null;
        issues.add(Issue.builder(level)
            .code(str(rule.get("code")))
            .message(str(rule.get("message")))
            .field(field != null ? field : str(rule.get("field")))
            .ruleId(step.ruleId())
            .expected(expected)
            .actual(actual)
            .stepId(step.stepId())
            .build());
        return "EXCEPTION".equals(level) ? ExecControl.STOP : ExecControl.CONTINUE;
    }

    // ── nested pipeline ───────────────────────────────────────────────────────

    private static ExecControl execNestedPipeline(Compiled compiled, CompiledStep.PipelineStep step,
                                                   OperatorContext ctx, List<Issue> issues,
                                                   List<TraceEntry> trace, String scopeId) {
        CompiledPipeline nested = compiled.pipelines().get(step.pipelineId());
        if (nested == null)
            throw new IllegalStateException("Missing nested pipeline: " + step.pipelineId());

        addTrace(trace, "pipeline:enter",
            "pipeline:" + scopeId,
            Map.of("pipelineId", nested.id(), "strict", nested.strict()));

        int issuesBefore = issues.size();
        if (checkRequiredContext(nested, ctx.payload(), step.pipelineId(), step.stepId(), issues, trace))
            return ExecControl.STOP;

        ExecControl ctl = execSteps(compiled, nested.steps(), step.pipelineId(), ctx, issues, trace);
        ctl = applyStrictBoundary(nested, issuesBefore, step.stepId(), issues, trace, ctl);

        addTrace(trace, "pipeline:exit",
            "pipeline:" + scopeId,
            Map.of("pipelineId", nested.id(), "control", ctl.name()));
        return ctl;
    }

    private static ExecControl applyStrictBoundary(CompiledPipeline pipeline,
                                                   int issuesStart,
                                                   String stepId,
                                                   List<Issue> issues,
                                                   List<TraceEntry> trace,
                                                   ExecControl currentControl) {
        if (!pipeline.strict()) return currentControl;

        boolean localHasErrors = issues.subList(issuesStart, issues.size()).stream()
            .anyMatch(i -> "ERROR".equals(i.getLevel()) || "EXCEPTION".equals(i.getLevel()));
        if (!localHasErrors) return currentControl;

        String code = !pipeline.strictCode().isBlank()
            ? pipeline.strictCode() : "STRICT_PIPELINE_FAILED";
        String msg  = !pipeline.strictMessage().isBlank()
            ? pipeline.strictMessage() : "Pipeline strict constraint failed";
        issues.add(Issue.builder("EXCEPTION")
            .code(code).message(msg).field(null)
            .ruleId("pipeline:" + pipeline.id()).pipelineId(pipeline.id())
            .stepId(stepId)
            .build());
        addTrace(trace, "pipeline:strict",
            "pipeline:" + pipeline.id(),
            Map.of("pipelineId", pipeline.id(), "code", code));
        return ExecControl.STOP;
    }

    // ── condition ─────────────────────────────────────────────────────────────

    private static ExecControl execCondition(Compiled compiled, CompiledStep.ConditionStep step,
                                              String scopeId, OperatorContext ctx,
                                              List<Issue> issues, List<TraceEntry> trace) {
        CompiledCondition cond = compiled.conditions().get(step.conditionId());
        if (cond == null)
            throw new IllegalStateException("Missing condition: " + step.conditionId());

        boolean whenResult = evalWhen(cond.when(), compiled, ctx, scopeId, trace);

        addTrace(trace, "condition:when",
            "condition:" + cond.id(),
            Map.of("conditionId", cond.id(), "result", whenResult));

        if (!whenResult) return ExecControl.CONTINUE;

        addTrace(trace, "condition:steps:enter",
            "condition:" + cond.id(),
            Map.of("conditionId", cond.id(), "stepCount", cond.steps().size()));

        return execSteps(compiled, cond.steps(), scopeId, ctx, issues, trace);
    }

    // ── predicate evaluation ──────────────────────────────────────────────────

    private static boolean evalWhen(WhenExpr expr, Compiled compiled,
                                     OperatorContext ctx, String scopeId,
                                     List<TraceEntry> trace) {
        return switch (expr) {
            case WhenExpr.Single s -> evalPredicate(s.predicateId(), compiled, ctx, scopeId, trace);
            case WhenExpr.All    a -> a.items().stream().allMatch(
                i -> evalWhen(i, compiled, ctx, scopeId, trace));
            case WhenExpr.Any    a -> a.items().stream().anyMatch(
                i -> evalWhen(i, compiled, ctx, scopeId, trace));
        };
    }

    private static boolean evalPredicate(String ref, Compiled compiled,
                                          OperatorContext ctx, String scopeId,
                                          List<TraceEntry> trace) {
        String id = Compiler.resolveRef(ref, scopeId);
        Map<String, Object> rule = compiled.registry().get(id);
        if (rule == null) throw new IllegalStateException("Missing predicate: " + id);

        String field    = str(rule.get("field"));
        String operator = str(rule.get("operator"));

        boolean result;
        if (WildcardExpander.isWildcard(field)) {
            result = evalWildcardPredicate(rule, id, operator, compiled, ctx, trace, scopeId);
        } else {
            PredicateOperator predOp = compiled.operators().getPredicate(operator);
            if (predOp == null) throw new IllegalStateException("Unknown predicate operator: " + operator);
            PredicateResult pr = predOp.apply(rule, ctx);
            if (pr instanceof PredicateResult.Exception ex)
                throw new RuntimeException("Predicate error in " + id, ex.error());
            result = pr.isTrue(); // UNDEFINED → FALSE
        }

        addTrace(trace, "predicate:eval",
            scopeId + ":pred:" + id,
            Map.of("ruleId", id, "operator", operator, "field", field, "result", result));

        return result;
    }

    @SuppressWarnings("unchecked")
    private static boolean evalWildcardPredicate(Map<String, Object> rule, String ruleId,
                                                   String operator, Compiled compiled,
                                                   OperatorContext ctx,
                                                   List<TraceEntry> trace, String scopeId) {
        String field   = str(rule.get("field"));
        Map<String, Object> agg = getAgg(rule);
        String mode    = str(agg.getOrDefault("mode", "ANY")).toUpperCase();
        String onEmpty = str(agg.getOrDefault("onEmpty", "UNDEFINED")).toUpperCase();

        List<String> keys = WildcardExpander.expand(field, ctx.payload().keySet());

        addTrace(trace, "wildcard:expand",
            scopeId + ":pred:" + ruleId,
            Map.of("pattern", field, "matchedCount", keys.size(),
                   "sample", keys.stream().limit(5).toList()));

        PredicateOperator predOp = compiled.operators().getPredicate(operator);
        if (predOp == null) throw new IllegalStateException("Unknown predicate operator: " + operator);

        if (keys.isEmpty()) {
            boolean result = switch (onEmpty) {
                case "TRUE"  -> true;
                case "FALSE" -> false;
                case "ERROR" -> throw new IllegalStateException(
                    "Wildcard predicate matched 0 fields: " + field);
                default      -> false; // UNDEFINED → FALSE
            };
            addTrace(trace, "wildcard:aggregate",
                scopeId + ":pred:" + ruleId,
                Map.of("pattern", field, "mode", mode, "matchedCount", 0,
                       "onEmpty", onEmpty, "result", result));
            return result;
        }

        boolean result = switch (mode) {
            case "ANY" -> keys.stream().anyMatch(k -> evalPredicateForKey(k, rule, predOp, ruleId, ctx));
            case "ALL" -> keys.stream().allMatch(k -> evalPredicateForKey(k, rule, predOp, ruleId, ctx));
            case "COUNT" -> {
                long passCount = keys.stream()
                    .filter(k -> evalPredicateForKey(k, rule, predOp, ruleId, ctx))
                    .count();
                String countOp = str(agg.getOrDefault("op", ">="));
                int target = toInt(agg.get("value"));
                yield compareCount(countOp, (int) passCount, target);
            }
            default -> throw new IllegalStateException(
                "Unsupported predicate aggregate.mode: " + mode
                + " — should have been caught at compile time");
        };

        addTrace(trace, "wildcard:aggregate",
            scopeId + ":pred:" + ruleId,
            Map.of("pattern", field, "mode", mode, "matchedCount", keys.size(), "result", result));

        return result;
    }

    private static boolean evalPredicateForKey(String key, Map<String, Object> rule,
                                                PredicateOperator predOp, String ruleId,
                                                OperatorContext ctx) {
        Map<String, Object> ruleForKey = new HashMap<>(rule);
        ruleForKey.put("field", key);
        PredicateResult result = predOp.apply(ruleForKey, ctx);
        if (result instanceof PredicateResult.Exception ex)
            throw new RuntimeException("Predicate error in " + ruleId, ex.error());
        return result.isTrue();
    }

    // ── status + result building ──────────────────────────────────────────────

    private static String computeStatus(List<Issue> issues) {
        if (issues.stream().anyMatch(i -> "EXCEPTION".equals(i.getLevel()))) return "EXCEPTION";
        if (issues.stream().anyMatch(i -> "ERROR".equals(i.getLevel())))     return "ERROR";
        if (issues.stream().anyMatch(i -> "WARNING".equals(i.getLevel())))   return "OK_WITH_WARNINGS";
        return "OK";
    }

    private static PipelineResult finishPipeline(String statusStr, List<Issue> issues,
                                                   List<TraceEntry> trace,
                                                   String errorMsg, String errorStack,
                                                   boolean safeMode) {
        PipelineResult.Status  status  = PipelineResult.Status.valueOf(statusStr);
        PipelineResult.Control control = (status == PipelineResult.Status.EXCEPTION
                                         || status == PipelineResult.Status.ERROR
                                         || status == PipelineResult.Status.ABORT)
                                        ? PipelineResult.Control.STOP
                                        : PipelineResult.Control.CONTINUE;
        return PipelineResult.builder()
            .status(status).control(control)
            .issues(Collections.unmodifiableList(issues))
            .trace(traceList(trace))
            .errorMessage(errorMsg)
            .errorStack(safeMode ? null : errorStack)
            .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getAgg(Map<String, Object> rule) {
        return rule.get("aggregate") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static boolean compareCount(String op, int left, int right) {
        return switch (op) {
            case "==", "=" -> left == right;
            case "!="      -> left != right;
            case ">"       -> left > right;
            case ">="      -> left >= right;
            case "<"       -> left < right;
            case "<="      -> left <= right;
            default -> throw new IllegalArgumentException("Unsupported COUNT op: " + op);
        };
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) throw new IllegalArgumentException("Expected integer, got null");
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Expected integer, got: " + v); }
    }

    private static String toSnakeUpper(String key) {
        return key.replaceAll("([a-z])([A-Z])", "$1_$2")
                  .replaceAll("[^A-Za-z0-9]+", "_")
                  .toUpperCase();
    }

    private static void addTrace(List<TraceEntry> trace, String message,
                                  String scope, Map<String, Object> data) {
        if (trace != null) trace.add(new TraceEntry(message, scope, data));
    }

    private static List<TraceEntry> traceList(List<TraceEntry> trace) {
        return trace != null ? Collections.unmodifiableList(trace) : List.of();
    }

    static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    private static String stackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
