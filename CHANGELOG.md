# Changelog

All notable changes to this project will be documented in this file.

Format: [Semantic Versioning](https://semver.org/)

## [1.3.0] - 2026-03-30

### Changed
- Relative artifact reference resolution now follows Node semantics more closely during compile-time normalization.
- Condition-scoped rule and predicate refs are resolved against the owning pipeline scope (derived from condition id), matching Node runtime behaviour.
- Pipeline step refs remain absolute-only, matching Node runtime behaviour.

### Fixed
- Fixed a parity gap where Java resolved condition-local refs against the full condition id instead of the pipeline scope, causing real snapshots with short internal refs to fail compilation while Node compiled them successfully.
- Compiled condition steps now store fully resolved artifact ids for relative refs, preserving compile-once/run-many semantics.
- Added regression coverage for relative rule refs in condition steps, relative predicate refs in condition `when`, and pipeline-step absolute ref semantics.
- Extended the parity suite with a `relative-condition-refs` runtime case to guard Node-vs-Java service/runtime equivalence on scoped refs.

## [1.2.0] - 2026-03-30

### Fixed

- Top-level `strict` pipeline semantics aligned with Node runtime parity: strict boundary escalation now works the same way for top-level and nested pipelines.
- Compiler validation aligned with Node compile-time contract for `strictCode`, `any_filled`, field-to-field operators requiring `value_field`, `in_dictionary`, and `meta`.
- Invalid DSL definitions that previously leaked into runtime and produced `ABORT` now fail earlier at compile time where Node already rejected them.

### Changed

- Removed Java-only non-canonical `in_dictionary` shortcut with string dictionary reference; Java now accepts only the canonical Node-compatible dictionary object form.
- Added regression tests covering strict top-level escalation and compile-time parity for the previously divergent DSL cases.
- Cleaned the source archive by removing compiled `out/` artifacts from the release package.
- README wording softened where full parity is still being formalized through a dedicated cross-runtime suite.
- Added a working minimal Node-vs-Java parity suite: `scripts/parity-check.sh`, runtime/compile-fail golden cases in `parity/`, and dedicated Node/Java harnesses for normalized result comparison.

## [1.1.2] - 2026-03-29

### Changed

- README приведён в соответствие с актуальной JS-линией `jsonspecs` `1.1.0`.
- Исправлена таблица npm/Java compatibility: задокументированы актуальные wildcard aggregate modes для check и predicate.
- Явно зафиксировано, что Node-линия остаётся источником истины для DSL и runtime semantics, а Java-линия является Java runtime для JSON DSL.
- README дополнен разделом о стабильном публичном API и политике версионного соответствия Node/Java.

### Added

- Дополнительные parity-oriented tests на trace backbone, aggregate validation, required_context validation и immutable compiled snapshot.

### Fixed

- Исправлена структура `EngineTest.java`, чтобы весь набор регрессионных тестов находился внутри одного test class.

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
Targets Node semantic parity for JSON rule artifacts compiled and calibrated on the npm side. Cross-runtime parity is hardened incrementally and should be verified by the parity suite and release process.

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

- `EngineTest` — compiler, runner, status values, conditions, wildcards, operators, trace toggle, custom operators and later regressions
- `ReadmeSmokeTest` — integration tests that mirror the README quick start and help keep the documentation aligned with the API
