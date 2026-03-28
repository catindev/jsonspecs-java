package ru.jsonspecs;

import ru.jsonspecs.operators.OperatorPack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test that mirrors the README quick start example exactly.
 *
 * <p>This test exists to ensure the README does not drift from the actual API.
 * If this test fails, the README must be updated to match.
 */
class ReadmeSmokeTest {

    /**
     * README quick start: person registration validation.
     * Artifacts are the same as in the README code blocks.
     */
    @Test
    void quickStart_readme_example_works_as_documented() {

        // ── Step 1: artifacts from README ────────────────────────────────────

        Map<String, Object> nameRequired = Map.of(
            "id",          "library.person.first_name_required",
            "type",        "rule",
            "description", "First name must be filled",
            "role",        "check",
            "operator",    "not_empty",
            "level",       "ERROR",
            "code",        "PERSON.FIRST_NAME.REQUIRED",
            "message",     "First name is required",
            "field",       "person.firstName"
        );

        Map<String, Object> emailFormat = Map.of(
            "id",          "library.person.email_format",
            "type",        "rule",
            "description", "Email must contain @",
            "role",        "check",
            "operator",    "contains",
            "level",       "WARNING",
            "code",        "PERSON.EMAIL.FORMAT",
            "message",     "Email address looks invalid",
            "field",       "person.email",
            "value",       "@"
        );

        Map<String, Object> docNotExpired = Map.of(
            "id",          "library.person.doc_not_expired",
            "type",        "rule",
            "description", "Document must not be expired",
            "role",        "check",
            "operator",    "field_greater_or_equal_than_field",
            "level",       "EXCEPTION",
            "code",        "PERSON.DOC.EXPIRED",
            "message",     "Document has expired",
            "field",       "person.document.expireDate",
            "value_field", "$context.currentDate"
        );

        Map<String, Object> pipeline = Map.of(
            "id",               "registration.pipeline",
            "type",             "pipeline",
            "description",      "Person registration validation",
            "entrypoint",       true,
            "strict",           false,
            "required_context", List.of("currentDate"),
            "flow", List.of(
                Map.of("rule", "library.person.first_name_required"),
                Map.of("rule", "library.person.email_format"),
                Map.of("rule", "library.person.doc_not_expired")
            )
        );

        // ── Step 2: create engine, compile once ───────────────────────────────

        Engine engine = Engine.create(OperatorPack.standard());

        List<Map<String, Object>> artifacts = List.of(nameRequired, emailFormat, docNotExpired, pipeline);

        CompiledRules compiled;
        try {
            compiled = engine.compile(artifacts);
        } catch (CompilationException e) {
            e.getErrors().forEach(System.err::println);
            throw e;
        }

        // ── Step 3: run against failing payload ───────────────────────────────

        Map<String, Object> payload = Map.of(
            "person", Map.of(
                "firstName", "",                   // ← ERROR: missing
                "email",     "not-an-email",        // ← WARNING: no @
                "document",  Map.of("expireDate", "2099-01-01")  // ← OK
            ),
            "__context", Map.of("currentDate", "2024-01-01")
        );

        PipelineResult result = engine.runPipeline(compiled, "registration.pipeline", payload);

        // Assertions mirror README comments exactly
        assertEquals(PipelineResult.Status.ERROR,   result.getStatus(),  "status should be ERROR");
        assertEquals(PipelineResult.Control.STOP,   result.getControl(), "control should be STOP");

        // Both issues should be present (ERROR does not stop pipeline)
        assertTrue(result.getIssues().stream()
            .anyMatch(i -> "PERSON.FIRST_NAME.REQUIRED".equals(i.getCode())
                        && "ERROR".equals(i.getLevel())),
            "Should have PERSON.FIRST_NAME.REQUIRED error");

        assertTrue(result.getIssues().stream()
            .anyMatch(i -> "PERSON.EMAIL.FORMAT".equals(i.getCode())
                        && "WARNING".equals(i.getLevel())),
            "Should have PERSON.EMAIL.FORMAT warning");

        // EXCEPTION rule did not trigger (document is not expired)
        assertTrue(result.getIssues().stream()
            .noneMatch(i -> "PERSON.DOC.EXPIRED".equals(i.getCode())),
            "DOC.EXPIRED should not appear — document is valid");

        // ── Step 4: run against passing payload ───────────────────────────────

        Map<String, Object> goodPayload = Map.of(
            "person", Map.of(
                "firstName", "Ivan",
                "email",     "ivan@example.com",
                "document",  Map.of("expireDate", "2099-01-01")
            ),
            "__context", Map.of("currentDate", "2024-01-01")
        );

        PipelineResult okResult = engine.runPipeline(compiled, "registration.pipeline", goodPayload);
        assertEquals(PipelineResult.Status.OK, okResult.getStatus());
        assertTrue(okResult.isOk());
        assertTrue(okResult.getIssues().isEmpty());

        // ── Step 5: RunOptions.NO_TRACE works ────────────────────────────────

        PipelineResult noTrace = engine.runPipeline(compiled, "registration.pipeline", goodPayload,
                                                     RunOptions.NO_TRACE);
        assertTrue(noTrace.getTrace().isEmpty(), "Trace should be empty with NO_TRACE");

        // ── Step 6: CompilationException shows all errors ─────────────────────

        Map<String, Object> badRule = new java.util.LinkedHashMap<>(nameRequired);
        badRule.put("id", "library.person.first_name_required"); // same id
        badRule.remove("level");
        badRule.remove("code");
        badRule.remove("message");

        CompilationException ex = assertThrows(CompilationException.class, () ->
            engine.compile(List.of(badRule)));
        assertFalse(ex.getErrors().isEmpty(), "Should have compilation errors");
        assertTrue(ex.getMessage().contains("Compilation failed"));
    }

