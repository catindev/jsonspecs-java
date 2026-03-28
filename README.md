# jsonspecs-java

[![CI](https://github.com/catindev/jsonspecs-java/actions/workflows/ci.yml/badge.svg)](https://github.com/catindev/jsonspecs-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.jsonspecs/jsonspecs-java)](https://central.sonatype.com/artifact/io.jsonspecs/jsonspecs-java)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.org/)

Java runtime for [jsonspecs](https://www.npmjs.com/package/jsonspecs) — composable validation pipelines powered by JSON rules.

Rules are JSON files. The engine compiles them, runs them against any payload,
and returns structured results with `ERROR`, `WARNING`, and `EXCEPTION` levels,
a full issue list, and an execution trace. **Zero production dependencies.**

```xml
<dependency>
  <groupId>io.jsonspecs</groupId>
  <artifactId>jsonspecs-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## How it works

Rules are individual JSON files (same format as the npm package). A pipeline composes them
into a scenario. The engine compiles them once and runs against any payload.

The same JSON artifact files work on both Node.js (via `jsonspecs` npm) and Java (via this library).
This means you can calibrate rules on the Node.js side and run them in production on Java
without changing a single JSON file.

---

## npm/Java compatibility

The same JSON artifact files (rules, conditions, pipelines, dictionaries) work on both runtimes without modification.

**What is guaranteed to match:**
- All artifact types: `rule`, `condition`, `pipeline`, `dictionary`
- All standard operators (same names and semantics as the npm package)
- Issue shape: `level`, `code`, `message`, `field`, `ruleId`, `expected`, `actual`
- Status values: `OK`, `OK_WITH_WARNINGS`, `ERROR`, `EXCEPTION`, `ABORT`
- Wildcard field patterns (`items[*].qty`) with `EACH`, `ALL`, `ANY` aggregate modes
- `$context.*` field references via `__context` key in payload
- Flat and nested JSON payload both accepted
- `matches_regex` flags (`"flags": "i"`)
- Compile-time validation: invalid regex, duplicate codes, unresolved refs, DAG cycles

**Implementation notes:**
- `Compiled` objects are not portable between runtimes (compile separately on each side)
- Trace format details may differ between runtimes (treat as diagnostic, not as contract)
- Custom operators must be implemented independently on each runtime

---

## Quick start

### Step 1 — define rules (same JSON format as the npm package)

```java
// Rules are plain Map<String, Object> — load from JSON files however you prefer.
// Jackson, Gson, org.json — all work. The engine is loader-agnostic.

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
```

### Step 2 — compose into a pipeline

```java
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
```

### Step 3 — compile and run

```java
import io.jsonspecs.Engine;
import io.jsonspecs.PipelineResult;
import io.jsonspecs.CompilationException;
import io.jsonspecs.operators.OperatorPack;
import io.jsonspecs.compiler.Compiled;

Engine engine = Engine.create(OperatorPack.standard());

// Compile once — reuse the Compiled object for every request
List<Map<String, Object>> artifacts = List.of(nameRequired, emailFormat, docNotExpired, pipeline);

Compiled compiled;
try {
    compiled = engine.compile(artifacts);
} catch (CompilationException e) {
    // Full list of all errors found — not just the first
    e.getErrors().forEach(System.err::println);
    throw e;
}

// Run against a payload — accepts nested JSON or flat dot-notation map
Map<String, Object> payload = Map.of(
    "person", Map.of(
        "firstName", "",
        "email",     "not-an-email",
        "document",  Map.of("expireDate", "2099-01-01")
    ),
    "__context", Map.of("currentDate", "2024-01-01")
);

PipelineResult result = engine.runPipeline(compiled, "registration.pipeline", payload);

System.out.println("status:  " + result.getStatus());   // ERROR
System.out.println("control: " + result.getControl());  // STOP

result.getIssues().forEach(issue ->
    System.out.printf("[%s] %s → %s%n", issue.getLevel(), issue.getCode(), issue.getMessage())
);
// [ERROR]   PERSON.FIRST_NAME.REQUIRED → First name is required
// [WARNING] PERSON.EMAIL.FORMAT        → Email address looks invalid
```

---

## API reference

### `Engine`

```java
Engine engine = Engine.create(OperatorPack.standard());
```

#### `compile`

```java
Compiled compiled = engine.compile(artifacts);
Compiled compiled = engine.compile(artifacts, CompileOptions.withSources(sourceMap));
```

Throws `CompilationException` if any artifact is invalid. The exception contains
the full list of all errors found across all phases — not just the first.

Compile once, run many times. `Compiled` is thread-safe and immutable.

#### `runPipeline`

```java
PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload);

// Suppress trace collection on hot paths:
PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload, RunOptions.NO_TRACE);
```

Never throws — unexpected engine errors are returned as `PipelineResult` with `status = "ABORT"`.

Accepts both nested JSON (`{person: {name: "Ivan"}}`) and flat maps (`{"person.name": "Ivan"}`).
Pass runtime context fields under `__context` key. Access them in rules via `$context.fieldName`.

---

### Result shape

```java
result.getStatus()   // "OK" | "OK_WITH_WARNINGS" | "ERROR" | "EXCEPTION" | "ABORT"
result.getControl()  // PipelineResult.Control enum: CONTINUE, STOP
result.getIssues()   // List<Issue>
result.getTrace()    // List<TraceEntry>  (empty when RunOptions.NO_TRACE)
result.getError()    // non-null only when status == "ABORT"
result.isOk()     // true for OK and OK_WITH_WARNINGS
```

**Status values:**

| Status | Meaning |
|---|---|
| `"OK"` | All checks passed |
| `"OK_WITH_WARNINGS"` | Passed, but WARNING-level issues present |
| `"ERROR"` | One or more ERROR-level issues; pipeline ran to completion |
| `"EXCEPTION"` | EXCEPTION-level issue stopped the pipeline early |
| `"ABORT"` | Engine fault (bug in custom operator, etc.). Not a validation result. |

**Issue fields:**

```java
issue.getLevel()      // "ERROR" | "WARNING" | "EXCEPTION"
issue.getCode()       // "PERSON.FIRST_NAME.REQUIRED"
issue.getMessage()    // "First name is required"
issue.getField()      // "person.firstName"
issue.getRuleId()     // "library.person.first_name_required"
issue.getActual()     // actual value that caused the failure (may be null)
issue.getExpected()   // expected value from the rule (may be null)
```

---

### Result levels

| Level | Meaning | Pipeline behaviour |
|---|---|---|
| `ERROR` | Validation failure | Accumulated; does **not** stop the pipeline |
| `WARNING` | Soft check, data quality hint | Accumulated; does **not** stop the pipeline |
| `EXCEPTION` | Hard block, cannot proceed | Immediately **stops** the pipeline |

---

### Loading JSON

The engine accepts `List<Map<String, Object>>`. Load your JSON files however you prefer:

**Jackson:**
```java
ObjectMapper mapper = new ObjectMapper();
List<Map<String, Object>> artifacts = Files.walk(rulesDir)
    .filter(p -> p.toString().endsWith(".json"))
    .map(p -> mapper.readValue(p.toFile(), new TypeReference<Map<String, Object>>() {}))
    .toList();
```

**Gson:**
```java
Gson gson = new Gson();
Map<String, Object> artifact = gson.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
```

---

### Custom operators

Extend `OperatorPack.standard()` with your own domain-specific operators:

```java
import io.jsonspecs.operators.*;
import io.jsonspecs.util.DeepGet;

OperatorPack operators = OperatorPack.standard()
    .withCheck("valid_inn", (rule, ctx) -> {
        DeepGet.Result r = ctx.get((String) rule.get("field"));
        if (!r.ok()) return CheckResult.fail();
        String inn = String.valueOf(r.value());
        return InnValidator.isValid(inn) ? CheckResult.ok() : CheckResult.fail(inn);
    })
    .withPredicate("is_pep", (rule, ctx) -> {
        DeepGet.Result r = ctx.get((String) rule.get("field"));
        if (!r.ok()) return PredicateResult.UNDEFINED;
        return Boolean.TRUE.equals(r.value()) ? PredicateResult.TRUE : PredicateResult.FALSE;
    });

Engine engine = Engine.create(operators);
```

Register the operator name in your rule artifact:

```json
{
  "id": "library.fl.inn_valid",
  "type": "rule",
  "role": "check",
  "description": "ИНН должен быть корректным",
  "operator": "valid_inn",
  "field": "beneficiary.fl.inn",
  "level": "WARNING",
  "code": "FL.INN.INVALID",
  "message": "ИНН не прошёл контрольное число"
}
```

---

### Wildcard fields

Match arrays using `[*]` in field paths:

```json
{
  "operator": "not_empty",
  "field": "items[*].name"
}
```

**Aggregate modes** (set in rule artifact):

```json
{
  "field": "beneficiary.tax.foreignResidencies[*].countryCode",
  "operator": "not_equals",
  "value": "US",
  "aggregate": { "mode": "ALL" }
}
```

| Mode | Meaning |
|---|---|
| `EACH` (default) | Check every element; collect all failures |
| `ALL` | Pass only if all elements match |
| `ANY` | Pass if at least one element matches |

---

### Conditional blocks

```json
{
  "id": "library.fl.cond_foreign_block",
  "type": "condition",
  "description": "If foreign trigger — check tax block",
  "when": {
    "any": [
      "library.fl.pred_address_is_foreign",
      "library.fl.pred_doc_is_foreign"
    ]
  },
  "steps": [
    { "rule": "library.fl.foreign_tax_country_required" },
    { "condition": "library.fl.cond_us_block" }
  ]
}
```

`when` supports `"all"`, `"any"`, and nested combinations.

---

## Public API (stable)

The following classes are **stable** and covered by semantic versioning.
Breaking changes to any of them require a major version bump.

### Stable

| Class | Package | Description |
|---|---|---|
| `Engine` | `io.jsonspecs` | Main entry point: compile and run |
| `CompilationException` | `io.jsonspecs` | Thrown by `compile()` with full error list |
| `PipelineResult` | `io.jsonspecs` | Result of `runPipeline()` |
| `Issue` | `io.jsonspecs` | A single validation issue |
| `TraceEntry` | `io.jsonspecs` | A single trace entry |
| `CompileOptions` | `io.jsonspecs` | Options for `compile()` |
| `RunOptions` | `io.jsonspecs` | Options for `runPipeline()` |
| `OperatorPack` | `io.jsonspecs.operators` | Operator collection; extend with custom operators |
| `CheckOperator` | `io.jsonspecs.operators` | Interface for custom check operators |
| `PredicateOperator` | `io.jsonspecs.operators` | Interface for custom predicate operators |
| `CheckResult` | `io.jsonspecs.operators` | Result type returned by check operators |
| `PredicateResult` | `io.jsonspecs.operators` | Result type returned by predicate operators |
| `OperatorContext` | `io.jsonspecs.operators` | Context passed to operators at runtime |
| `DeepGet` | `io.jsonspecs.util` | Field lookup helper for custom operators |

### Internal (do not use directly)

The following are implementation details and **may change without notice**:

| Package | Contains |
|---|---|
| `io.jsonspecs.compiler.*` | Compiler phases, compiled artifact types, `WhenExpr`, `Compiled` |
| `io.jsonspecs.Runner` | Runtime execution (package-private; accessed only via `Engine`) |
| `io.jsonspecs.operators.StandardOperators` | Built-in operator implementations |
| `io.jsonspecs.util.*` | Internal utilities (`PayloadFlattener`, `WildcardExpander`, `ValueComparator`) |

If you find yourself needing to import anything from these packages, open an issue — it likely indicates a missing public API.

---

## Built-in operators

| Operator | Description |
|---|---|
| `not_empty` | Field must not be null/empty string |
| `is_empty` | Field must be null or empty |
| `equals` | Field equals `value` |
| `not_equals` | Field does not equal `value` |
| `contains` | String field contains `value` |
| `matches_regex` | Field matches regex `value`; optional `flags` (`"i"`, `"m"`, `"s"`) |
| `greater_than` | Field > `value` (numeric or ISO date) |
| `less_than` | Field < `value` (numeric or ISO date) |
| `length_equals` | String length equals `value` |
| `length_max` | String length ≤ `value` |
| `in_dictionary` | Field value is in dictionary `values` array |
| `any_filled` | At least one of `fields[]` is non-empty |
| `field_equals_field` | Two fields are equal |
| `field_not_equals_field` | Two fields differ |
| `field_greater_than_field` | `field` > `value_field` |
| `field_less_than_field` | `field` < `value_field` |
| `field_greater_or_equal_than_field` | `field` ≥ `value_field` |
| `field_less_or_equal_than_field` | `field` ≤ `value_field` |

All operators are also available as predicates (same name, predicate semantics, for use in `condition.when`).

---

## Requirements

- Java 21+
- Zero production dependencies

## License

MIT
