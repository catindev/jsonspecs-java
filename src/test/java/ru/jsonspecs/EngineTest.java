package ru.jsonspecs;

import ru.jsonspecs.operators.*;
import ru.jsonspecs.util.DeepGet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    static Engine engine;

    @BeforeAll static void setup() {
        engine = Engine.create(OperatorPack.standard());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static Map<String, Object> rule(String id, String op, String field, String level,
                                     String code, Object... extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("type", "rule"); m.put("description", id);
        m.put("role", "check"); m.put("operator", op); m.put("field", field);
        m.put("level", level); m.put("code", code); m.put("message", code + " failed");
        for (int i = 0; i < extras.length - 1; i += 2) m.put((String) extras[i], extras[i + 1]);
        return m;
    }

    static Map<String, Object> predicate(String id, String op, String field, Object... extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("type", "rule"); m.put("description", id);
        m.put("role", "predicate"); m.put("operator", op); m.put("field", field);
        for (int i = 0; i < extras.length - 1; i += 2) m.put((String) extras[i], extras[i + 1]);
        return m;
    }

    static Map<String, Object> pipeline(String id, List<Map<String, Object>> flow) {
        return Map.of("id", id, "type", "pipeline", "description", id,
                      "entrypoint", true, "strict", false, "flow", flow);
    }

    static Map<String, Object> condition(String id, Object when, List<Map<String, Object>> steps) {
        return Map.of("id", id, "type", "condition", "description", id, "when", when, "steps", steps);
    }

    static Map<String, Object> step(String kind, String ref) { return Map.of(kind, ref); }

    // ── compiler tests ────────────────────────────────────────────────────────

    @Test void compile_duplicateId_throws() {
        var r = rule("library.r", "not_empty", "x", "ERROR", "X");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r, r)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("Duplicate artifact id")));
        assertFalse(ex.getMessage().isBlank());
    }

    @Test void compile_missingLevelCodeMessage_allThreeErrors() {
        var bad = new LinkedHashMap<>(rule("library.r", "not_empty", "x", "ERROR", "X"));
        bad.remove("level"); bad.remove("code"); bad.remove("message");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(bad)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("level")));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("code")));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("message")));
    }

    @Test void compile_duplicateErrorCode_throws() {
        var r1 = rule("library.r1", "not_empty", "a", "ERROR", "SAME");
        var r2 = rule("library.r2", "not_empty", "b", "ERROR", "SAME");
        var p  = pipeline("p", List.of(step("rule", "library.r1"), step("rule", "library.r2")));
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r1, r2, p)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("SAME")));
    }

    @Test void compile_unresolvedRef_throws() {
        var p = pipeline("p", List.of(step("rule", "library.does.not.exist")));
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(p)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("library.does.not.exist")));
    }

    @Test void compile_invalidRegex_caughtAtCompileTime() {
        var r = rule("library.r", "matches_regex", "x", "ERROR", "X", "value", "[invalid(");
        var p = pipeline("p", List.of(step("rule", "library.r")));
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r, p)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("invalid regex")));
    }

    @Test void compile_validRegex_ok() {
        var r = rule("library.r", "matches_regex", "x", "ERROR", "X", "value", "^\\d{12}$");
        var p = pipeline("p", List.of(step("rule", "library.r")));
        assertDoesNotThrow(() -> engine.compile(List.of(r, p)));
    }

    @Test void compile_pipelineCycle_throws() {
        var pA = Map.of("id","pipe.a","type","pipeline","description","a",
                         "entrypoint",true,"strict",false,"flow",List.of(step("pipeline","pipe.b")));
        var pB = Map.of("id","pipe.b","type","pipeline","description","b",
                         "entrypoint",false,"strict",false,"flow",List.of(step("pipeline","pipe.a")));
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(pA, pB)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.toLowerCase().contains("cycle")));
    }

    // ── runner: status values ─────────────────────────────────────────────────

    @Test void run_ok() {
        var r = rule("library.r", "not_empty", "name", "ERROR", "NAME.REQUIRED");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled, "p", Map.of("name", "Ivan"));
        assertEquals(PipelineResult.Status.OK, result.getStatus());
        assertEquals(PipelineResult.Control.CONTINUE, result.getControl());
        assertTrue(result.getIssues().isEmpty());
        assertTrue(result.isOk());
    }

    @Test void run_error() {
        var r = rule("library.r", "not_empty", "name", "ERROR", "NAME.REQUIRED");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled, "p", Map.of("name", ""));
        assertEquals(PipelineResult.Status.ERROR, result.getStatus());
        assertEquals(PipelineResult.Control.STOP, result.getControl());
        assertEquals(1, result.getIssues().size());
        assertEquals("NAME.REQUIRED", result.getIssues().get(0).getCode());
        assertEquals("name", result.getIssues().get(0).getField());
        assertEquals("ERROR", result.getIssues().get(0).getLevel());
        assertFalse(result.isOk());
    }

    @Test void run_okWithWarnings() {
        var r = rule("library.r", "not_empty", "phone", "WARNING", "PHONE.SOFT");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled, "p", Map.of("phone", ""));
        assertEquals(PipelineResult.Status.OK_WITH_WARNINGS, result.getStatus());
        assertEquals(PipelineResult.Control.CONTINUE, result.getControl());
        assertTrue(result.isOk());
    }

    @Test void run_exception_stopsPipeline() {
        var r1 = rule("library.r1", "not_empty", "doc", "EXCEPTION", "DOC.BLOCK");
        var r2 = rule("library.r2", "not_empty", "name", "ERROR", "NAME.REQUIRED");
        var compiled = engine.compile(List.of(r1, r2,
            pipeline("p", List.of(step("rule","library.r1"), step("rule","library.r2")))));
        var result = engine.runPipeline(compiled, "p", Map.of("doc","","name",""));
        assertEquals(PipelineResult.Status.EXCEPTION, result.getStatus());
        assertTrue(result.getIssues().stream().noneMatch(i -> "NAME.REQUIRED".equals(i.getCode())));
    }

    @Test void run_accumulatesAllErrors() {
        var r1 = rule("library.r1", "not_empty", "name", "ERROR", "NAME.REQUIRED");
        var r2 = rule("library.r2", "not_empty", "inn",  "ERROR", "INN.REQUIRED");
        var compiled = engine.compile(List.of(r1, r2,
            pipeline("p", List.of(step("rule","library.r1"), step("rule","library.r2")))));
        var result = engine.runPipeline(compiled, "p", Map.of("name","","inn",""));
        assertEquals(2, result.getIssues().size());
    }

    // ── payload forms ─────────────────────────────────────────────────────────

    @Test void run_nestedJson() {
        var r = rule("library.r", "not_empty", "person.name", "ERROR", "NAME");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p", Map.of("person", Map.of("name","Ivan"))).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p", Map.of("person", Map.of("name",""))).getStatus());
    }

    @Test void run_flatMap() {
        var r = rule("library.r", "not_empty", "person.name", "ERROR", "NAME");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p", Map.of("person.name","Ivan")).getStatus());
    }

    // ── wildcards ─────────────────────────────────────────────────────────────

    @Test void run_wildcardCheck_eachMode() {
        var r = rule("library.r", "not_empty", "items[*].name", "ERROR", "ITEM.NAME");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled, "p",
            Map.of("items", List.of(Map.of("name","A"), Map.of("name",""), Map.of("name","C"))));
        assertEquals(PipelineResult.Status.ERROR, result.getStatus());
        assertEquals(1, result.getIssues().size());
        assertEquals("items[1].name", result.getIssues().get(0).getField());
    }

    // ── conditions ────────────────────────────────────────────────────────────

    @Test void run_condition_skipsWhenFalse() {
        var pred  = predicate("library.pred", "equals", "isForeign", "value", true);
        var check = rule("library.check", "not_empty", "tin", "ERROR", "TIN.REQUIRED");
        var cond  = condition("library.cond", Map.of("all", List.of("library.pred")),
                              List.of(step("rule","library.check")));
        var compiled = engine.compile(List.of(pred, check, cond,
            pipeline("p", List.of(step("condition","library.cond")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p", Map.of("isForeign", false, "tin", "")).getStatus());
    }

    @Test void run_condition_runsWhenTrue() {
        var pred  = predicate("library.pred", "equals", "isForeign", "value", true);
        var check = rule("library.check", "not_empty", "tin", "ERROR", "TIN.REQUIRED");
        var cond  = condition("library.cond", Map.of("all", List.of("library.pred")),
                              List.of(step("rule","library.check")));
        var compiled = engine.compile(List.of(pred, check, cond,
            pipeline("p", List.of(step("condition","library.cond")))));
        var result = engine.runPipeline(compiled,"p", Map.of("isForeign", true, "tin", ""));
        assertEquals(PipelineResult.Status.ERROR, result.getStatus());
        assertEquals("TIN.REQUIRED", result.getIssues().get(0).getCode());
    }

    // ── operators ─────────────────────────────────────────────────────────────

    @Test void op_matchesRegex_pass() {
        var r = rule("library.r","matches_regex","inn","ERROR","INN.FORMAT","value","^\\d{12}$");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("inn","123456789012")).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of("inn","abc")).getStatus());
    }

    @Test void op_matchesRegex_flags_caseInsensitive() {
        var r = rule("library.r","matches_regex","code","ERROR","CODE","value","^[a-z]+$","flags","i");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("code","ABC")).getStatus());
    }

    @Test void op_fieldGreaterOrEqualThanField_date() {
        var r = rule("library.r","field_greater_or_equal_than_field","doc.expire","ERROR","DOC.EXPIRED",
                     "value_field","$context.currentDate");
        var p = Map.of("id","p","type","pipeline","description","p","entrypoint",true,"strict",false,
                        "required_context",List.of("currentDate"),"flow",List.of(step("rule","library.r")));
        var compiled = engine.compile(List.of(r, p));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",
                Map.of("doc",Map.of("expire","2030-01-01"),"__context",Map.of("currentDate","2024-01-01")))
            .getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",
                Map.of("doc",Map.of("expire","2020-01-01"),"__context",Map.of("currentDate","2024-01-01")))
            .getStatus());
    }

    // ── trace ─────────────────────────────────────────────────────────────────

    @Test void run_traceIncludedByDefault() {
        var r = rule("library.r","not_empty","x","ERROR","X");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled,"p",Map.of("x","v"));
        assertFalse(result.getTrace().isEmpty());
    }

    @Test void run_traceDisabled() {
        var r = rule("library.r","not_empty","x","ERROR","X");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled,"p",Map.of("x","v"), RunOptions.NO_TRACE);
        assertTrue(result.getTrace().isEmpty());
    }

    // ── custom operator ───────────────────────────────────────────────────────

    @Test void customOperator_validInn() {
        OperatorPack operators = OperatorPack.standard().withCheck("valid_inn", (rule2, ctx) -> {
            DeepGet.Result r = ctx.get((String) rule2.get("field"));
            if (!r.ok()) return CheckResult.fail();
            String inn = String.valueOf(r.value());
            boolean allSame = inn.chars().distinct().count() == 1;
            return allSame ? CheckResult.fail(inn) : CheckResult.ok();
        });
        Engine customEngine = Engine.create(operators);
        var r = rule("library.r","valid_inn","inn","WARNING","INN.REPEATED");
        var compiled = customEngine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK_WITH_WARNINGS,
            customEngine.runPipeline(compiled,"p",Map.of("inn","111111111111")).getStatus());
        assertEquals(PipelineResult.Status.OK,
            customEngine.runPipeline(compiled,"p",Map.of("inn","123456789012")).getStatus());
    }

    // ── CompilationException ──────────────────────────────────────────────────

    @Test void compilationException_containsAllErrors() {
        var bad = new LinkedHashMap<>(rule("library.r","not_empty","x","ERROR","X"));
        bad.remove("level"); bad.remove("code"); bad.remove("message");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(bad)));
        assertFalse(ex.getErrors().isEmpty());
        assertTrue(ex.getMessage().contains("Compilation failed"));
    }

    // ── PipelineResult convenience methods ────────────────────────────────────

    @Test void pipelineResult_isOk_and_hasErrors() {
        var r = rule("library.r","not_empty","x","ERROR","X");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertTrue(engine.runPipeline(compiled,"p",Map.of("x","v")).isOk());
        assertTrue(engine.runPipeline(compiled,"p",Map.of("x","")).hasErrors());
    }
}
