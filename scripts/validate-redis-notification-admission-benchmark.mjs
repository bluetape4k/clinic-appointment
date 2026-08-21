#!/usr/bin/env node

import fs from "node:fs";
import process from "node:process";

const args = process.argv.slice(2);
const inputIndex = args.indexOf("--input");
const input = inputIndex >= 0 ? args[inputIndex + 1] : undefined;
const targetIndex = args.indexOf("--target-p99-ms");
const targetP99Ms = targetIndex >= 0 ? Number(args[targetIndex + 1]) : 250;

if (!input || !Number.isFinite(targetP99Ms)) {
  console.error("사용법: node scripts/validate-redis-notification-admission-benchmark.mjs --input <report.json> [--target-p99-ms <ms>]");
  process.exit(2);
}

const fail = (message) => {
  console.error(`검증 실패: ${message}`);
  process.exitCode = 1;
};

let report;
try {
  report = JSON.parse(fs.readFileSync(input, "utf8"));
} catch (error) {
  fail(`${input} JSON을 읽을 수 없습니다: ${error.message}`);
  process.exit();
}

const required = (value, path) => {
  if (value === undefined || value === null) fail(`${path} 누락`);
  return value;
};
const positive = (value, path) => {
  if (!Number.isFinite(value) || value <= 0) fail(`${path}는 양수여야 합니다: ${value}`);
};
const percentiles = (value, path) => {
  required(value, path);
  positive(value.sampleCount, `${path}.sampleCount`);
  for (const key of ["p50", "p95", "p99"]) positive(value[key], `${path}.${key}`);
  if (!(value.p50 <= value.p95 && value.p95 <= value.p99)) fail(`${path} percentile 순서가 잘못되었습니다`);
};

required(report, "report");
if (report.schemaVersion !== 1) fail(`schemaVersion은 1이어야 합니다: ${report.schemaVersion}`);
if (report.benchmarkFamily !== "io.bluetape4k.clinic.appointment.notification.RedisNotificationAdmissionBenchmark") {
  fail(`benchmarkFamily가 다릅니다: ${report.benchmarkFamily}`);
}
if (report.redisImage !== "redis:8.8") fail(`redisImage는 redis:8.8이어야 합니다: ${report.redisImage}`);
if (!["smoke", "main"].includes(report.configuration)) fail(`configuration이 잘못되었습니다: ${report.configuration}`);
if (report.deploymentSloEvidence !== false) fail("deploymentSloEvidence는 false여야 합니다");

const workload = required(report.workload, "workload");
positive(workload.operationsPerScenario, "workload.operationsPerScenario");
positive(workload.concurrency, "workload.concurrency");
positive(workload.globalConcurrency, "workload.globalConcurrency");
positive(workload.perClinicConcurrency, "workload.perClinicConcurrency");
if (!Array.isArray(workload.clinicCardinalities) || workload.clinicCardinalities.length === 0) fail("workload.clinicCardinalities가 비어 있습니다");
if (!Array.isArray(workload.churnRates) || workload.churnRates.length === 0) fail("workload.churnRates가 비어 있습니다");
if (!Array.isArray(workload.cacheModes) || workload.cacheModes.length === 0) fail("workload.cacheModes가 비어 있습니다");

const scenarios = required(report.scenarios, "scenarios");
if (!Array.isArray(scenarios) || scenarios.length === 0) fail("scenarios가 비어 있습니다");
for (const [index, scenario] of scenarios.entries()) {
  const path = `scenarios[${index}]`;
  required(scenario.name, `${path}.name`);
  positive(scenario.clinicCardinality, `${path}.clinicCardinality`);
  if (scenario.churnRate < 0 || scenario.churnRate > 1) fail(`${path}.churnRate 범위 오류`);
  if (!["cold", "warm"].includes(scenario.cacheMode)) fail(`${path}.cacheMode 오류`);
  positive(scenario.operations, `${path}.operations`);
  if (scenario.successfulOperations + scenario.backpressuredOperations !== scenario.operations) {
    fail(`${path} operation 합계가 맞지 않습니다`);
  }
  if (!Number.isFinite(scenario.warmupMillis) || scenario.warmupMillis < 0) fail(`${path}.warmupMillis가 음수이거나 누락되었습니다`);
  positive(scenario.workloadElapsedMillis, `${path}.workloadElapsedMillis`);
  positive(scenario.throughputOpsPerSecond, `${path}.throughputOpsPerSecond`);
  for (const metric of ["admissionLatencyMs", "queueingLatencyMs", "acquireLatencyMs", "reconcileLatencyMs", "renewLatencyMs"]) {
    percentiles(scenario[metric], `${path}.${metric}`);
  }
  required(scenario.failureReasons, `${path}.failureReasons`);
  positive(scenario.uniqueClinicIds, `${path}.uniqueClinicIds`);
  positive(scenario.redisKeyCountAfter, `${path}.redisKeyCountAfter`);
}

const summary = required(report.summary, "summary");
positive(summary.elapsedMillis, "summary.elapsedMillis");
positive(summary.workloadElapsedMillis, "summary.workloadElapsedMillis");
positive(summary.throughputOpsPerSecond, "summary.throughputOpsPerSecond");
positive(summary.steadyStateThroughputOpsPerSecond, "summary.steadyStateThroughputOpsPerSecond");
for (const metric of ["admissionLatencyMs", "queueingLatencyMs", "acquireLatencyMs", "reconcileLatencyMs", "renewLatencyMs"]) {
  percentiles(summary[metric], `summary.${metric}`);
}
positive(summary.successfulOperations, "summary.successfulOperations");
if (summary.backpressuredOperations < 0) fail("summary.backpressuredOperations가 음수입니다");
const recovery = required(report.leaseRecovery, "leaseRecovery");
if (recovery.status !== "reacquired") fail(`leaseRecovery.status는 reacquired여야 합니다: ${recovery.status}`);
positive(recovery.leaseMillis, "leaseRecovery.leaseMillis");
positive(recovery.reacquireLatencyMs, "leaseRecovery.reacquireLatencyMs");

const worstScenario = scenarios.reduce((worst, scenario) =>
  scenario.admissionLatencyMs.p99 > worst.admissionLatencyMs.p99 ? scenario : worst, scenarios[0]);
const p99 = summary.admissionLatencyMs.p99;
const targetStatus = p99 <= targetP99Ms ? "within-target" : "target-breached";
console.log(JSON.stringify({
  input,
  configuration: report.configuration,
  scenarioCount: scenarios.length,
  admissionP99Ms: p99,
  steadyStateThroughputOpsPerSecond: summary.steadyStateThroughputOpsPerSecond,
  worstScenario: worstScenario.name,
  targetP99Ms,
  targetStatus,
  leaseRecovery: recovery.status,
  deploymentSloEvidence: report.deploymentSloEvidence,
}, null, 2));

if (process.exitCode) process.exit();
if (targetStatus === "target-breached") {
  console.error(`검증 실패: summary.admissionLatencyMs.p99=${p99}ms > target=${targetP99Ms}ms`);
  process.exit(1);
}
