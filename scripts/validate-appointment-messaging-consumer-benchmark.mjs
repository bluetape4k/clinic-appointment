#!/usr/bin/env node

import fs from "node:fs";

const args = parseArgs(process.argv.slice(2));
if (!args.input) fail("Missing required --input");
const report = JSON.parse(fs.readFileSync(args.input, "utf8"));

requireValue(report.schemaVersion === 1, "schemaVersion must be 1");
requireValue(report.benchmarkFamily?.endsWith("PostgreSqlAppointmentConsumerBenchmark"), "benchmarkFamily must identify the PostgreSQL consumer benchmark");
requireValue(report.database === "postgresql", "database must be postgresql");
requireValue(report.postgresImage?.startsWith("postgres:"), "postgresImage must identify a PostgreSQL image");
requireValue(["main", "smoke"].includes(report.configuration), "configuration must be main or smoke");
requireValue(report.sourceFile && report.sourceFilePattern, "sourceFile and sourceFilePattern are required");
requireValue(report.sourceFile.includes(`/${report.configuration}/`) || report.sourceFile.startsWith(`benchmark/appointment-messaging-benchmark/build/reports/benchmarks/${report.configuration}/`), "sourceFile must preserve the selected configuration path");
requirePositiveInteger(report.seed, "seed");
requireValue(JSON.stringify(report.rowCounts) === JSON.stringify([10000, 100000]), "rowCounts must be [10000, 100000]");
requirePositiveInteger(report.cleanupBatchSize, "cleanupBatchSize");
requireValue(report.deploymentSloEvidence === false, "deploymentSloEvidence must remain false");
requireValue(report.lockContentionEvidence === true, "lockContentionEvidence must be true");

const expected = new Set(["boundedCleanup", "duplicateInboxLookup", "duplicateInboxInsertContention"]);
const seen = new Set();
requireValue(Array.isArray(report.measurements) && report.measurements.length === 6, "measurements must contain six operation/row results");
for (const measurement of report.measurements) {
  requireValue(expected.has(measurement.operation), `unexpected operation ${measurement.operation}`);
  const key = `${measurement.operation}/${measurement.rows}`;
  requireValue(!seen.has(key), `duplicate measurement ${key}`);
  seen.add(key);
  requireValue(report.rowCounts.includes(measurement.rows), `${key} must use a declared row count`);
  requirePositive(measurement.score, `${key}.score`);
  requireValue(typeof measurement.scoreUnit === "string" && measurement.scoreUnit.length > 0, `${key}.scoreUnit is required`);
  for (const percentile of ["p50", "p95", "p99"]) requirePositive(measurement.percentiles?.[percentile], `${key}.percentiles.${percentile}`);
  if (measurement.operation === "duplicateInboxInsertContention") {
    requireValue(measurement.scoreUnit === "ms/op" || measurement.scoreUnit === "us/op", `${key} must report a time unit`);
  } else {
    requireValue(measurement.scoreUnit === "ops/ms", `${key} must report throughput in ops/ms`);
  }
}
for (const operation of expected) for (const rows of report.rowCounts) requireValue(seen.has(`${operation}/${rows}`), `missing ${operation}/${rows}`);

console.log(`Validated ${report.benchmarkFamily}`);
console.log(`  PostgreSQL=${report.postgresImage} rows=${report.rowCounts.join(",")} seed=${report.seed}`);
console.log(`  measurements=${report.measurements.length} lockContentionEvidence=true deploymentSloEvidence=false`);

function parseArgs(raw) {
  const result = {};
  for (let index = 0; index < raw.length; index += 1) {
    const token = raw[index];
    if (!token.startsWith("--")) fail(`Unexpected argument: ${token}`);
    const key = token.slice(2);
    const value = raw[index + 1];
    if (!value || value.startsWith("--")) fail(`Missing value for --${key}`);
    result[key] = value;
    index += 1;
  }
  return result;
}

function requirePositive(value, field) {
  requireValue(typeof value === "number" && Number.isFinite(value) && value > 0, `${field} must be positive`);
}

function requirePositiveInteger(value, field) {
  requireValue(Number.isInteger(value) && value > 0, `${field} must be a positive integer`);
}

function requireValue(condition, message) {
  if (!condition) fail(message);
}

function fail(message) {
  console.error(`consumer benchmark validation failed: ${message}`);
  process.exit(1);
}
