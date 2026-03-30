#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; exit 2; }
}

require_cmd node
require_cmd java
require_cmd python3
require_cmd mvn

if [[ -z "${JSONSPECS_NODE_PATH:-}" ]]; then
  for candidate in "../jsonspecs-main" "../jsonspecs" "../../jsonspecs-main" "../../jsonspecs"; do
    if [[ -f "$candidate/package.json" ]]; then
      export JSONSPECS_NODE_PATH="$(cd "$candidate" && pwd)"
      break
    fi
  done
fi

if [[ -n "${JSONSPECS_NODE_PATH:-}" ]]; then
  echo "Using Node source from: $JSONSPECS_NODE_PATH"
else
  echo "JSONSPECS_NODE_PATH not set; parity suite will bootstrap npm package ${JSONSPECS_NODE_PACKAGE_SPEC:-jsonspecs@1.1.0}" >&2
fi

echo "[1/3] Compiling Java test harness"
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/parity.classpath
JAVA_CP="target/test-classes:target/classes:$(cat target/parity.classpath)"

run_runtime_case() {
  local case_dir="$1"
  local node_out java_out
  node_out="$(mktemp)"
  java_out="$(mktemp)"
  node scripts/parity-node.mjs runtime "$case_dir" > "$node_out"
  java -cp "$JAVA_CP" ru.jsonspecs.parity.ParityHarness runtime "$case_dir" > "$java_out"
  python3 scripts/parity-compare.py runtime "$case_dir" "$node_out" "$java_out"
  rm -f "$node_out" "$java_out"
}

run_compile_fail_case() {
  local case_dir="$1"
  local node_out java_out
  node_out="$(mktemp)"
  java_out="$(mktemp)"
  node scripts/parity-node.mjs compile-fail "$case_dir" > "$node_out"
  java -cp "$JAVA_CP" ru.jsonspecs.parity.ParityHarness compile-fail "$case_dir" > "$java_out"
  python3 scripts/parity-compare.py compile-fail "$case_dir" "$node_out" "$java_out"
  rm -f "$node_out" "$java_out"
}

echo "[2/3] Running compile-fail parity cases"
while IFS= read -r -d '' case_dir; do
  run_compile_fail_case "$case_dir"
done < <(find parity/compile-fail -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

echo "[3/3] Running runtime parity cases"
while IFS= read -r -d '' case_dir; do
  run_runtime_case "$case_dir"
done < <(find parity/runtime -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

echo "Parity suite passed."
