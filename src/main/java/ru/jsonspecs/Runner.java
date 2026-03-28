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
 */
final class Runner {

    private Runner() {}

    static PipelineResult runPipeline(Compiled compiled,
                                      String pipelineId,
                                      Map<String, Object> payload,
                                      RunOptions options) {
        boolean traceOn = options == null || options.isTrace();
        List<Issue>      issues = new ArrayList<>();
        List<TraceEntry> trace  = new ArrayList<>();

        try {
            Map<String, Object> flat = PayloadFlattener.flatten(payload != null ? payload : Map.of());

            CompiledPipeline pipeline = compiled.pipelines().get(pipelineId);
            if (pipeline == null)
                throw new IllegalArgumentException("Pipeline not found: " + pipelineId);

            // Check required context
            for (String key : pipeline.requiredContext()) {
                if (!DeepGet.get(flat, "$context." + key).ok()) {
                    String code = "CTX." + key
                        .replaceAll("([a-z])([A-Z])", "$1_$2")
                        .replaceAll("[^A-Za-z0-9]", "_")
                        .toUpperCase() + ".REQUIRED";
                    issues.add(Issue.builder("EXCEPTION")
                        .code(code)
                        .message("Missing required runtime context field: " + key)
                        .field("$context." + key)
                        .ruleId("pipeline:" + pipelineId)
                        .pipelineId(pipelineId)
                        .build());
                }
            }
            if (issues.stream().anyMatch(i -> "EXCEPTION".equals(i.getLevel()))) {
                return buildResult("EXCEPTION", issues, traceOn ? trace : List.of(), null, null);
            }

            OperatorContext ctx = new OperatorContext(flat, compiled.dictionaries());
            String control = execSteps(compiled, pipeline.steps(), pipelineId, ctx,
                                       issues, traceOn ? trace : null);

            // Strict pipeline escalation
            if (pipeline.strict() && "STOP".equals(control)) {
                boolean alreadyException = issues.stream().anyMatch(i -> "EXCEPTION".equals(i.getLevel()));
                if (!alreadyException) {
                    String code = pipeline.strictCode() != null && !pipeline.strictCode().isBlank()
                        ? pipeline.strictCode()
                        : "PIPELINE." + pipelineId.replace('.', '_').toUpperCase() + ".STRICT";
                    String msg = pipeline.strictMessage() != null && !pipeline.strictMessage().isBlank()
                        ? pipeline.strictMessage() : "Pipeline strict constraint failed";
                    issues.add(Issue.builder("EXCEPTION")
                        .code(code).message(msg)
                        .ruleId("pipeline:" + pipelineId).pipelineId(pipelineId)
                        .build());
                }
            }

            return buildResult(computeStatus(issues, control), issues, traceOn ? trace : List.of(), null, null);

        } catch (Exception e) {
            return buildResult("ABORT", issues, traceOn ? trace : List.of(), e.getMessage(), stackTrace(e));
        }
    }

    // ── step execution ────────────────────────────────────────────────────────

    private static String execSteps(Compiled compiled, List<CompiledStep> steps,
                                    String scopeId, OperatorContext ctx,
                                    List<Issue> issues, List<TraceEntry> trace) {
        for (CompiledStep step : steps) {
            String ctl = switch (step) {
                case CompiledStep.RuleStep      rs -> execRule(compiled, rs, ctx, issues, trace);
                case CompiledStep.PipelineStep  ps -> execNestedPipeline(compiled, ps, ctx, issues, trace);
                case CompiledStep.ConditionStep cs -> execCondition(compiled, cs, scopeId, ctx, issues, trace);
            };
            if ("STOP".equals(ctl)) return "STOP";
        }
        return "CONTINUE";
    }

