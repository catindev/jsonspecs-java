# JSONSpecs (Java)

[![CI](https://github.com/catindev/jsonspecs-java/actions/workflows/ci.yml/badge.svg)](https://github.com/catindev/jsonspecs-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ru.jsonspecs/jsonspecs)](https://central.sonatype.com/artifact/ru.jsonspecs/jsonspecs)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.org/)

Java runtime for [jsonspecs](https://www.npmjs.com/package/jsonspecs): composable validation pipelines powered by JSON rules.

Rules are JSON files. The engine compiles them, runs them against any payload, and returns structured results with `ERROR`, `WARNING`, and `EXCEPTION` levels, a full issue list, and an execution trace.

**Zero production dependencies.**

```xml
<dependency>
  <groupId>ru.jsonspecs</groupId>
  <artifactId>jsonspecs</artifactId>
  <version>1.1.0</version>
</dependency>
```

## What this library gives you

- Rules as plain JSON artifacts
- Compile once, run many times
- Structured results instead of bare `true` / `false`
- `ERROR`, `WARNING`, `EXCEPTION`, and `ABORT` result semantics
- Conditional blocks and reusable pipelines
- Wildcard array paths with `EACH`, `ALL`, and `ANY`
- Runtime context via `__context`
- Same JSON artifact format as the Node.js package
- Zero production dependencies

## How it works

Rules are individual JSON files. Conditions activate blocks of checks. Pipelines compose rules and conditions into a business scenario.

The engine compiles artifacts once and then runs pipelines against any payload.

The same JSON artifact files work on both Node.js (via `jsonspecs` npm) and Java (via this library). That means you can calibrate rules on the Node.js side and run them in production on Java without changing the JSON files themselves.

## npm / Java compatibility

The same JSON artifact files (`rule`, `condition`, `pipeline`, `dictionary`) work on both runtimes without modification.

### What is guaranteed to match

- All artifact types: `rule`, `condition`, `pipeline`, `dictionary`
- All standard operator names and semantics
- Issue shape: `level`, `code`, `message`, `field`, `ruleId`, `expected`, `actual`
- Status values: `OK`, `OK_WITH_WARNINGS`, `ERROR`, `EXCEPTION`, `ABORT`
- Wildcard field patterns like `items[*].qty`
- Aggregate modes: `EACH`, `ALL`, `ANY`
- `$context.*` field references via `__context` in payload
- Flat and nested payloads
- `matches_regex` flags like `"i"`, `"m"`, `"s"`
- Compile-time validation checks such as invalid regex, duplicate codes, unresolved refs, and DAG cycles

### What is not portable

- `CompiledRules` objects are runtime-specific and must be built separately on each side
- Trace entry details may differ between runtimes and should be treated as diagnostics, not as a strict contract
- Custom operators must be implemented independently on each runtime

The same JSON artifact files work on both runtimes without modification. Compiled results do not: each runtime builds its own `CompiledRules` instance from the same JSON artifacts.

## Quick start

### Step 1: define rules

Rules are plain `Map<String, Object>` values. Load them from JSON however you prefer: Jackson, Gson, org.json, your own loader, or even inline maps for tests.

```java
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

### Step 2: compose a pipeline

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

### Step 3: compile once and run

```java
import ru.jsonspecs.Engine;
import ru.jsonspecs.PipelineResult;
import ru.jsonspecs.operators.OperatorPack;

Engine engine = Engine.create(OperatorPack.standard());

List<Map<String, Object>> artifacts = List.of(
    nameRequired,
    emailFormat,
    docNotExpired,
    pipeline
);

var compiled = engine.compile(artifacts);

Map<String, Object> payload = Map.of(
    "person", Map.of(
        "firstName", "",
        "email",     "not-an-email",
        "document",  Map.of("expireDate", "2099-01-01")
    ),
    "__context", Map.of("currentDate", "2024-01-01")
);

PipelineResult result = engine.runPipeline(compiled, "registration.pipeline", payload);

System.out.println("status:  " + result.getStatus());
System.out.println("control: " + result.getControl());

result.getIssues().forEach(issue ->
    System.out.printf("[%s] %s → %s%n", issue.getLevel(), issue.getCode(), issue.getMessage())
);
```

Expected output:

```text
status:  ERROR
control: STOP
[ERROR] PERSON.FIRST_NAME.REQUIRED → First name is required
[WARNING] PERSON.EMAIL.FORMAT → Email address looks invalid
```

## API reference

### Engine

```java
Engine engine = Engine.create(OperatorPack.standard());
```

#### compile

```java
CompiledRules compiled = engine.compile(artifacts);
CompiledRules compiled = engine.compile(artifacts, CompileOptions.withSources(sourceMap));
```

Throws `CompilationException` if any artifact is invalid.
The exception contains the full list of all errors found across all compile phases, not just the first failure.

Compile once, run many times. `CompiledRules` is immutable and safe to reuse across requests.

#### runPipeline

```java
PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload);
PipelineResult result = engine.runPipeline(compiled, "my.pipeline", payload, RunOptions.NO_TRACE);
```

The method does not throw for unexpected engine faults. If something goes wrong inside runtime execution, the engine returns a `PipelineResult` with `status = ABORT`.

The payload may be:

- nested JSON-like maps
- flat dot-notation maps

Runtime context fields go under `__context` and are referenced in rules via `$context.fieldName`.

## Result model

```java
result.getStatus();
result.getControl();
result.getIssues();
result.getTrace();
result.getError();
result.isOk();
```

### Status values

| Status             | Meaning                                                    |
| ------------------ | ---------------------------------------------------------- |
| `OK`               | All checks passed                                          |
| `OK_WITH_WARNINGS` | Passed, but WARNING-level issues are present               |
| `ERROR`            | One or more ERROR-level issues; pipeline ran to completion |
| `EXCEPTION`        | EXCEPTION-level issue stopped the pipeline early           |
| `ABORT`            | Engine fault, not a validation result                      |

### Levels

| Level       | Meaning                      | Pipeline behavior                       |
| ----------- | ---------------------------- | --------------------------------------- |
| `ERROR`     | Validation failure           | Accumulated; does not stop the pipeline |
| `WARNING`   | Soft check / hint            | Accumulated; does not stop the pipeline |
| `EXCEPTION` | Hard block / cannot continue | Stops the pipeline immediately          |

### Issue fields

```java
issue.getLevel();
issue.getCode();
issue.getMessage();
issue.getField();
issue.getRuleId();
issue.getActual();
issue.getExpected();
```

## Loading JSON

The engine accepts `List<Map<String, Object>>`. It does not depend on a specific JSON library.

### Jackson

```java
ObjectMapper mapper = new ObjectMapper();
List<Map<String, Object>> artifacts = Files.walk(rulesDir)
    .filter(p -> p.toString().endsWith(".json"))
    .map(p -> mapper.readValue(p.toFile(), new TypeReference<Map<String, Object>>() {}))
    .toList();
```

### Gson

```java
Gson gson = new Gson();
Map<String, Object> artifact = gson.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
```

## Custom operators

Use custom operators when the built-in set is not enough for your domain.

A good README example should be tiny, deterministic, and easy to copy. So instead of a domain-heavy validator, here is a small smoke example.

### Small smoke example: `is_apple`

```java
import ru.jsonspecs.Engine;
import ru.jsonspecs.PipelineResult;
import ru.jsonspecs.operators.OperatorPack;
import ru.jsonspecs.operators.CheckResult;
import ru.jsonspecs.operators.PredicateResult;
import ru.jsonspecs.util.DeepGet;

import java.util.List;
import java.util.Map;

OperatorPack operators = OperatorPack.standard()
    .withCheck("is_apple", (rule, ctx) -> {
        DeepGet.Result r = ctx.get((String) rule.get("field"));
        if (!r.ok()) return CheckResult.fail();
        String actual = String.valueOf(r.value());
        return "apple".equalsIgnoreCase(actual)
            ? CheckResult.ok()
            : CheckResult.fail(actual);
    })
    .withPredicate("is_apple", (rule, ctx) -> {
        DeepGet.Result r = ctx.get((String) rule.get("field"));
        if (!r.ok()) return PredicateResult.UNDEFINED;
        String actual = String.valueOf(r.value());
        return "apple".equalsIgnoreCase(actual)
            ? PredicateResult.TRUE
            : PredicateResult.FALSE;
    });

Engine engine = Engine.create(operators);

Map<String, Object> rule = Map.of(
    "id",          "library.fruit.must_be_apple",
    "type",        "rule",
    "role",        "check",
    "description", "Fruit must be apple",
    "operator",    "is_apple",
    "field",       "fruit.name",
    "level",       "ERROR",
    "code",        "FRUIT.NOT_APPLE",
    "message",     "Fruit must be apple"
);

Map<String, Object> pipeline = Map.of(
    "id",          "fruit.pipeline",
    "type",        "pipeline",
    "description", "Fruit validation",
    "entrypoint",  true,
    "strict",      false,
    "flow",        List.of(
        Map.of("rule", "library.fruit.must_be_apple")
    )
);

var compiled = engine.compile(List.of(rule, pipeline));

PipelineResult ok = engine.runPipeline(
    compiled,
    "fruit.pipeline",
    Map.of("fruit", Map.of("name", "apple"))
);

PipelineResult fail = engine.runPipeline(
    compiled,
    "fruit.pipeline",
    Map.of("fruit", Map.of("name", "banana"))
);

System.out.println(ok.getStatus());
System.out.println(fail.getStatus());
System.out.println(fail.getIssues().get(0).getCode());
```

Expected output:

```text
OK
ERROR
FRUIT.NOT_APPLE
```

Register the operator name in the rule artifact like any other operator:

```json
{
  "id": "library.fruit.must_be_apple",
  "type": "rule",
  "role": "check",
  "description": "Fruit must be apple",
  "operator": "is_apple",
  "field": "fruit.name",
  "level": "ERROR",
  "code": "FRUIT.NOT_APPLE",
  "message": "Fruit must be apple"
}
```

Keep custom operators small and deterministic:

- read values from payload
- evaluate
- return `CheckResult` or `PredicateResult`

If your README example needs a domain validator, a database call, or another library, it is already too heavy for a first example.

## Wildcard fields

Match arrays using `[*]` in field paths:

```json
{
  "operator": "not_empty",
  "field": "items[*].name"
}
```

### Aggregate modes

```json
{
  "field": "beneficiary.tax.foreignResidencies[*].countryCode",
  "operator": "not_equals",
  "value": "US",
  "aggregate": { "mode": "ALL" }
}
```

| Mode   | Meaning                                   |
| ------ | ----------------------------------------- |
| `EACH` | Check every element; collect all failures |
| `ALL`  | Pass only if all elements match           |
| `ANY`  | Pass if at least one element matches      |

## Conditional blocks

```json
{
  "id": "library.fl.cond_foreign_block",
  "type": "condition",
  "description": "If foreign trigger, check tax block",
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

`when` supports `all`, `any`, and nested combinations.

## Public API (stable)

The following classes are stable and covered by semantic versioning.
Breaking changes to any of them require a major version bump.

### Stable

| Class                  | Package                  | Description                                       |
| ---------------------- | ------------------------ | ------------------------------------------------- |
| `Engine`               | `ru.jsonspecs`           | Main entry point: compile and run                 |
| `CompiledRules`        | `ru.jsonspecs`           | Public handle for compiled rule artifacts         |
| `CompilationException` | `ru.jsonspecs`           | Thrown by `compile()` with full error list        |
| `PipelineResult`       | `ru.jsonspecs`           | Result of `runPipeline()`                         |
| `Issue`                | `ru.jsonspecs`           | A single validation issue                         |
| `TraceEntry`           | `ru.jsonspecs`           | A single trace entry                              |
| `CompileOptions`       | `ru.jsonspecs`           | Options for `compile()`                           |
| `RunOptions`           | `ru.jsonspecs`           | Options for `runPipeline()`                       |
| `OperatorPack`         | `ru.jsonspecs.operators` | Operator collection; extend with custom operators |
| `CheckOperator`        | `ru.jsonspecs.operators` | Interface for custom check operators              |
| `PredicateOperator`    | `ru.jsonspecs.operators` | Interface for custom predicate operators          |
| `CheckResult`          | `ru.jsonspecs.operators` | Result type returned by check operators           |
| `PredicateResult`      | `ru.jsonspecs.operators` | Result type returned by predicate operators       |
| `OperatorContext`      | `ru.jsonspecs.operators` | Context passed to operators at runtime            |
| `DeepGet`              | `ru.jsonspecs.util`      | Field lookup helper for custom operators          |

### Internal (do not use directly)

The following are implementation details and may change without notice:

| Package / class                            | Contains                                                      |
| ------------------------------------------ | ------------------------------------------------------------- |
| `ru.jsonspecs.compiler.*`                  | Compiler phases and compiled artifact internals               |
| `ru.jsonspecs.Runner`                      | Runtime execution internals                                   |
| `ru.jsonspecs.operators.StandardOperators` | Built-in operator implementations                             |
| `ru.jsonspecs.util.*`                      | Internal utilities such as wildcard expansion and comparisons |

If you find yourself importing these directly, that usually means a missing public API. Open an issue.

## Built-in operators

| Operator                            | Description                                                      |
| ----------------------------------- | ---------------------------------------------------------------- |
| `not_empty`                         | Field must not be null or empty string                           |
| `is_empty`                          | Field must be null or empty                                      |
| `equals`                            | Field equals `value`                                             |
| `not_equals`                        | Field does not equal `value`                                     |
| `contains`                          | String field contains `value`                                    |
| `matches_regex`                     | Field matches regex `value`; optional `flags` like `i`, `m`, `s` |
| `greater_than`                      | Field > `value` (numeric or ISO date)                            |
| `less_than`                         | Field < `value` (numeric or ISO date)                            |
| `length_equals`                     | String length equals `value`                                     |
| `length_max`                        | String length <= `value`                                         |
| `in_dictionary`                     | Field value is in dictionary `values`                            |
| `any_filled`                        | At least one of `fields[]` is non-empty                          |
| `field_equals_field`                | Two fields are equal                                             |
| `field_not_equals_field`            | Two fields differ                                                |
| `field_greater_than_field`          | `field` > `value_field`                                          |
| `field_less_than_field`             | `field` < `value_field`                                          |
| `field_greater_or_equal_than_field` | `field` >= `value_field`                                         |
| `field_less_or_equal_than_field`    | `field` <= `value_field`                                         |

All built-in check operators are also available as predicates for use in `condition.when`.

## Examples and tests

- Quick start examples in this README are covered by `ReadmeSmokeTest`
- Additional tests live in `EngineTest`
- See the Node.js package for the same JSON artifact format on the JavaScript side

## Requirements

- Java 21+
- Zero production dependencies

## License

MIT
