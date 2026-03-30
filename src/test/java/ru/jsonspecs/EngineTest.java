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

    // ── fixes: in_dictionary ──────────────────────────────────────────────────

    @Test void op_inDictionary_stringEntries() {
        var dict = Map.of("id","doc_types","type","dictionary","description","d",
                           "entries", List.of("21","22","99"));
        var r = rule("library.r","in_dictionary","docType","ERROR","DOC.TYPE",
                     "dictionary", Map.of("type","static","id","doc_types"));
        var compiled = engine.compile(List.of(dict, r,
            pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("docType","21")).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of("docType","55")).getStatus());
    }

    @Test void op_inDictionary_codeObjectEntries() {
        var dict = Map.of("id","statuses","type","dictionary","description","d",
                           "entries", List.of(
                               Map.of("code","ACTIVE","label","Active"),
                               Map.of("code","BLOCKED","label","Blocked")));
        var r = rule("library.r","in_dictionary","status","ERROR","STATUS.INVALID",
                     "dictionary", Map.of("type","static","id","statuses"));
        var compiled = engine.compile(List.of(dict, r,
            pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("status","ACTIVE")).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of("status","UNKNOWN")).getStatus());
    }

    @Test void op_inDictionary_valueObjectEntries() {
        var dict = Map.of("id","countries","type","dictionary","description","d",
                           "entries", List.of(
                               Map.of("value","RU","name","Russia"),
                               Map.of("value","DE","name","Germany")));
        var r = rule("library.r","in_dictionary","country","ERROR","COUNTRY.INVALID",
                     "dictionary", Map.of("type","static","id","countries"));
        var compiled = engine.compile(List.of(dict, r,
            pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("country","RU")).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of("country","US")).getStatus());
    }

    // ── fixes: strictEquals ───────────────────────────────────────────────────

    @Test void op_equals_noStringToNumberCoercion() {
        // "1" (string) must NOT equal 1 (number)
        var r = rule("library.r","equals","count","ERROR","COUNT.WRONG","value",1);
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("count",1)).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of("count","1")).getStatus());
    }

    @Test void op_equals_crossNumericType() {
        // Integer(1) and Long(1) should be equal (same JSON number, different Java types)
        var r = rule("library.r","equals","count","ERROR","COUNT.WRONG","value",1L);
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("count",1)).getStatus());
    }

    // ── fixes: length_max absent field ────────────────────────────────────────

    @Test void op_lengthMax_absentField_isFail() {
        var r = rule("library.r","length_max","name","ERROR","NAME.TOO_LONG","value",50);
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of()).getStatus());
    }

    // ── fixes: predicate forbidden fields ────────────────────────────────────

    @Test void compile_predicate_withLevelCodeMessage_throws() {
        var bad = new LinkedHashMap<>(predicate("library.p","not_empty","x"));
        bad.put("level","ERROR"); bad.put("code","X"); bad.put("message","x");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(bad)));
        assertTrue(ex.getErrors().stream().anyMatch(s ->
            s.contains("must not have level/code/message")));
    }

    // ── fixes: strict nested pipeline ────────────────────────────────────────

    @Test void run_strictNestedPipeline_escalatesToException() {
        var inner = Map.of(
            "id","internal.inner","type","pipeline","description","inner",
            "entrypoint",false,"strict",true,
            "message","Inner checks failed","strictCode","INNER.STRICT",
            "flow", List.of(step("rule","library.r_err")));
        var outer = Map.of(
            "id","outer","type","pipeline","description","outer",
            "entrypoint",true,"strict",false,
            "flow", List.of(
                step("pipeline","internal.inner"),
                Map.of("rule","library.r_after")));
        var rErr   = rule("library.r_err",   "not_empty","required","ERROR","REQ.MISSING");
        var rAfter = rule("library.r_after","not_empty","other","ERROR","OTHER.MISSING");
        var compiled = engine.compile(List.of(rErr, rAfter, inner, outer));

        // inner pipeline fails → strict escalates → outer stops → r_after never runs
        var result = engine.runPipeline(compiled,"outer",Map.of("other","x"));
        assertEquals(PipelineResult.Status.EXCEPTION, result.getStatus());
        assertTrue(result.getIssues().stream().anyMatch(i -> "INNER.STRICT".equals(i.getCode())));
        assertTrue(result.getIssues().stream().noneMatch(i -> "OTHER.MISSING".equals(i.getCode())));
    }

    @Test void run_strictNestedPipeline_errorIssuesTrigger_notJustStop() {
        // strict should fire when there are ERROR issues, even if no EXCEPTION-level rule triggered STOP
        var inner = Map.of(
            "id","internal.inner","type","pipeline","description","inner",
            "entrypoint",false,"strict",true,
            "message","Strict","strictCode","STRICT",
            "flow", List.of(step("rule","library.r_err")));
        var outer = Map.of(
            "id","outer","type","pipeline","description","outer",
            "entrypoint",true,"strict",false,
            "flow", List.of(step("pipeline","internal.inner")));
        var rErr = rule("library.r_err","not_empty","x","ERROR","X.ERR");
        var compiled = engine.compile(List.of(rErr, inner, outer));

        var result = engine.runPipeline(compiled,"outer",Map.of());
        assertEquals(PipelineResult.Status.EXCEPTION, result.getStatus());
        assertTrue(result.getIssues().stream().anyMatch(i -> "STRICT".equals(i.getCode())));
    }

    // ── fixes: wildcard check COUNT mode ─────────────────────────────────────

    @Test void run_wildcardCheck_countMode() {
        var agg = Map.of("mode","COUNT","op",">=","value",2);
        var ruleMap = new LinkedHashMap<>(rule("library.r","not_empty","items[*].name","ERROR","ITEMS.COUNT"));
        ruleMap.put("aggregate", agg);
        var compiled = engine.compile(List.of(ruleMap,
            pipeline("p", List.of(step("rule","library.r")))));

        // 3 items, 2 with non-empty name → passes (2 >= 2)
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",
                Map.of("items", List.of(
                    Map.of("name","A"), Map.of("name","B"), Map.of("name","")))).getStatus());

        // 3 items, 1 with non-empty name → fails (1 < 2)
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",
                Map.of("items", List.of(
                    Map.of("name","A"), Map.of("name",""), Map.of("name","")))).getStatus());
    }

    // ── fixes: wildcard predicate ─────────────────────────────────────────────

    @Test void run_wildcardPredicate_anyMode() {
        var pred = new LinkedHashMap<>(predicate("library.pred","not_empty","items[*].blocked"));
        pred.put("aggregate", Map.of("mode","ANY"));
        var check = rule("library.chk","not_empty","extra","ERROR","EXTRA.REQ");
        var cond  = condition("library.cond","library.pred", List.of(step("rule","library.chk")));
        var compiled = engine.compile(List.of(pred, check, cond,
            pipeline("p", List.of(step("condition","library.cond")))));

        // any item has blocked → condition activates → check fails (extra missing)
        var resultWithBlocked = engine.runPipeline(compiled,"p",
            Map.of("items", List.of(
                Map.of("blocked",true), Map.of("blocked",""))));
        assertEquals(PipelineResult.Status.ERROR, resultWithBlocked.getStatus());

        // no item has blocked → condition skipped → OK
        var resultAllEmpty = engine.runPipeline(compiled,"p",
            Map.of("items", List.of(Map.of("blocked",""), Map.of("blocked",""))));
        assertEquals(PipelineResult.Status.OK, resultAllEmpty.getStatus());
    }

    // ── immutability: mutation after compile must not affect compiled snapshot ──

    @Test void compiled_immutableFrom_originalMutation() {
        var ruleMap = new LinkedHashMap<>(rule("library.r","not_empty","x","ERROR","X.REQ"));
        var artifacts = new ArrayList<Map<String,Object>>();
        artifacts.add(ruleMap);
        artifacts.add(pipeline("p", List.of(step("rule","library.r"))));
        var compiled = engine.compile(artifacts);

        // Verify baseline
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of("x","")).getStatus());
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("x","v")).getStatus());

        // Mutate the original artifact — must NOT affect compiled snapshot
        ruleMap.put("operator","is_empty");  // inverted semantics
        ruleMap.put("code","MUTATED");

        // Compiled snapshot must still behave as before
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",Map.of("x","")).getStatus(),
            "compiled snapshot must not be affected by post-compile mutation");
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",Map.of("x","v")).getStatus(),
            "compiled snapshot must not be affected by post-compile mutation");
    }

    @Test void compiled_immutableFrom_nestedMapMutation() {
        // Mutate a nested object inside an artifact (e.g. aggregate)
        var agg = new LinkedHashMap<String,Object>();
        agg.put("mode","EACH");
        var ruleMap = new LinkedHashMap<>(rule("library.r","not_empty","items[*].x","ERROR","X.REQ"));
        ruleMap.put("aggregate", agg);
        var artifacts = new ArrayList<Map<String,Object>>();
        artifacts.add(ruleMap);
        artifacts.add(pipeline("p", List.of(step("rule","library.r"))));
        var compiled = engine.compile(artifacts);

        // Baseline: one failing item → one issue
        var result1 = engine.runPipeline(compiled,"p",
            Map.of("items", List.of(Map.of("x",""), Map.of("x","v"))));
        assertEquals(1, result1.getIssues().size());

        // Mutate the nested aggregate object
        agg.put("mode","COUNT");
        agg.put("op",">=");
        agg.put("value", 99);

        // Snapshot must still use EACH (one issue per failure)
        var result2 = engine.runPipeline(compiled,"p",
            Map.of("items", List.of(Map.of("x",""), Map.of("x","v"))));
        assertEquals(1, result2.getIssues().size(),
            "nested aggregate mutation must not affect compiled snapshot");
    }

    // ── compile-time: aggregate validation ───────────────────────────────────

    @Test void compile_aggregate_invalidCheckMode_throws() {
        var ruleMap = new LinkedHashMap<>(rule("library.r","not_empty","x[*]","ERROR","X"));
        ruleMap.put("aggregate", Map.of("mode","WRONG_MODE"));
        var ex = assertThrows(CompilationException.class,
            () -> engine.compile(List.of(ruleMap, pipeline("p", List.of(step("rule","library.r"))))));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("aggregate.mode")));
    }

    @Test void compile_aggregate_countWithoutValue_throws() {
        var ruleMap = new LinkedHashMap<>(rule("library.r","not_empty","x[*]","ERROR","X"));
        ruleMap.put("aggregate", Map.of("mode","COUNT"));
        var ex = assertThrows(CompilationException.class,
            () -> engine.compile(List.of(ruleMap, pipeline("p", List.of(step("rule","library.r"))))));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("aggregate.value")));
    }

    @Test void compile_aggregate_minMaxForPredicate_throws() {
        var pred = new LinkedHashMap<>(predicate("library.p","greater_than","x[*]","value",0));
        pred.put("aggregate", Map.of("mode","MIN"));
        var ex = assertThrows(CompilationException.class,
            () -> engine.compile(List.of(pred)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("aggregate.mode")));
    }

    @Test void compile_aggregate_invalidOnEmpty_throws() {
        var ruleMap = new LinkedHashMap<>(rule("library.r","not_empty","x[*]","ERROR","X"));
        ruleMap.put("aggregate", Map.of("onEmpty","BOGUS_VALUE"));
        var ex = assertThrows(CompilationException.class,
            () -> engine.compile(List.of(ruleMap, pipeline("p", List.of(step("rule","library.r"))))));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("onEmpty")));
    }

    @Test void compile_aggregate_validCheckModes_ok() {
        for (String mode : List.of("EACH","ALL","MIN","MAX")) {
            var ruleMap = new LinkedHashMap<>(rule("library.r."+mode,"greater_than","x[*]","ERROR","X."+mode,"value",0));
            ruleMap.put("aggregate", Map.of("mode", mode));
            var p = pipeline("p."+mode, List.of(step("rule","library.r."+mode)));
            assertDoesNotThrow(() -> engine.compile(List.of(ruleMap, p)),
                "mode " + mode + " should compile without error");
        }
    }

    @Test void compile_aggregate_countMode_withValue_ok() {
        var ruleMap = new LinkedHashMap<>(rule("library.r","not_empty","x[*]","ERROR","X"));
        ruleMap.put("aggregate", Map.of("mode","COUNT","op",">=","value",2));
        var p = pipeline("p", List.of(step("rule","library.r")));
        assertDoesNotThrow(() -> engine.compile(List.of(ruleMap, p)));
    }

    // ── compile-time: dictionary entries validation ───────────────────────────

    @Test void compile_dictionary_missingEntries_throws() {
        var dict = Map.of("id","d","type","dictionary","description","d","entries",List.of());
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(dict)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("entries")));
    }

    @Test void compile_dictionary_notAnArray_throws() {
        var dict = new LinkedHashMap<String,Object>();
        dict.put("id","d"); dict.put("type","dictionary"); dict.put("description","d");
        dict.put("entries","not-a-list");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(dict)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("entries")));
    }

    @Test void compile_dictionary_objectEntryMissingCodeOrValue_throws() {
        var dict = Map.of("id","d","type","dictionary","description","d",
                           "entries", List.of(Map.of("label","Active")));
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(dict)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("'code'") || s.contains("'value'")));
    }

    @Test void compile_dictionary_validStringEntries_ok() {
        var dict = Map.of("id","d","type","dictionary","description","d",
                           "entries", List.of("A","B","C"));
        assertDoesNotThrow(() -> engine.compile(List.of(dict)));
    }

    @Test void compile_dictionary_validObjectEntries_ok() {
        var dict = Map.of("id","d","type","dictionary","description","d",
                           "entries", List.of(
                               Map.of("code","ACTIVE","label","Active"),
                               Map.of("value","RU","name","Russia")));
        assertDoesNotThrow(() -> engine.compile(List.of(dict)));
    }

    // ── compile-time: required_context validation ─────────────────────────────

    @Test void compile_requiredContext_nonStringElement_throws() {
        var ruleMap = rule("library.r","not_empty","x","ERROR","X");
        var p = new LinkedHashMap<String,Object>();
        p.put("id","p"); p.put("type","pipeline"); p.put("description","p");
        p.put("entrypoint",true); p.put("strict",false);
        p.put("flow", List.of(step("rule","library.r")));
        p.put("required_context", List.of("validKey", 999));  // 999 is not a string
        var ex = assertThrows(CompilationException.class,
            () -> engine.compile(List.of(ruleMap, p)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("required_context")));
    }

    @Test void compile_requiredContext_blankString_throws() {
        var ruleMap = rule("library.r","not_empty","x","ERROR","X");
        var p = new LinkedHashMap<String,Object>();
        p.put("id","p"); p.put("type","pipeline"); p.put("description","p");
        p.put("entrypoint",true); p.put("strict",false);
        p.put("flow", List.of(step("rule","library.r")));
        p.put("required_context", List.of("validKey", "  "));  // blank string
        var ex = assertThrows(CompilationException.class,
            () -> engine.compile(List.of(ruleMap, p)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("required_context")));
    }

    @Test void compile_requiredContext_notAList_throws() {
        var ruleMap = rule("library.r","not_empty","x","ERROR","X");
        var p = new LinkedHashMap<String,Object>();
        p.put("id","p"); p.put("type","pipeline"); p.put("description","p");
        p.put("entrypoint",true); p.put("strict",false);
        p.put("flow", List.of(step("rule","library.r")));
        p.put("required_context", "not-a-list");
        var ex = assertThrows(CompilationException.class,
            () -> engine.compile(List.of(ruleMap, p)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("required_context")));
    }


    @Test void compile_pipelineStrictCode_blank_throws() {
        var r = rule("library.r", "not_empty", "x", "ERROR", "X");
        var p = new LinkedHashMap<String,Object>();
        p.put("id","p"); p.put("type","pipeline"); p.put("description","p");
        p.put("entrypoint",true); p.put("strict",true); p.put("message","strict failed");
        p.put("strictCode","   ");
        p.put("flow", List.of(step("rule","library.r")));
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r, p)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("strictCode must be non-empty string if provided")));
    }

    @Test void compile_anyFilled_withoutFields_throws() {
        var r = rule("library.r", "any_filled", "ignored", "ERROR", "X");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("any_filled requires fields[]")));
    }

    @Test void compile_fieldEqualsField_withoutValueField_throws() {
        var r = rule("library.r", "field_equals_field", "left", "ERROR", "X");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("field_equals_field requires value_field")));
    }

    @Test void compile_inDictionary_stringShortcut_throws() {
        var dict = Map.of("id","currencies","type","dictionary","description","d",
                           "entries", List.of("RUB","USD"));
        var r = rule("library.r", "in_dictionary", "currency", "ERROR", "X",
                     "dictionary", "currencies");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(dict, r)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("in_dictionary requires dictionary{type:static,id}")));
    }

    @Test void compile_inDictionary_missingDictionary_throws() {
        var r = rule("library.r", "in_dictionary", "currency", "ERROR", "X",
                     "dictionary", Map.of("type","static","id","missing"));
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("dictionary not found: missing")));
    }

    @Test void compile_meta_nonObject_throws() {
        var r = rule("library.r", "equals", "x", "ERROR", "X", "value", 1, "meta", "oops");
        var ex = assertThrows(CompilationException.class, () -> engine.compile(List.of(r)));
        assertTrue(ex.getErrors().stream().anyMatch(s -> s.contains("meta must be an object if provided")));
    }

    @Test void run_strictTopLevelPipeline_escalatesToException() {
        var r = rule("library.r", "not_empty", "name", "ERROR", "NAME.REQUIRED");
        var p = new LinkedHashMap<String,Object>();
        p.put("id","p"); p.put("type","pipeline"); p.put("description","p");
        p.put("entrypoint",true); p.put("strict",true);
        p.put("message","Top-level strict failed"); p.put("strictCode","TOP.STRICT");
        p.put("flow", List.of(step("rule","library.r")));
        var compiled = engine.compile(List.of(r, p));

        var result = engine.runPipeline(compiled, "p", Map.of("name", ""));
        assertEquals(PipelineResult.Status.EXCEPTION, result.getStatus());
        assertEquals(PipelineResult.Control.STOP, result.getControl());
        assertEquals(2, result.getIssues().size());
        assertEquals("NAME.REQUIRED", result.getIssues().get(0).getCode());
        assertEquals("TOP.STRICT", result.getIssues().get(1).getCode());
        assertEquals("EXCEPTION", result.getIssues().get(1).getLevel());
        assertEquals("pipeline:p", result.getIssues().get(1).getRuleId());
        assertEquals("p", result.getIssues().get(1).getPipelineId());
        assertNull(result.getIssues().get(1).getField());
    }

    @Test void run_strictTopLevelPipeline_withWarningsOnly_doesNotEscalate() {
        var r = rule("library.r", "not_empty", "name", "WARNING", "NAME.SOFT");
        var p = new LinkedHashMap<String,Object>();
        p.put("id","p"); p.put("type","pipeline"); p.put("description","p");
        p.put("entrypoint",true); p.put("strict",true);
        p.put("message","Top-level strict failed"); p.put("strictCode","TOP.STRICT");
        p.put("flow", List.of(step("rule","library.r")));
        var compiled = engine.compile(List.of(r, p));

        var result = engine.runPipeline(compiled, "p", Map.of("name", ""));
        assertEquals(PipelineResult.Status.OK_WITH_WARNINGS, result.getStatus());
        assertEquals(PipelineResult.Control.CONTINUE, result.getControl());
        assertEquals(1, result.getIssues().size());
        assertEquals("NAME.SOFT", result.getIssues().get(0).getCode());
    }

    // ── trace: key events ─────────────────────────────────────────────────────

    @Test void trace_containsPipelineStartEnd() {
        var r = rule("library.r","not_empty","x","ERROR","X");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled,"p",Map.of("x","v"));
        var msgs = result.getTrace().stream().map(TraceEntry::getMessage).toList();
        assertTrue(msgs.contains("pipeline:start"), "trace must contain pipeline:start");
        assertTrue(msgs.contains("pipeline:end"),   "trace must contain pipeline:end");
    }

    @Test void trace_containsConditionWhen() {
        var pred  = predicate("library.pred","equals","flag","value",true);
        var check = rule("library.check","not_empty","x","ERROR","X");
        var cond  = condition("library.cond","library.pred", List.of(step("rule","library.check")));
        var compiled = engine.compile(List.of(pred,check,cond,
            pipeline("p", List.of(step("condition","library.cond")))));
        var result = engine.runPipeline(compiled,"p",Map.of("flag",false));
        assertTrue(result.getTrace().stream().anyMatch(t -> "condition:when".equals(t.getMessage())));
    }

    @Test void trace_containsPredicateEval() {
        var pred  = predicate("library.pred","equals","flag","value",true);
        var check = rule("library.check","not_empty","x","ERROR","X");
        var cond  = condition("library.cond","library.pred", List.of(step("rule","library.check")));
        var compiled = engine.compile(List.of(pred,check,cond,
            pipeline("p", List.of(step("condition","library.cond")))));
        var result = engine.runPipeline(compiled,"p",Map.of("flag",true));
        assertTrue(result.getTrace().stream().anyMatch(t -> "predicate:eval".equals(t.getMessage())));
    }

    @Test void trace_containsNestedPipelineEnterExit() {
        var r = rule("library.r","not_empty","x","ERROR","X");
        var inner = Map.of("id","internal.inner","type","pipeline","description","i",
                            "entrypoint",false,"strict",false,
                            "flow",List.of(step("rule","library.r")));
        var outer = Map.of("id","outer","type","pipeline","description","o",
                            "entrypoint",true,"strict",false,
                            "flow",List.of(step("pipeline","internal.inner")));
        var compiled = engine.compile(List.of(r, inner, outer));
        var result = engine.runPipeline(compiled,"outer",Map.of("x","v"));
        var msgs = result.getTrace().stream().map(TraceEntry::getMessage).toList();
        assertTrue(msgs.contains("pipeline:enter"), "trace must contain pipeline:enter");
        assertTrue(msgs.contains("pipeline:exit"),  "trace must contain pipeline:exit");
    }

    @Test void trace_containsWildcardEvents() {
        var r = rule("library.r","not_empty","items[*].name","ERROR","NAME");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled,"p",
            Map.of("items", List.of(Map.of("name","A"), Map.of("name",""))));
        var msgs = result.getTrace().stream().map(TraceEntry::getMessage).toList();
        assertTrue(msgs.contains("wildcard:expand"),    "trace must contain wildcard:expand");
        assertTrue(msgs.contains("wildcard:aggregate"), "trace must contain wildcard:aggregate");
    }

    @Test void trace_containsRequiredContextMissing() {
        var r = rule("library.r","not_empty","x","ERROR","X");
        var p = new LinkedHashMap<String,Object>();
        p.put("id","p"); p.put("type","pipeline"); p.put("description","p");
        p.put("entrypoint",true); p.put("strict",false);
        p.put("flow", List.of(step("rule","library.r")));
        p.put("required_context", List.of("merchantId"));
        var compiled = engine.compile(List.of(r, p));
        // Run WITHOUT the required context
        var result = engine.runPipeline(compiled,"p",Map.of("x","v"));
        assertEquals(PipelineResult.Status.EXCEPTION, result.getStatus());
        assertTrue(result.getTrace().stream()
            .anyMatch(t -> "pipeline:context:missing".equals(t.getMessage())));
    }

    @Test void trace_containsStrictBoundary() {
        var r = rule("library.r_err","not_empty","required","ERROR","REQ.MISSING");
        var inner = new LinkedHashMap<String,Object>();
        inner.put("id","internal.inner"); inner.put("type","pipeline"); inner.put("description","i");
        inner.put("entrypoint",false); inner.put("strict",true);
        inner.put("message","Inner failed"); inner.put("strictCode","INNER.STRICT");
        inner.put("flow", List.of(step("rule","library.r_err")));
        var outer = Map.of("id","outer","type","pipeline","description","o",
                            "entrypoint",true,"strict",false,
                            "flow",List.of(step("pipeline","internal.inner")));
        var compiled = engine.compile(List.of(r, inner, outer));
        var result = engine.runPipeline(compiled,"outer",Map.of());
        assertTrue(result.getTrace().stream()
            .anyMatch(t -> "pipeline:strict".equals(t.getMessage())));
    }

    @Test void trace_disabled_noTraceEvents() {
        var r = rule("library.r","not_empty","x","ERROR","X");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled,"p",Map.of("x","v"), RunOptions.NO_TRACE);
        assertTrue(result.getTrace().isEmpty(), "trace must be empty with NO_TRACE");
    }

    // ── RunOptions.SAFE: no stack trace on ABORT ──────────────────────────────

    @Test void abort_safeMode_suppressesStackTrace() {
        var operators = OperatorPack.standard().withCheck("boom", (rule2, ctx) -> {
            throw new RuntimeException("intentional boom");
        });
        var badEngine = Engine.create(operators);
        var r = rule("library.r","boom","x","ERROR","X");
        var compiled = badEngine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));

        var result = badEngine.runPipeline(compiled,"p",Map.of("x","v"), RunOptions.SAFE);
        assertEquals(PipelineResult.Status.ABORT, result.getStatus());
        assertNotNull(result.getError(),      "error message must still be present in safe mode");
        assertNull(result.getErrorStack(),    "stack trace must be null in safe mode");
    }

    @Test void abort_defaultMode_includesStackTrace() {
        var operators = OperatorPack.standard().withCheck("boom", (rule2, ctx) -> {
            throw new RuntimeException("intentional boom");
        });
        var badEngine = Engine.create(operators);
        var r = rule("library.r","boom","x","ERROR","X");
        var compiled = badEngine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));

        var result = badEngine.runPipeline(compiled,"p",Map.of("x","v"), RunOptions.DEFAULT);
        assertEquals(PipelineResult.Status.ABORT, result.getStatus());
        assertNotNull(result.getErrorStack(), "stack trace must be included in default mode");
    }

    // ── PayloadFlattener: empty array semantics ───────────────────────────────

    @Test void flattener_emptyArray_storedAsKey() {
        // Empty array stored as prefix → [] so operators can detect presence
        var r = rule("library.r","not_empty","items","ERROR","ITEMS.REQ");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));

        // Empty array: key "items" IS present with value [], which is NOT null/"" → passes not_empty
        // This is intentional per spec: [], false, 0 are not empty
        var result = engine.runPipeline(compiled,"p",Map.of("items",List.of()));
        assertEquals(PipelineResult.Status.OK, result.getStatus(),
            "empty array must not fail not_empty — per spec [] is not considered empty");
    }

    @Test void flattener_nonEmptyArray_expandsToIndexedKeys() {
        // Non-empty array expands to "items[0]", "items[1]" — key "items" is NOT present
        var rCheck = rule("library.r","not_empty","items[0].name","ERROR","ITEM.NAME");
        var compiled = engine.compile(List.of(rCheck, pipeline("p", List.of(step("rule","library.r")))));
        assertEquals(PipelineResult.Status.OK,
            engine.runPipeline(compiled,"p",
                Map.of("items", List.of(Map.of("name","Alice")))).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            engine.runPipeline(compiled,"p",
                Map.of("items", List.of(Map.of("name","")))).getStatus());
    }

    // ── PipelineResult.ERROR: control is STOP ────────────────────────────────

    @Test void pipelineResult_errorStatus_controlIsStop() {
        // ERROR status: pipeline ran to completion but control = STOP
        var r1 = rule("library.r1","not_empty","a","ERROR","A.REQ");
        var r2 = rule("library.r2","not_empty","b","ERROR","B.REQ");
        var compiled = engine.compile(List.of(r1, r2,
            pipeline("p", List.of(step("rule","library.r1"), step("rule","library.r2")))));
        var result = engine.runPipeline(compiled,"p",Map.of("a","","b",""));
        assertEquals(PipelineResult.Status.ERROR,   result.getStatus());
        assertEquals(PipelineResult.Control.STOP,   result.getControl(),
            "ERROR status must yield STOP control for orchestrators");
        assertEquals(2, result.getIssues().size(), "both rules must have been evaluated");
    }

    @Test void pipelineResult_okWithWarnings_controlIsContinue() {
        var r = rule("library.r","not_empty","phone","WARNING","PHONE.SOFT");
        var compiled = engine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));
        var result = engine.runPipeline(compiled,"p",Map.of("phone",""));
        assertEquals(PipelineResult.Status.OK_WITH_WARNINGS, result.getStatus());
        assertEquals(PipelineResult.Control.CONTINUE, result.getControl(),
            "OK_WITH_WARNINGS must yield CONTINUE control");
        assertTrue(result.isOk());
    }

    // ── OperatorContext contract: ctx.get / ctx.has / ctx.getDictionary ───────

    @Test void customOp_usesCtxGet_withoutImportingDeepGet() {
        // Custom operator using only ctx.get() — no import of DeepGet needed
        OperatorPack ops = OperatorPack.standard().withCheck("custom_not_empty", (rule2, ctx) -> {
            var r = ctx.get((String) rule2.get("field"));
            if (!r.ok()) return CheckResult.fail();
            Object v = r.value();
            return (v == null || "".equals(v)) ? CheckResult.fail(v) : CheckResult.ok();
        });
        var customEngine = Engine.create(ops);
        var r = rule("library.r","custom_not_empty","name","ERROR","NAME.REQ");
        var compiled = customEngine.compile(List.of(r, pipeline("p", List.of(step("rule","library.r")))));

        assertEquals(PipelineResult.Status.OK,
            customEngine.runPipeline(compiled,"p",Map.of("name","Alice")).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            customEngine.runPipeline(compiled,"p",Map.of("name","")).getStatus());
        // absent field also fails
        assertEquals(PipelineResult.Status.ERROR,
            customEngine.runPipeline(compiled,"p",Map.of()).getStatus());
    }

    @Test void customOp_usesCtxHas_asGuard() {
        // Custom predicate using ctx.has() as a presence guard
        OperatorPack ops = OperatorPack.standard().withPredicate("field_present", (rule2, ctx) ->
            ctx.has((String) rule2.get("field")) ? PredicateResult.TRUE : PredicateResult.FALSE
        );
        var customEngine = Engine.create(ops);

        var pred  = predicate("library.pred","field_present","tin");
        var check = rule("library.check","not_empty","tin","ERROR","TIN.INVALID");
        var cond  = condition("library.cond","library.pred", List.of(step("rule","library.check")));
        var compiled = customEngine.compile(List.of(pred, check, cond,
            pipeline("p", List.of(step("condition","library.cond")))));

        // tin absent → predicate FALSE → condition skipped → OK
        assertEquals(PipelineResult.Status.OK,
            customEngine.runPipeline(compiled,"p",Map.of()).getStatus());
        // tin present but empty → predicate TRUE → condition runs → ERROR
        assertEquals(PipelineResult.Status.ERROR,
            customEngine.runPipeline(compiled,"p",Map.of("tin","")).getStatus());
        // tin present and non-empty → predicate TRUE → condition runs → OK
        assertEquals(PipelineResult.Status.OK,
            customEngine.runPipeline(compiled,"p",Map.of("tin","7707083893")).getStatus());
    }

    @Test void customOp_usesCtxGetDictionary() {
        // Custom operator using ctx.getDictionary() directly
        OperatorPack ops = OperatorPack.standard().withCheck("in_custom_dict", (rule2, ctx) -> {
            var r = ctx.get((String) rule2.get("field"));
            if (!r.ok()) return CheckResult.fail();
            @SuppressWarnings("unchecked")
            var dict = ctx.getDictionary((String) rule2.get("dictId"));
            if (dict == null) return CheckResult.exception(
                new IllegalStateException("Dict not found: " + rule2.get("dictId")));
            @SuppressWarnings("unchecked")
            var entries = (List<Object>) dict.get("entries");
            boolean found = entries != null && entries.stream()
                .anyMatch(e -> String.valueOf(e).equals(String.valueOf(r.value())));
            return found ? CheckResult.ok() : CheckResult.fail(r.value());
        });
        var customEngine = Engine.create(ops);

        var dict = Map.of("id","countries","type","dictionary","description","d",
                           "entries",List.of("RU","DE","FR"));
        var ruleMap = new LinkedHashMap<>(rule("library.r","in_custom_dict","country","ERROR","COUNTRY.INVALID"));
        ruleMap.put("dictId","countries");
        var compiled = customEngine.compile(List.of(dict, ruleMap,
            pipeline("p", List.of(step("rule","library.r")))));

        assertEquals(PipelineResult.Status.OK,
            customEngine.runPipeline(compiled,"p",Map.of("country","RU")).getStatus());
        assertEquals(PipelineResult.Status.ERROR,
            customEngine.runPipeline(compiled,"p",Map.of("country","US")).getStatus());
    }

    @Test void customOp_usesCtxPayloadKeys_forEnumeration() {
        // Custom operator using ctx.payloadKeys() to enumerate fields
        OperatorPack ops = OperatorPack.standard().withCheck("count_filled_prefix", (rule2, ctx) -> {
            String prefix = (String) rule2.get("prefix");
            int count = (int) ctx.payloadKeys().stream()
                .filter(k -> k.startsWith(prefix))
                .filter(k -> {
                    var r = ctx.get(k);
                    return r.ok() && r.value() != null && !"".equals(r.value());
                })
                .count();
            int min = ((Number) rule2.getOrDefault("min", 1)).intValue();
            return count >= min ? CheckResult.ok() : CheckResult.fail(count);
        });
        var customEngine = Engine.create(ops);
        var ruleMap = new LinkedHashMap<>(rule("library.r","count_filled_prefix","ignored","ERROR","CONTACTS.MIN"));
        ruleMap.put("prefix","contacts");
        ruleMap.put("min",2);
        var compiled = customEngine.compile(List.of(ruleMap, pipeline("p", List.of(step("rule","library.r")))));

        // 3 contacts filled → OK
        assertEquals(PipelineResult.Status.OK, customEngine.runPipeline(compiled,"p",
            Map.of("contacts.email","a@b.com","contacts.phone","+7999","contacts.telegram","@user"))
            .getStatus());
        // only 1 filled → ERROR
        assertEquals(PipelineResult.Status.ERROR, customEngine.runPipeline(compiled,"p",
            Map.of("contacts.email","a@b.com","contacts.phone",""))
            .getStatus());
    }
}
