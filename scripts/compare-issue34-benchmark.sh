#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <baseline.json> <candidate.json>" >&2
  exit 2
fi

node --input-type=module - "$@" <<'NODE'
import { readFile } from "node:fs/promises";

const [baselinePath, candidatePath] = process.argv.slice(2);
const baseline = await readReport(baselinePath, "baseline");
const candidate = await readReport(candidatePath, "candidate");
validateEnvironment(baseline, candidate);

const baselineMetrics = summarize(baseline);
const candidateMetrics = summarize(candidate);
const failures = [];

checkRelative("p95", baselineMetrics.cancelP95Millis, candidateMetrics.cancelP95Millis, 0.10, failures);
checkRelative("p99", baselineMetrics.cancelP99Millis, candidateMetrics.cancelP99Millis, 0.15, failures);
checkAbsolute("p95", candidateMetrics.cancelP95Millis, 500, "ms", failures);
checkAbsolute("p99", candidateMetrics.cancelP99Millis, 1000, "ms", failures);
checkAbsolute("unexpected error rate", candidateMetrics.unexpectedErrorRate, 0.01, "", failures);
checkAbsolute(
  "unintended retry exhaustion rate",
  candidateMetrics.unintendedRetryExhaustionRate,
  0.001,
  "",
  failures,
);
checkAbsolute("lock-wait p95", candidateMetrics.lockWaitP95Millis, 50, "ms", failures);
checkAbsolute("scenario mismatch rate", candidateMetrics.scenarioMismatchRate, 0, "", failures);

