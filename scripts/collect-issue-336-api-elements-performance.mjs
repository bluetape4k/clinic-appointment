#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const args = parseArgs(process.argv.slice(2));
const ref = required(args, "ref");
const mode = required(args, "mode");
const runs = positiveInteger(args.runs ?? "3", "runs");
const output = args.output ?? "build/reports/consumer-fixtures/issue-336/performance.json";
const gradleArgs = splitArgs(args["gradle-args"] ?? "compileModuleConsumerFixtures --no-daemon");
const root = process.cwd();
const gradle = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const timeBinary = process.env.TIME_BIN ?? "/usr/bin/time";
const samples = [];

for (let index = 0; index < runs; index += 1) {
  const gradleUserHome = fs.mkdtempSync(path.join(os.tmpdir(), "issue-336-gradle-"));
  const command = [
    "--no-build-cache",
    "--max-workers=2",
    ...gradleArgs,
  ];
  const started = process.hrtime.bigint();
  const result = spawnSync(timeBinary, ["-p", gradle, ...command], {
    cwd: root,
    encoding: "utf8",
    env: {
      ...process.env,
      GRADLE_USER_HOME: gradleUserHome,
      GRADLE_OPTS: `${process.env.GRADLE_OPTS ?? ""} -Dorg.gradle.daemon=false -Dkotlin.compiler.execution.strategy=in-process`.trim(),
    },
    maxBuffer: 20 * 1024 * 1024,
  });
  const elapsedMs = Number(process.hrtime.bigint() - started) / 1_000_000;
  const outputText = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  const realSeconds = Number.parseFloat(outputText.match(/^real\s+(\d+(?:\.\d+)?)$/m)?.[1] ?? "NaN");
  samples.push({
    run: index + 1,
    status: result.status === 0 ? "passed" : "failed",
    exitCode: result.status,
    elapsedMs: round(elapsedMs),
    realMs: Number.isFinite(realSeconds) ? round(realSeconds * 1000) : null,
    taskOutcomes: parseTaskOutcomes(outputText),
  });
  fs.rmSync(gradleUserHome, { recursive: true, force: true });
  if (result.status !== 0) {
    console.error(outputText.slice(-4_000));
    break;
  }
}

const successful = samples.filter((sample) => sample.status === "passed");
const report = {
  schemaVersion: 1,
  contract: "issue-336-api-elements",
  ref,
  mode,
  runs,
  command: [gradle, ...gradleArgs],
  environment: {
    gradleUserHome: "temporary-per-run",
    gradleOpts: "-Dorg.gradle.daemon=false -Dkotlin.compiler.execution.strategy=in-process",
    noBuildCache: true,
    maxWorkers: 2,
    jdk: process.env.JAVA_HOME ? "configured" : "detected",
  },
  samples,
  summary: {
    status: samples.length === runs && successful.length === runs ? "passed" : "failed",
    medianMs: median(successful.map((sample) => sample.realMs ?? sample.elapsedMs)),
    minMs: minimum(successful.map((sample) => sample.realMs ?? sample.elapsedMs)),
    maxMs: maximum(successful.map((sample) => sample.realMs ?? sample.elapsedMs)),
  },
};

fs.mkdirSync(path.dirname(path.resolve(root, output)), { recursive: true });
fs.writeFileSync(path.resolve(root, output), `${JSON.stringify(report, null, 2)}\n`);
console.log(`Wrote ${output}: ${report.summary.status}`);
if (report.summary.status !== "passed") process.exitCode = 1;

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

function splitArgs(value) {
  return value.match(/(?:[^\s"]+|"[^"]*")+/g)?.map((token) => token.replace(/^"|"$/g, "")) ?? [];
}

function parseTaskOutcomes(outputText) {
  return [...outputText.matchAll(/^> Task (:[^\s]+)(?:\s+(.+))?$/gm)].map((match) => ({
    task: match[1],
    outcome: match[2]?.trim() ?? "executed",
  }));
}

function median(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return round(sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle]);
}

function minimum(values) {
  return values.length === 0 ? null : round(Math.min(...values));
}

function maximum(values) {
  return values.length === 0 ? null : round(Math.max(...values));
}

function round(value) {
  return Math.round(value * 100) / 100;
}

function required(map, key) {
  if (!map[key]) fail(`Missing required --${key}`);
  return map[key];
}

function positiveInteger(value, key) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) fail(`${key} must be a positive integer`);
  return parsed;
}

function fail(message) {
  console.error(`Issue #336 performance collector failed: ${message}`);
  process.exit(2);
}
