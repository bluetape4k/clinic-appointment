#!/usr/bin/env node

import fs from "node:fs";
import process from "node:process";

const args = process.argv.slice(2);
const input = valueAfter("--input");
const targetAdmissionP99Ms = numberAfter("--target-admission-p99-ms", 250);
const targetLifecycleCoverage = numberAfter("--target-lifecycle-coverage", 1);

if (!input) {
  fail("사용법: node scripts/validate-issue372-redis-key-lifecycle-benchmark.mjs --input <report.json> [--target-admission-p99-ms <ms>] [--target-lifecycle-coverage <fraction>]");
}

let report;
try {
  report = JSON.parse(fs.readFileSync(input, "utf8"));
} catch (error) {
  fail(input + " JSON을 읽을 수 없습니다: " + error.message);
}

required(report, "report");
if (report.schemaVersion !== 1) fail("schemaVersion은 1이어야 합니다: " + report.schemaVersion);
if (
  report.benchmarkFamily !==
  "io.bluetape4k.clinic.appointment.notification.RedisNotificationKeyLifecycleBenchmark"
) {
  fail("benchmarkFamily가 다릅니다: " + report.benchmarkFamily);
}
if (report.redisImage !== "redis:8.8") fail("redisImage는 redis:8.8이어야 합니다: " + report.redisImage);
if (!["smoke", "main"].includes(report.configuration)) fail("configuration이 잘못되었습니다: " + report.configuration);
if (!report.sourceCommit || report.sourceCommit === "unprovided") fail("sourceCommit이 필요합니다");
if (report.deploymentSloEvidence !== false) fail("deploymentSloEvidence는 false여야 합니다");

const workload = required(report.workload, "workload");
positive(workload.operationsPerRound, "workload.operationsPerRound");
positive(workload.longRunRounds, "workload.longRunRounds");
positive(workload.concurrency, "workload.concurrency");
nonNegative(workload.actionMillis, "workload.actionMillis");
nonNegative(workload.retentionWaitMillis, "workload.retentionWaitMillis");
if (!Array.isArray(workload.clinicCardinalities) || workload.clinicCardinalities.length === 0) {
  fail("workload.clinicCardinalities가 비어 있습니다");
}
if (!Array.isArray(workload.churnRates) || workload.churnRates.length === 0) {
  fail("workload.churnRates가 비어 있습니다");
}
if (!Array.isArray(workload.cacheModes) || workload.cacheModes.length === 0) {
  fail("workload.cacheModes가 비어 있습니다");
}

const scenarios = required(report.scenarios, "scenarios");
const expectedScenarioCount =
  workload.clinicCardinalities.length * workload.churnRates.length * workload.cacheModes.length;
if (!Array.isArray(scenarios) || scenarios.length !== expectedScenarioCount) {
  fail(
    "scenario 수가 맞지 않습니다: expected=" +
      expectedScenarioCount +
      " actual=" +
      (scenarios && scenarios.length),
  );
}

const seen = new Set();
for (const [index, scenario] of scenarios.entries()) {
  const prefix = "scenarios[" + index + "]";
  required(scenario.name, prefix + ".name");
  positive(scenario.clinicCardinality, prefix + ".clinicCardinality");
  if (!workload.clinicCardinalities.includes(scenario.clinicCardinality)) {
    fail(prefix + ".clinicCardinality가 workload와 다릅니다");
  }
  if (!Number.isFinite(scenario.churnRate) || scenario.churnRate < 0 || scenario.churnRate > 1) {
    fail(prefix + ".churnRate 범위 오류");
  }
  if (!workload.churnRates.includes(scenario.churnRate)) fail(prefix + ".churnRate가 workload와 다릅니다");
  if (!workload.cacheModes.includes(scenario.cacheMode)) fail(prefix + ".cacheMode가 workload와 다릅니다");
  const key = scenario.cacheMode + "/" + scenario.clinicCardinality + "/" + scenario.churnRate;
  if (seen.has(key)) fail("중복 scenario " + key);
  seen.add(key);
  positive(scenario.operationsPerRound, prefix + ".operationsPerRound");
  if (scenario.longRunRounds !== workload.longRunRounds) fail(prefix + ".longRunRounds 불일치");
  const expectedOperations = scenario.operationsPerRound * (scenario.longRunRounds + 1);
  if (scenario.successfulOperations + scenario.backpressuredOperations !== expectedOperations) {
    fail(prefix + " operation 합계가 맞지 않습니다");
  }
  nonNegative(scenario.warmupMillis, prefix + ".warmupMillis");
  positive(scenario.workloadElapsedMillis, prefix + ".workloadElapsedMillis");
  percentiles(scenario.admissionLatencyMs, prefix + ".admissionLatencyMs");
  positive(scenario.uniqueClinicIds, prefix + ".uniqueClinicIds");
  validateSnapshot(scenario.lifecycle && scenario.lifecycle.workloadEnd, prefix + ".lifecycle.workloadEnd", "workload-end");
  const longRun = scenario.lifecycle && scenario.lifecycle.longRun;
  if (!Array.isArray(longRun) || longRun.length !== workload.longRunRounds) {
    fail(prefix + ".lifecycle.longRun 길이가 잘못되었습니다");
  }
  longRun.forEach((snapshot, round) =>
    validateSnapshot(snapshot, prefix + ".lifecycle.longRun[" + round + "]", "long-run-round-" + (round + 1)),
  );
  validateSnapshot(
    scenario.lifecycle && scenario.lifecycle.afterCoordinatorClose,
    prefix + ".lifecycle.afterCoordinatorClose",
    "after-coordinator-close",
  );
  validateSnapshot(
    scenario.lifecycle && scenario.lifecycle.afterRetentionWindow,
    prefix + ".lifecycle.afterRetentionWindow",
    "after-retention-window",
  );
  nonNegative(
    scenario.lifecycle && scenario.lifecycle.retentionWaitMillis,
    prefix + ".lifecycle.retentionWaitMillis",
  );
  if (scenario.lifecycle.retentionWaitMillis !== workload.retentionWaitMillis) {
    fail(prefix + ".lifecycle.retentionWaitMillis 불일치");
  }
}

