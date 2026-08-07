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

const expectedOperations = new Set(["boundedCleanup", "duplicateInboxLookup", "duplicateInboxInsertContention"]);
const expectedRows = new Set([10_000, 100_000]);
const candidates = files.flatMap(({ file }) => {
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!Array.isArray(parsed)) return [];
  const entries = parsed.filter((entry) => entry?.benchmark?.includes("PostgreSqlAppointmentConsumerBenchmark."));
  const operations = new Set(entries.map((entry) => operationName(entry.benchmark)));
  const rows = new Set(entries.map((entry) => Number(entry.params?.consumerRows)));
  if (![...expectedOperations].every((operation) => operations.has(operation))) return [];
  if (![...expectedRows].every((row) => rows.has(row))) return [];
  return [{ entries, file }];
});

if (candidates.length === 0) {
  fail("No complete PostgreSQL appointment consumer benchmark report was found");
}

const { entries, file } = candidates[0];
const measurements = entries
  .filter((entry) => expectedOperations.has(operationName(entry.benchmark)))
  .map((entry) => {
    const metric = entry.primaryMetric;
    const percentiles = metric?.scorePercentiles;
    const measurement = {
      operation: operationName(entry.benchmark),
      rows: Number(entry.params?.consumerRows),
      mode: entry.mode,
      score: metric?.score,
      scoreUnit: metric?.scoreUnit,
      percentiles: {
        p50: percentiles?.["50.0"],
        p95: percentiles?.["95.0"],
        p99: percentiles?.["99.0"],
      },
    };
    for (const field of ["rows", "score", "scoreUnit", "percentiles"]) {
      if (field === "scoreUnit") {
        if (typeof measurement[field] !== "string" || measurement[field].length === 0) {
          fail(`Missing ${field} for ${measurement.operation}/${measurement.rows}`);
        }
      } else if (field === "percentiles") {
        if (!["p50", "p95", "p99"].every((percentile) => positive(measurement[field][percentile]))) {
          fail(`Missing percentile evidence for ${measurement.operation}/${measurement.rows}`);
        }
      } else if (!positive(measurement[field])) {
        fail(`Missing positive ${field} for ${measurement.operation}/${measurement.rows}`);
      }
    }
    return measurement;
  })
  .sort((left, right) => left.operation.localeCompare(right.operation) || left.rows - right.rows);

const configuration = requestedConfiguration ?? inferConfiguration(file);
const report = {
  schemaVersion: 1,
  benchmarkFamily: "io.bluetape4k.clinic.appointment.benchmark.PostgreSqlAppointmentConsumerBenchmark",
  database: "postgresql",
  postgresImage: args["postgres-image"] ?? "postgres:18-alpine",
  kotlinxBenchmarkVersion: "0.4.17",
  seed: numberArg(args, "seed", 42),
  rowCounts: [...expectedRows],
  cleanupBatchSize: numberArg(args, "cleanup-batch-size", 32),
  configuration,
  mode: "mixed",
  measurements,
  deploymentSloEvidence: false,
  lockContentionEvidence: true,
  sourceFile: relativeSourceFile(file),
  sourceFilePattern: stableSourceFile(configuration),
};

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(`Collected ${report.benchmarkFamily}`);
console.log(`  source=${report.sourceFile}`);
for (const measurement of report.measurements) {
  console.log(`  ${measurement.operation}/${measurement.rows}: ${measurement.score} ${measurement.scoreUnit} p95=${measurement.percentiles.p95}`);
}
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

function operationName(benchmark) {
  return benchmark?.split(".").at(-1);
}

function positive(value) {
  return typeof value === "number" && Number.isFinite(value) && value > 0;
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
  console.error(`consumer benchmark collection failed: ${message}`);
  process.exit(1);
}
