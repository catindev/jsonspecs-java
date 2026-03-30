#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

function usage() {
  console.error('Usage: node scripts/parity-node.mjs <compile-fail|runtime> <case-dir>');
  process.exit(2);
}

const mode = process.argv[2];
const caseDir = process.argv[3];
if (!mode || !caseDir) usage();

function loadEngine() {
  const explicitPath = process.env.JSONSPECS_NODE_PATH;
  const packageSpec = process.env.JSONSPECS_NODE_PACKAGE_SPEC || 'jsonspecs@1.1.0';
  if (explicitPath) {
    const abs = path.resolve(explicitPath);
    const req = createRequire(path.join(abs, 'package.json'));
    return req(abs);
  }

  const localRequire = createRequire(import.meta.url);
  try {
    return localRequire('jsonspecs');
  } catch {
    const bootstrapDir = path.resolve('.parity-node-runtime');
    if (!fs.existsSync(bootstrapDir)) {
      fs.mkdirSync(bootstrapDir, { recursive: true });
      fs.writeFileSync(path.join(bootstrapDir, 'package.json'), JSON.stringify({ name: 'jsonspecs-java-parity', private: true }));
      const { spawnSync } = localRequire('node:child_process');
      const install = spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['install', '--no-fund', '--no-audit', packageSpec], {
        cwd: bootstrapDir,
        stdio: 'inherit'
      });
      if (install.status !== 0) {
        console.error(`Failed to install ${packageSpec} for parity suite`);
        process.exit(2);
      }
    }
    const req = createRequire(path.join(bootstrapDir, 'package.json'));
    return req('jsonspecs');
  }
}

const req = createRequire(import.meta.url);
const { createEngine, Operators } = loadEngine();
const engine = createEngine({ operators: Operators });

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function normIssue(i) {
  return {
    level: i.level ?? null,
    code: i.code ?? null,
    message: i.message ?? null,
    field: i.field ?? null,
  };
}

function sortIssues(issues) {
  return [...issues].map(normIssue).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));
}

function selectPipelineId(artifacts, caseDir) {
  const pipelineIdFile = path.join(caseDir, 'pipeline-id.txt');
  if (fs.existsSync(pipelineIdFile)) return fs.readFileSync(pipelineIdFile, 'utf8').trim();
  const entry = artifacts.find((a) => a.type === 'pipeline' && a.entrypoint === true);
  if (entry) return entry.id;
  const first = artifacts.find((a) => a.type === 'pipeline');
  if (!first) throw new Error('No pipeline artifact found');
  return first.id;
}

function output(obj) {
  process.stdout.write(JSON.stringify(obj, null, 2));
}

try {
  const artifacts = readJson(path.join(caseDir, 'artifacts.json'));
  if (mode === 'compile-fail') {
    try {
      engine.compile(artifacts);
      output({ kind: 'compile-fail', compileOk: true, errors: [] });
    } catch (e) {
      const errors = Array.isArray(e.errors) ? [...e.errors].sort() : [String(e.message ?? e)].sort();
      output({ kind: 'compile-fail', compileOk: false, errors });
    }
  } else if (mode === 'runtime') {
    try {
      const compiled = engine.compile(artifacts);
      const payload = readJson(path.join(caseDir, 'payload.json'));
      const pipelineId = selectPipelineId(artifacts, caseDir);
      const result = engine.runPipeline(compiled, pipelineId, payload);
      output({
        kind: 'runtime',
        compileOk: true,
        pipelineId,
        status: result.status,
        control: result.control,
        issues: sortIssues(result.issues ?? []),
      });
    } catch (e) {
      const errors = Array.isArray(e.errors) ? [...e.errors].sort() : [String(e.message ?? e)].sort();
      output({ kind: 'runtime', compileOk: false, errors });
    }
  } else {
    usage();
  }
} catch (e) {
  output({ kind: mode, fatal: true, message: String(e && e.stack ? e.stack : e) });
  process.exit(1);
}