    @SuppressWarnings("unchecked")
    private static String execRule(Compiled compiled, CompiledStep.RuleStep step,
                                   OperatorContext ctx, List<Issue> issues, List<TraceEntry> trace) {
        Map<String, Object> rule = compiled.registry().get(step.ruleId());
        if (rule == null) throw new IllegalStateException("Missing rule: " + step.ruleId());

        String role     = str(rule.get("role"));
        String operator = str(rule.get("operator"));
        String field    = str(rule.get("field"));

        // Predicates used as standalone steps don't produce issues
        if ("predicate".equals(role)) return "CONTINUE";

        if (WildcardExpander.isWildcard(field)) {
            return execWildcardRule(compiled, step, rule, field, operator, ctx, issues, trace);
        }

        CheckOperator op = compiled.operators().getCheck(operator);
        if (op == null) throw new IllegalStateException("Unknown check operator: " + operator);

        CheckResult result = op.apply(rule, ctx);
        addTrace(trace, "rule result", step.ruleId(), Map.of(
            "status", result instanceof CheckResult.Ok ? "OK" : "FAIL",
            "field", field, "operator", operator
        ));

        return applyCheckResult(result, rule, step, null, issues);
    }

    @SuppressWarnings("unchecked")
    private static String execWildcardRule(Compiled compiled, CompiledStep.RuleStep step,
                                            Map<String, Object> rule, String pattern, String operator,
                                            OperatorContext ctx, List<Issue> issues,
                                            List<TraceEntry> trace) {
        CheckOperator op = compiled.operators().getCheck(operator);
        List<String> keys = WildcardExpander.expand(pattern, ctx.payload().keySet());

        Map<String, Object> agg = rule.get("aggregate") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();
        String mode    = str(agg.getOrDefault("mode", "EACH")).toUpperCase();
        String onEmpty = str(agg.getOrDefault("onEmpty", "UNDEFINED")).toUpperCase();

        if (keys.isEmpty()) {
            return "UNDEFINED".equals(onEmpty) ? "CONTINUE"
                 : "FALSE".equals(onEmpty)     ? applyCheckResult(CheckResult.fail(), rule, step, null, issues)
                 : "CONTINUE";
        }

        return switch (mode) {
            case "EACH" -> {
                String ctl = "CONTINUE";
                for (String key : keys) {
                    Map<String, Object> ruleForKey = new HashMap<>(rule);
                    ruleForKey.put("field", key);
                    CheckResult r = op.apply(ruleForKey, ctx);
                    if (!"CONTINUE".equals(applyCheckResult(r, rule, step, key, issues)))
                        ctl = "STOP";
                }
                yield ctl;
            }
            case "ALL" -> {
                boolean allPass = keys.stream().allMatch(key -> {
                    Map<String, Object> r2 = new HashMap<>(rule); r2.put("field", key);
                    return op.apply(r2, ctx) instanceof CheckResult.Ok;
                });
                yield allPass ? "CONTINUE" : applyCheckResult(CheckResult.fail(), rule, step, null, issues);
            }
            case "ANY" -> {
                boolean anyPass = keys.stream().anyMatch(key -> {
                    Map<String, Object> r2 = new HashMap<>(rule); r2.put("field", key);
                    return op.apply(r2, ctx) instanceof CheckResult.Ok;
                });
                yield anyPass ? "CONTINUE" : applyCheckResult(CheckResult.fail(), rule, step, null, issues);
            }
            default -> "CONTINUE";
        };
    }

    private static String applyCheckResult(CheckResult result, Map<String, Object> rule,
                                            CompiledStep.RuleStep step, String overrideField,
                                            List<Issue> issues) {
        return switch (result) {
            case CheckResult.Ok ignored -> "CONTINUE";
            case CheckResult.Exception ex ->
                throw new RuntimeException("Operator error in rule " + step.ruleId(), ex.error());
            case CheckResult.Fails fs -> {
                String ctl = "CONTINUE";
                for (CheckResult.Fail f : fs.failures()) {
                    if (!"CONTINUE".equals(pushIssue(rule, step, f.field(), f.actual(), issues)))
                        ctl = "STOP";
                }
                yield ctl;
            }
            case CheckResult.Fail f ->
                pushIssue(rule, step, overrideField != null ? overrideField : f.field(), f.actual(), issues);
        };
    }

    private static String pushIssue(Map<String, Object> rule, CompiledStep.RuleStep step,
                                     String field, Object actual, List<Issue> issues) {
        String level = str(rule.get("level"));
        issues.add(Issue.builder(level)
            .code(str(rule.get("code")))
            .message(str(rule.get("message")))
            .field(field != null ? field : str(rule.get("field")))
            .ruleId(step.ruleId())
            .expected(rule.get("value"))
            .actual(actual)
            .stepId(step.stepId())
            .build());
        return "EXCEPTION".equals(level) ? "STOP" : "CONTINUE";
    }