const summary = required(report.summary, "summary");
positive(summary.elapsedMillis, "summary.elapsedMillis");
percentiles(summary.admissionLatencyMs, "summary.admissionLatencyMs");
positive(summary.successfulOperations, "summary.successfulOperations");
nonNegative(summary.backpressuredOperations, "summary.backpressuredOperations");
if (summary.lifecycleObservationCoverage < targetLifecycleCoverage) {
  fail(
    "lifecycleObservationCoverage=" +
      summary.lifecycleObservationCoverage +
      " < target=" +
      targetLifecycleCoverage,
  );
}
if (!Array.isArray(summary.requiredLifecycleStages) || summary.requiredLifecycleStages.length !== 4) {
  fail("summary.requiredLifecycleStages는 4개여야 합니다");
}
const retentionMax = Math.max(
  ...scenarios.map((scenario) => scenario.lifecycle.afterRetentionWindow.keyCount),
);
if (summary.persistentKeyCountAfterRetentionMax !== retentionMax) {
  fail("summary.persistentKeyCountAfterRetentionMax가 scenario와 다릅니다");
}

const recovery = required(report.leaseRecovery, "leaseRecovery");
if (recovery.status !== "reacquired") fail("leaseRecovery.status는 reacquired여야 합니다: " + recovery.status);
positive(recovery.leaseMillis, "leaseRecovery.leaseMillis");
positive(recovery.reacquireLatencyMs, "leaseRecovery.reacquireLatencyMs");

const targetStatus = summary.admissionLatencyMs.p99 <= targetAdmissionP99Ms ? "within-target" : "target-breached";
console.log(
  JSON.stringify(
    {
      input,
      configuration: report.configuration,
      scenarioCount: scenarios.length,
      lifecycleObservationCoverage: summary.lifecycleObservationCoverage,
      admissionP99Ms: summary.admissionLatencyMs.p99,
      targetAdmissionP99Ms,
      targetStatus,
      persistentKeyCountAfterRetentionMax: summary.persistentKeyCountAfterRetentionMax,
      leaseRecovery: recovery.status,
      deploymentSloEvidence: report.deploymentSloEvidence,
    },
    null,
    2,
  ),
);

if (targetStatus === "target-breached") {
  fail(
    "summary.admissionLatencyMs.p99=" +
      summary.admissionLatencyMs.p99 +
      "ms > target=" +
      targetAdmissionP99Ms +
      "ms",
  );
}

function validateSnapshot(snapshot, path, expectedStage) {
  required(snapshot, path);
  if (snapshot.stage !== expectedStage) fail(path + ".stage 오류: " + snapshot.stage);
  positive(snapshot.keyCount, path + ".keyCount");
  const ttlBuckets = required(snapshot.ttlBuckets, path + ".ttlBuckets");
  const keyKinds = required(snapshot.keyKinds, path + ".keyKinds");
  for (const [name, value] of Object.entries(ttlBuckets)) nonNegative(value, path + ".ttlBuckets." + name);
  for (const [name, value] of Object.entries(keyKinds)) nonNegative(value, path + ".keyKinds." + name);
  if (Object.values(ttlBuckets).reduce((sum, value) => sum + value, 0) !== snapshot.keyCount) {
    fail(path + ".ttlBuckets 합계가 keyCount와 다릅니다");
  }
  if (Object.values(keyKinds).reduce((sum, value) => sum + value, 0) !== snapshot.keyCount) {
    fail(path + ".keyKinds 합계가 keyCount와 다릅니다");
  }
}

function valueAfter(flag) {
  const index = args.indexOf(flag);
  return index >= 0 ? args[index + 1] : undefined;
}

function numberAfter(flag, fallback) {
  const value = valueAfter(flag);
  const parsed = value === undefined ? fallback : Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) fail("--" + flag.slice(2) + "는 0 이상 숫자여야 합니다");
  return parsed;
}

function required(value, path) {
  if (value === undefined || value === null) fail(path + " 누락");
  return value;
}

function positive(value, path) {
  if (!Number.isFinite(value) || value <= 0) fail(path + "는 양수여야 합니다: " + value);
}

function nonNegative(value, path) {
  if (!Number.isFinite(value) || value < 0) fail(path + "는 0 이상이어야 합니다: " + value);
}

function percentiles(value, path) {
  required(value, path);
  positive(value.sampleCount, path + ".sampleCount");
  for (const key of ["p50", "p95", "p99"]) positive(value[key], path + "." + key);
  if (!(value.p50 <= value.p95 && value.p95 <= value.p99)) fail(path + " percentile 순서가 잘못되었습니다");
}

function fail(message) {
  console.error("검증 실패: " + message);
  process.exit(1);
}
