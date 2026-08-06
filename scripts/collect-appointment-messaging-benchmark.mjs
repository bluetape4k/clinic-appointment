#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const args = parseArgs(process.argv.slice(2));
const inputDir = required(args, "input-dir");
const output = required(args, "output");
const requestedConfiguration = args.config;
if (requestedConfiguration && !["main", "smoke"].includes(requestedConfiguration)) {
  fail(`--config must be main or smoke, received ${requestedConfiguration}`);
}

const files = jsonFiles(inputDir)
  .filter((file) => !requestedConfiguration || inferConfiguration(file) === requestedConfiguration)
  .map((file) => ({ file, mtime: fs.statSync(file).mtimeMs }))
  .sort((left, right) => right.mtime - left.mtime);

if (files.length === 0) {
  fail(`No kotlinx-benchmark JSON files found below ${inputDir}`);
}

const candidates = files.flatMap(({ file }) => {
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((entry) => entry?.benchmark?.endsWith("PostgreSqlAppointmentOutboxBenchmark.claimBatch"))
    .map((entry) => ({ entry, file }));
});

if (candidates.length === 0) {
  fail("No PostgreSQL appointment outbox claim benchmark result was found");
}

const { entry, file } = candidates[0];
const metric = entry.primaryMetric;
const percentiles = metric?.scorePercentiles;
const configuration = requestedConfiguration ?? inferConfiguration(file);
const report = {
  schemaVersion: 1,
  benchmark: entry.benchmark,
  database: "postgresql",
  postgresImage: args["postgres-image"] ?? "postgres:18-alpine",
  kotlinxBenchmarkVersion: "0.4.17",
  seed: numberArg(args, "seed", 41),
  rows: numberArg(args, "rows", 20_000),
  configuration,
  mode: entry.mode,
  score: metric?.score,
  scoreUnit: metric?.scoreUnit,
  percentiles: {
    p50: percentiles?.["50.0"],
    p95: percentiles?.["95.0"],
    p99: percentiles?.["99.0"],
  },
  deploymentSloEvidence: false,
  sourceFile: relativeSourceFile(file),
  sourceFilePattern: stableSourceFile(configuration),
};

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(`Collected ${report.benchmark}`);
console.log(`  source=${report.sourceFile}`);
console.log(`  mode=${report.mode} score=${report.score} ${report.scoreUnit}`);
console.log(`  p50=${report.percentiles.p50} p95=${report.percentiles.p95} p99=${report.percentiles.p99}`);
console.log(`  output=${output}`);

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

function required(values, key) {
  if (!values[key]) fail(`Missing required --${key}`);
  return values[key];
}

function numberArg(values, key, fallback) {
  const value = Number(values[key] ?? fallback);
  if (!Number.isInteger(value) || value <= 0) fail(`--${key} must be a positive integer`);
  return value;
}

function inferConfiguration(file) {
  const normalized = file.replaceAll(path.sep, "/");
  return normalized.includes("/smoke/") ? "smoke" : "main";
}

function stableSourceFile(configuration) {
  return `benchmark/appointment-messaging-benchmark/build/reports/benchmarks/${configuration}/main.json`;
}

function relativeSourceFile(file) {
  return path.relative(process.cwd(), file).replaceAll(path.sep, "/");
}

function jsonFiles(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) return jsonFiles(fullPath);
    return entry.isFile() && entry.name.endsWith(".json") ? [fullPath] : [];
  });
}

function fail(message) {
  console.error(`benchmark collection failed: ${message}`);
  process.exit(1);
}
