# Changelog

All notable changes to this project will be documented in this file.

Format: [Semantic Versioning](https://semver.org/)

## [1.1.0] - 2026-03-28

### Added

- Added new stable public type `CompiledRules` in `ru.jsonspecs`.
- `Engine.compile(...)` now returns `CompiledRules`.
- `Engine.runPipeline(...)` now accepts `CompiledRules`.
- `CompiledRules` is an immutable, thread-safe public handle for compiled rule artifacts.

### Changed

- The compile result is now part of the stable public API instead of being exposed only through internal compiler types.
- This makes it possible to build services and long-lived application components on top of `jsonspecs-java` without depending on `ru.jsonspecs.compiler.*`.
- README, smoke examples, and tests were updated to use `CompiledRules`.

### Notes

- `CompiledRules` is an opaque bridge over the internal compiler model. Internal compile structures remain non-public and are still considered implementation details.
- This is an additive API improvement intended to make the library cleaner for external service usage.

## [1.0.0] - 2026-03-28

Initial release. Java runtime for the [jsonspecs](https://www.npmjs.com/package/jsonspecs) rule engine.
Implements the full SPEC semantics — JSON rule artifacts compiled and calibrated on the npm side
run unchanged on the Java side.

### Engine

- `Engine.create(OperatorPack)` — creates an engine instance
- `engine.compile(artifacts)` / `engine.compile(artifacts, CompileOptions)` — compiles artifact list;
  throws `CompilationException` with full error list if invalid
- `engine.runPipeline(compiled, pipelineId, payload)` /
  `engine.runPipeline(..., RunOptions)` — runs a pipeline; never throws

### Compiler (7 phases)

1. Registry — deduplication, artifact map, dictionary collection
2. Schema validation — per-artifact structural checks
3. Code uniqueness — duplicate error codes across check rules
4. Reference validation — all cross-artifact refs resolved at compile time
5. Condition build — compile-time normalization of condition artifacts
6. Pipeline build — compile-time normalization of pipeline artifacts
7. DAG validation — cycle detection in pipeline call graph

### Runtime

- Accumulates all issues in a single pass (does not stop on ERROR)
- EXCEPTION level stops the pipeline immediately
- Strict pipeline groups escalate to EXCEPTION if any check fails
- `OK_WITH_WARNINGS` status for passes with WARNING-level issues
- Full execution trace included in result; disable with `RunOptions.NO_TRACE`
- Accepts both flat (`{"a.b": 1}`) and nested (`{a: {b: 1}}`) payloads
- Wildcard field patterns (`items[*].qty`) with `EACH`, `ALL`, `ANY` aggregate modes
- `$context.*` field references for runtime context injection via `__context` key
- `ABORT` status for unexpected engine faults — `runPipeline` never throws

### Public API

Stable and covered by semver: `Engine`, `CompilationException`, `Issue`, `PipelineResult`,
`TraceEntry`, `CompileOptions`, `RunOptions`, `OperatorPack`, `CheckOperator`,
`PredicateOperator`, `CheckResult`, `PredicateResult`, `OperatorContext`, `DeepGet`.

### Built-in operators

**Check:** `not_empty`, `is_empty`, `equals`, `not_equals`, `contains`, `matches_regex`,
`greater_than`, `less_than`, `length_equals`, `length_max`, `in_dictionary`, `any_filled`,
`field_equals_field`, `field_not_equals_field`, `field_greater_than_field`,
`field_less_than_field`, `field_greater_or_equal_than_field`, `field_less_or_equal_than_field`

**Predicate (same names):** 13 predicate variants for use in `condition.when` clauses

### Build

- Java 21+ required (`maven.compiler.release = 21`)
- `maven-enforcer-plugin` — fails if Java < 21 or Maven < 3.8
- `Automatic-Module-Name: ru.jsonspecs` in JAR manifest for JPMS
- Sources JAR and Javadoc JAR included in release artifacts

### npm/Java compatibility

The same JSON artifact files work unchanged on both runtimes.
Compatible: all operators, issue shape, status values, wildcard semantics,
`$context.*` references, aggregate modes, `matches_regex` flags.
Not portable: compiled `Compiled` objects (compile separately per runtime).

### Tests

- `EngineTest` — 21 tests covering compiler, runner, all status values,
  conditions, wildcards, operators, trace toggle, custom operators
- `ReadmeSmokeTest` — 2 integration tests that mirror the README quick start
  exactly, ensuring documentation does not drift from the API
