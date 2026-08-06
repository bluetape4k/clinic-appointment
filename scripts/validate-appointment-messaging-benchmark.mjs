#!/usr/bin/env node

import fs from "node:fs";

const args = parseArgs(process.argv.slice(2));
if (!args.input) fail("Missing required --input");
const report = JSON.parse(fs.readFileSync(args.input, "utf8"));

requireValue(report.schemaVersion === 1, "schemaVersion must be 1");
requireValue(report.database === "postgresql", "database must be postgresql");
requireValue(report.benchmark?.endsWith("PostgreSqlAppointmentOutboxBenchmark.claimBatch"), "benchmark must identify the PostgreSQL claim path");
requireValue(report.postgresImage?.startsWith("postgres:"), "postgresImage must identify a PostgreSQL image");
requirePositiveInteger(report.seed, "seed");
requirePositiveInteger(report.rows, "rows");
requirePositive(report.score, "score");
requireValue(typeof report.scoreUnit === "string" && report.scoreUnit.length > 0, "scoreUnit is required");
for (const percentile of ["p50", "p95", "p99"]) {
  requirePositive(report.percentiles?.[percentile], `percentiles.${percentile}`);
}
requireValue(report.deploymentSloEvidence === false, "deploymentSloEvidence must remain false");

console.log(`Validated ${report.benchmark}`);
console.log(`  PostgreSQL=${report.postgresImage} rows=${report.rows} seed=${report.seed}`);
console.log(`  ${report.mode} ${report.scoreUnit}: p50=${report.percentiles.p50} p95=${report.percentiles.p95} p99=${report.percentiles.p99}`);
console.log("  deploymentSloEvidence=false");

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
  console.error(`benchmark validation failed: ${message}`);
  process.exit(1);
}