    private static String execNestedPipeline(Compiled compiled, CompiledStep.PipelineStep step,
                                              OperatorContext ctx, List<Issue> issues,
                                              List<TraceEntry> trace) {
        CompiledPipeline nested = compiled.pipelines().get(step.pipelineId());
        if (nested == null) throw new IllegalStateException("Missing nested pipeline: " + step.pipelineId());
        return execSteps(compiled, nested.steps(), step.pipelineId(), ctx, issues, trace);
    }

    private static String execCondition(Compiled compiled, CompiledStep.ConditionStep step,
                                         String scopeId, OperatorContext ctx,
                                         List<Issue> issues, List<TraceEntry> trace) {
        CompiledCondition cond = compiled.conditions().get(step.conditionId());
        if (cond == null) throw new IllegalStateException("Missing condition: " + step.conditionId());
        if (!evalWhen(cond.when(), compiled, ctx, scopeId)) return "CONTINUE";
        return execSteps(compiled, cond.steps(), scopeId, ctx, issues, trace);
    }

    // ── predicate evaluation ──────────────────────────────────────────────────

    private static boolean evalWhen(WhenExpr expr, Compiled compiled,
                                     OperatorContext ctx, String scopeId) {
        return switch (expr) {
            case WhenExpr.Single s -> evalPredicate(s.predicateId(), compiled, ctx, scopeId);
            case WhenExpr.All    a -> a.items().stream().allMatch(i -> evalWhen(i, compiled, ctx, scopeId));
            case WhenExpr.Any    a -> a.items().stream().anyMatch(i -> evalWhen(i, compiled, ctx, scopeId));
        };
    }

    private static boolean evalPredicate(String ref, Compiled compiled,
                                          OperatorContext ctx, String scopeId) {
        String id = Compiler.resolveRef(ref, scopeId);
        Map<String, Object> rule = compiled.registry().get(id);
        if (rule == null) throw new IllegalStateException("Missing predicate: " + id);
        String op = str(rule.get("operator"));
        PredicateOperator predOp = compiled.operators().getPredicate(op);
        if (predOp == null) throw new IllegalStateException("Unknown predicate operator: " + op);
        PredicateResult result = predOp.apply(rule, ctx);
        if (result instanceof PredicateResult.Exception ex)
            throw new RuntimeException("Predicate error in " + id, ex.error());
        return result.isTrue();
    }

    // ── status computation ────────────────────────────────────────────────────

    private static String computeStatus(List<Issue> issues, String control) {
        boolean hasException = "STOP".equals(control)
            && issues.stream().anyMatch(i -> "EXCEPTION".equals(i.getLevel()));
        if (hasException) return "EXCEPTION";
        boolean hasErrors = issues.stream()
            .anyMatch(i -> "ERROR".equals(i.getLevel()) || "EXCEPTION".equals(i.getLevel()));
        if (hasErrors) return "ERROR";
        return issues.stream().anyMatch(i -> "WARNING".equals(i.getLevel()))
            ? "OK_WITH_WARNINGS" : "OK";
    }

    private static PipelineResult buildResult(String statusStr, List<Issue> issues,
                                               List<TraceEntry> trace,
                                               String errorMsg, String errorStack) {
        PipelineResult.Status  status  = PipelineResult.Status.valueOf(statusStr);
        PipelineResult.Control control = (status == PipelineResult.Status.EXCEPTION
                                         || status == PipelineResult.Status.ERROR
                                         || status == PipelineResult.Status.ABORT)
                                        ? PipelineResult.Control.STOP
                                        : PipelineResult.Control.CONTINUE;
        return PipelineResult.builder()
            .status(status)
            .control(control)
            .issues(Collections.unmodifiableList(issues))
            .trace(Collections.unmodifiableList(trace))
            .errorMessage(errorMsg)
            .errorStack(errorStack)
            .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void addTrace(List<TraceEntry> trace, String message,
                                  String scope, Map<String, Object> data) {
        if (trace != null) trace.add(new TraceEntry(message, scope, data));
    }

    static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    private static String stackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