    /**
     * EXCEPTION-level rule stops the pipeline immediately.
     * Mirrors the "Result levels" table in README.
     */
    @Test
    void exception_stops_pipeline_immediately() {
        Map<String, Object> docExpired = Map.of(
            "id",          "library.person.doc_not_expired",
            "type",        "rule",
            "description", "Document must not be expired",
            "role",        "check",
            "operator",    "field_greater_or_equal_than_field",
            "level",       "EXCEPTION",
            "code",        "PERSON.DOC.EXPIRED",
            "message",     "Document has expired",
            "field",       "person.document.expireDate",
            "value_field", "$context.currentDate"
        );

        Map<String, Object> nameRequired = Map.of(
            "id", "library.person.name", "type", "rule", "description", "d",
            "role", "check", "operator", "not_empty", "field", "person.name",
            "level", "ERROR", "code", "PERSON.NAME", "message", "required"
        );

        Map<String, Object> pipeline = Map.of(
            "id", "p", "type", "pipeline", "description", "d",
            "entrypoint", true, "strict", false,
            "required_context", List.of("currentDate"),
            "flow", List.of(
                Map.of("rule", "library.person.doc_not_expired"),
                Map.of("rule", "library.person.name")  // should NOT run
            )
        );

        Engine engine = Engine.create(OperatorPack.standard());
        var compiled = engine.compile(List.of(docExpired, nameRequired, pipeline));

        // Document expired — EXCEPTION should stop immediately, name check should NOT run
        Map<String, Object> payload = Map.of(
            "person", Map.of(
                "document", Map.of("expireDate", "2020-01-01"),
                "name", ""
            ),
            "__context", Map.of("currentDate", "2024-01-01")
        );

        PipelineResult result = engine.runPipeline(compiled, "p", payload);

        assertEquals(PipelineResult.Status.EXCEPTION, result.getStatus());
        assertEquals(PipelineResult.Control.STOP, result.getControl());
        assertTrue(result.getIssues().stream().anyMatch(i -> "PERSON.DOC.EXPIRED".equals(i.getCode())));
        // name check did NOT run because EXCEPTION stopped the pipeline
        assertTrue(result.getIssues().stream().noneMatch(i -> "PERSON.NAME".equals(i.getCode())));
    }
}
