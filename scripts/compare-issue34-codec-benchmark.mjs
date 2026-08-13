#!/usr/bin/env node

import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

const [baselineDirectory, candidateDirectory] = process.argv.slice(2);

if (!baselineDirectory || !candidateDirectory) {
  console.error("Usage: compare-issue34-codec-benchmark.mjs <baseline-dir> <candidate-dir>");
  process.exit(2);
}

const MIXES = ["legacy-heavy", "current-heavy"];
const RUNS = [1, 2, 3];
const BENCHMARK = "issue-34-notification-codec-backlog";

const baseline = await readReports(baselineDirectory, "baseline");
const candidate = await readReports(candidateDirectory, "candidate");

for (const mix of MIXES) {
  const baselineRuns = byMix(baseline, mix);
  const candidateRuns = byMix(candidate, mix);
  assertEnvironment(baselineRuns, candidateRuns, mix);
  assertValidMetrics(baselineRuns, "baseline", mix);
  assertValidMetrics(candidateRuns, "candidate", mix);

  const baselineMedian = medianMetrics(baselineRuns);
  const candidateMedian = medianMetrics(candidateRuns);
  const checks = [
    ["decode p95 absolute", candidateMedian.decodeP95Millis <= 500],
    ["decode p99 absolute", candidateMedian.decodeP99Millis <= 1000],
    ["decode p95 regression", candidateMedian.decodeP95Millis <= baselineMedian.decodeP95Millis * 1.1],
    ["decode p99 regression", candidateMedian.decodeP99Millis <= baselineMedian.decodeP99Millis * 1.15],
    ["throughput regression", candidateMedian.throughputRowsPerSecond >= baselineMedian.throughputRowsPerSecond * 0.9],
    ["drain-time regression", candidateMedian.drainTimeMillis <= baselineMedian.drainTimeMillis * 1.1],
    ["decode failures", candidateMedian.decodeFailures === 0],
  ];
  const failed = checks.filter(([, passed]) => !passed).map(([name]) => name);
  if (failed.length > 0) {
    console.error(`FAIL ${mix}: ${failed.join(", ")}`);
    console.error(`baseline=${JSON.stringify(baselineMedian)}`);
    console.error(`candidate=${JSON.stringify(candidateMedian)}`);
    process.exitCode = 1;
    continue;
  }

  console.log(
    `PASS ${mix}: ` +
      `p95 ${format(candidateMedian.decodeP95Millis)}ms, ` +
      `p99 ${format(candidateMedian.decodeP99Millis)}ms, ` +
      `throughput ${format(candidateMedian.throughputRowsPerSecond)}/s, ` +
      `drain ${format(candidateMedian.drainTimeMillis)}ms`,
  );
}

async function readReports(directory, expectedMode) {
  const files = (await readdir(directory, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
    .map((entry) => path.join(directory, entry.name));
  const reports = [];
  for (const file of files) {
    const report = JSON.parse(await readFile(file, "utf8"));
    if (report.benchmark !== BENCHMARK) continue;
    assert.equal(report.schemaVersion, 1, `${file}: schemaVersion must be 1`);
    assert.equal(report.mode, expectedMode, `${file}: mode must be ${expectedMode}`);
    assert.ok(MIXES.includes(report.mix), `${file}: unsupported mix`);
    assert.ok(RUNS.includes(report.run), `${file}: run must be 1, 2, or 3`);
    reports.push({ ...report, sourceFile: file });
  }
  assert.equal(reports.length, 6, `${directory}: expected six codec reports (two mixes x three runs)`);
  for (const mix of MIXES) {
    const runs = reports.filter((report) => report.mix === mix).map((report) => report.run).sort((a, b) => a - b);
    assert.deepEqual(runs, RUNS, `${directory}: ${mix} must contain runs 1, 2, and 3`);
  }
  return reports;
}

function byMix(reports, mix) {
  return reports.filter((report) => report.mix === mix).sort((a, b) => a.run - b.run);
}

function assertEnvironment(baselineRuns, candidateRuns, mix) {
  const baselineEnvironment = baselineRuns[0].environment;
  const candidateEnvironment = candidateRuns[0].environment;
  assertSourceCommits(baselineEnvironment, candidateEnvironment, mix);
  const { sourceCommit: _baselineSourceCommit, ...baselineComparable } = baselineEnvironment;
  const { sourceCommit: _candidateSourceCommit, ...candidateComparable } = candidateEnvironment;
  assert.deepEqual(candidateComparable, baselineComparable, `${mix}: benchmark environments differ`);
  assert.equal(baselineEnvironment.datasetRows, 10_000, `${mix}: datasetRows must be 10000`);
  assert.equal(baselineEnvironment.warmupSeconds, 30, `${mix}: warmupSeconds must be 30`);
  assert.equal(baselineEnvironment.measureSeconds, 300, `${mix}: measureSeconds must be 300`);
  assert.equal(baselineEnvironment.detailLength, 500, `${mix}: detailLength must be 500`);
}

function assertSourceCommits(baselineEnvironment, candidateEnvironment, mix) {
  for (const [label, value] of [
    ["baseline", baselineEnvironment.sourceCommit],
    ["candidate", candidateEnvironment.sourceCommit],
  ]) {
    assert.equal(typeof value, "string", `${mix}: ${label} sourceCommit is required`);
    assert.notEqual(value.trim(), "", `${mix}: ${label} sourceCommit cannot be empty`);
    assert.notEqual(value, "unknown", `${mix}: ${label} sourceCommit cannot be unknown`);
  }
  assert.notEqual(
    baselineEnvironment.sourceCommit,
    candidateEnvironment.sourceCommit,
    `${mix}: baseline and candidate sourceCommit must differ`,
  );
}

function assertValidMetrics(reports, mode, mix) {
  for (const report of reports) {
    const metrics = report.metrics;
    assert.ok(metrics.decodedRows >= report.environment.datasetRows, `${mode}/${mix}/${report.run}: rows not drained`);
    assert.ok(metrics.latencySamples > 0, `${mode}/${mix}/${report.run}: no latency samples`);
    assert.ok(metrics.throughputRowsPerSecond > 0, `${mode}/${mix}/${report.run}: throughput must be positive`);
    assert.ok(metrics.decodeP95Millis >= 0, `${mode}/${mix}/${report.run}: p95 must be non-negative`);
    assert.ok(metrics.decodeP99Millis >= metrics.decodeP95Millis, `${mode}/${mix}/${report.run}: p99 < p95`);
    assert.ok(metrics.drainTimeMillis > 0, `${mode}/${mix}/${report.run}: drain time must be positive`);
    assert.equal(metrics.decodeFailures, 0, `${mode}/${mix}/${report.run}: decode failures are not allowed`);
  }
}

function medianMetrics(reports) {
  const median = (field) => reports.map((report) => report.metrics[field]).sort((a, b) => a - b)[1];
  return {
    decodeP95Millis: median("decodeP95Millis"),
    decodeP99Millis: median("decodeP99Millis"),
    throughputRowsPerSecond: median("throughputRowsPerSecond"),
    drainTimeMillis: median("drainTimeMillis"),
    decodeFailures: median("decodeFailures"),
  };
}

function format(value) {
  return Number(value).toFixed(3);
}