if (failures.length > 0) {
  console.error("FAIL issue-34 benchmark gate");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("PASS issue-34 benchmark gate");
console.log(`- p95: ${format(candidateMetrics.cancelP95Millis)} ms (baseline ${format(baselineMetrics.cancelP95Millis)} ms)`);
console.log(`- p99: ${format(candidateMetrics.cancelP99Millis)} ms (baseline ${format(baselineMetrics.cancelP99Millis)} ms)`);
console.log(`- unexpected error rate: ${format(candidateMetrics.unexpectedErrorRate)}`);
console.log(`- unintended retry exhaustion rate: ${format(candidateMetrics.unintendedRetryExhaustionRate)}`);
console.log(`- lock-wait p95: ${format(candidateMetrics.lockWaitP95Millis)} ms`);
console.log(`- expected conflict observed rate: ${format(candidateMetrics.expectedConflictRate)}`);
console.log(`- expected retry exhaustion observed rate: ${format(candidateMetrics.expectedRetryExhaustionRate)}`);

async function readReport(file, label) {
  let parsed;
  try {
    parsed = JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    throw new Error(`${label} report cannot be read: ${error.message}`);
  }
  if (
    parsed?.schemaVersion !== 1 ||
    parsed?.benchmark !== "issue-34-patient-appointment-cancel" ||
    parsed?.mode !== label
  ) {
    throw new Error(`${label} report has an unsupported schema or benchmark name`);
  }
  if (!Array.isArray(parsed.runs) || parsed.runs.length !== 3) {
    throw new Error(`${label} report must contain exactly three runs`);
  }
  const runNumbers = parsed.runs.map((run) => run.run).sort((left, right) => left - right);
  if (runNumbers.join(",") !== "1,2,3") {
    throw new Error(`${label} report runs must be numbered 1, 2, and 3 exactly once`);
  }
  if (!parsed.environment || typeof parsed.environment !== "object") {
    throw new Error(`${label} report must include an environment object`);
  }
  validateExpectedEnvironment(parsed.environment, label);
  for (const run of parsed.runs) validateRunEvidence(run, parsed.environment.sourceCommit, `${label} run ${run.run}`);
  return parsed;
}

function validateExpectedEnvironment(environment, label) {
  const expected = {
    datasetAppointments: 100,
    warmupSeconds: 30,
    measureSeconds: 300,
    sameAppointmentConcurrency: 10,
    differentAppointmentConcurrency: 20,
  };
  for (const [key, value] of Object.entries(expected)) {
    if (environment[key] !== value) throw new Error(`${label} environment ${key} must be ${value}`);
  }
}

function validateRunEvidence(run, sourceCommit, label) {
  if (run.sourceCommit !== sourceCommit) throw new Error(`${label} run sourceCommit must match its report environment`);
  const warmupRequests = integer(run.warmupRequests, `${label} warmupRequests`);
  const requests = integer(run.requests, `${label} requests`);
  if (warmupRequests <= 0) throw new Error(`${label} warmupRequests must be positive`);
  if (requests <= 0) throw new Error(`${label} requests must be positive`);
  const queries = integer(run.lockWaitSampleQueries, `${label} lockWaitSampleQueries`);
  const failures = integer(run.lockWaitSampleFailures, `${label} lockWaitSampleFailures`);
  if (queries <= 0) throw new Error(`${label} lock-wait sampling must execute at least one successful query`);
  if (failures !== 0) throw new Error(`${label} lock-wait sampling failures must be zero`);
}

function validateEnvironment(baseline, candidate) {
  const keys = [
    "datasetAppointments",
    "warmupSeconds",
    "measureSeconds",
    "sameAppointmentConcurrency",
    "differentAppointmentConcurrency",
    "seed",
    "postgresqlImage",
    "jdk",
    "vm",
  ];
  for (const key of keys) {
    if (!(key in baseline.environment) || !(key in candidate.environment)) {
      throw new Error(`environment key ${key} is required in both reports`);
    }
    if (baseline.environment[key] !== candidate.environment[key]) {
      throw new Error(`environment key ${key} differs between baseline and candidate`);
    }
  }
  validateSourceCommit(baseline.environment.sourceCommit, "baseline");
  validateSourceCommit(candidate.environment.sourceCommit, "candidate");
  if (baseline.environment.sourceCommit === candidate.environment.sourceCommit) {
    throw new Error("baseline and candidate sourceCommit must differ");
  }
}

function validateSourceCommit(value, label) {
  if (typeof value !== "string" || value.trim() === "" || value === "unknown") {
    throw new Error(`${label} environment sourceCommit must identify the measured source`);
  }
}

function summarize(report) {
  const values = {
    cancelP95Millis: report.runs.map((run) => number(run.cancelP95Millis, "cancelP95Millis")),
    cancelP99Millis: report.runs.map((run) => number(run.cancelP99Millis, "cancelP99Millis")),
    unexpectedErrorRate: report.runs.map((run) => number(run.unexpectedErrorRate, "unexpectedErrorRate")),
    unintendedRetryExhaustionRate: report.runs.map((run) =>
      number(run.unintendedRetryExhaustionRate, "unintendedRetryExhaustionRate"),
    ),
    lockWaitP95Millis: report.runs.map((run) => number(run.lockWaitP95Millis, "lockWaitP95Millis")),
    expectedConflictRate: report.runs.map((run) => number(run.expectedConflictRate, "expectedConflictRate")),
    expectedRetryExhaustionRate: report.runs.map((run) =>
      number(run.expectedRetryExhaustionRate, "expectedRetryExhaustionRate"),
    ),
    scenarioMismatchRate: report.runs.map((run) => number(run.scenarioMismatchRate, "scenarioMismatchRate")),
  };
  return Object.fromEntries(Object.entries(values).map(([key, series]) => [key, median(series)]));
}

function number(value, name) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new Error(`metric ${name} must be a finite non-negative number`);
  }
  return value;
}

function integer(value, name) {
  const parsed = number(value, name);
  if (!Number.isInteger(parsed)) throw new Error(`metric ${name} must be an integer`);
  return parsed;
}

function median(series) {
  const sorted = [...series].sort((left, right) => left - right);
  return sorted[1];
}

function checkRelative(name, baseline, candidate, budget, failures) {
  if (baseline === 0) return;
  const allowed = baseline * (1 + budget);
  if (candidate > allowed) {
    failures.push(`${name} ${format(candidate)} exceeds ${format(allowed)} (${budget * 100}% over baseline)`);
  }
}

function checkAbsolute(name, value, limit, unit, failures) {
  if (value > limit) failures.push(`${name} ${format(value)}${unit ? ` ${unit}` : ""} exceeds ${format(limit)}${unit ? ` ${unit}` : ""}`);
}

function format(value) {
  return Number(value.toFixed(4));
}
NODE
