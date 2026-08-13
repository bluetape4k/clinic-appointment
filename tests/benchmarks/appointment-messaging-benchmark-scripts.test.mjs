import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { test } from "node:test";

const execFileAsync = promisify(execFile);
const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const collector = path.join(repositoryRoot, "scripts/collect-appointment-messaging-benchmark.mjs");
const consumerCollector = path.join(repositoryRoot, "scripts/collect-appointment-messaging-consumer-benchmark.mjs");
const consumerValidator = path.join(repositoryRoot, "scripts/validate-appointment-messaging-consumer-benchmark.mjs");
const issue34Comparator = path.join(repositoryRoot, "scripts/compare-issue34-benchmark.sh");
const issue34CodecComparator = path.join(repositoryRoot, "scripts/compare-issue34-codec-benchmark.mjs");

test("collector selects the requested configuration from mixed raw reports", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "appointment-messaging-benchmark-"));
  try {
    const mainDir = path.join(root, "main", "2026-08-06T00.00.00.000000");
    const smokeDir = path.join(root, "smoke", "2026-08-06T01.00.00.000000");
    await mkdir(mainDir, { recursive: true });
    await mkdir(smokeDir, { recursive: true });
    await writeFile(path.join(mainDir, "main.json"), rawReport(2));
    await writeFile(path.join(smokeDir, "main.json"), rawReport(1));

    const output = path.join(root, "stable", "benchmark.json");
    await execFileAsync(process.execPath, [collector, "--input-dir", root, "--output", output, "--config", "main"], {
      cwd: repositoryRoot,
    });

    const report = JSON.parse(await readFile(output, "utf8"));
    assert.equal(report.configuration, "main");
    assert.equal(report.score, 2);
    assert.match(report.sourceFile, /main\/2026-08-06T00\.00\.00\.000000\/main\.json$/);
    assert.equal(
      report.sourceFilePattern,
      "benchmark/appointment-messaging-benchmark/build/reports/benchmarks/main/main.json",
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

function rawReport(score) {
  return JSON.stringify([
    {
      benchmark: "io.bluetape4k.clinic.appointment.benchmark.PostgreSqlAppointmentOutboxBenchmark.claimBatch",
      mode: "thrpt",
      primaryMetric: {
        score,
        scoreUnit: "ops/ms",
        scorePercentiles: { "50.0": score, "95.0": score, "99.0": score },
      },
    },
  ]);
}

test("consumer collector preserves throughput and contention measurements", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "appointment-messaging-consumer-benchmark-"));
  try {
    const smokeDir = path.join(root, "smoke", "2026-08-07T00.00.00.000000");
    await mkdir(smokeDir, { recursive: true });
    await writeFile(path.join(smokeDir, "main.json"), JSON.stringify([
      rawConsumer("boundedCleanup", "10000", "thrpt", "ops/ms", 0.1),
      rawConsumer("boundedCleanup", "100000", "thrpt", "ops/ms", 0.04),
      rawConsumer("duplicateInboxLookup", "10000", "thrpt", "ops/ms", 0.2),
      rawConsumer("duplicateInboxLookup", "100000", "thrpt", "ops/ms", 0.3),
      rawConsumer("duplicateInboxInsertContention", "10000", "sample", "ms/op", 5),
      rawConsumer("duplicateInboxInsertContention", "100000", "sample", "ms/op", 6),
    ]));

    const output = path.join(root, "stable", "consumer.json");
    await execFileAsync(process.execPath, [consumerCollector, "--input-dir", root, "--output", output, "--config", "smoke"], {
      cwd: repositoryRoot,
    });
    await execFileAsync(process.execPath, [consumerValidator, "--input", output], { cwd: repositoryRoot });
    const report = JSON.parse(await readFile(output, "utf8"));
    assert.equal(report.measurements.length, 6);
    assert.equal(report.lockContentionEvidence, true);
    assert.equal(report.measurements.find((measurement) => measurement.operation === "duplicateInboxInsertContention").scoreUnit, "ms/op");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator accepts three-run artifacts within the regression budget", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    await writeFile(baseline, JSON.stringify(issue34Report(100, 200, 20, 0.1, 0.01, 0, "baseline")));
    await writeFile(candidate, JSON.stringify(issue34Report(105, 210, 21, 0.001, 0.0001)));

    const result = await execFileAsync(issue34Comparator, [baseline, candidate], {
      cwd: repositoryRoot,
    });

    assert.match(result.stdout, /PASS/);
    assert.match(result.stdout, /p95/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects a p99 regression beyond the hard gate", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    await writeFile(baseline, JSON.stringify(issue34Report(100, 200, 20, 0.1, 0.01, 0, "baseline")));
    await writeFile(candidate, JSON.stringify(issue34Report(100, 250, 20, 0.1, 0.01, 0, "candidate")));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /p99/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects scenario mismatches even when latency is within budget", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    await writeFile(baseline, JSON.stringify(issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline")));
    await writeFile(candidate, JSON.stringify(issue34Report(100, 200, 20, 0.001, 0.0001, 0.01, "candidate")));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /scenario mismatch/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects baseline and candidate from the same source commit", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    candidateReport.environment.sourceCommit = baselineReport.environment.sourceCommit;
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /sourceCommit/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 codec comparator accepts two mixed-schema scenarios with three runs", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-codec-benchmark-"));
  try {
    const baseline = path.join(root, "baseline");
    const candidate = path.join(root, "candidate");
    await mkdir(baseline, { recursive: true });
    await mkdir(candidate, { recursive: true });
    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baseline : candidate;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          await writeFile(
            path.join(directory, `${mode}-${mix}-run${run}.json`),
            JSON.stringify(codecReport(mode, mix, run)),
          );
        }
      }
    }

    const result = await execFileAsync(process.execPath, [issue34CodecComparator, baseline, candidate], {
      cwd: repositoryRoot,
    });

    assert.match(result.stdout, /PASS legacy-heavy/);
    assert.match(result.stdout, /PASS current-heavy/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 codec comparator rejects baseline and candidate from the same source commit", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-codec-benchmark-"));
  try {
    const baseline = path.join(root, "baseline");
    const candidate = path.join(root, "candidate");
    await mkdir(baseline, { recursive: true });
    await mkdir(candidate, { recursive: true });
    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baseline : candidate;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          const report = codecReport(mode, mix, run);
          if (mode === "candidate") {
            report.environment.sourceCommit = "pre-change-commit";
          }
          await writeFile(
            path.join(directory, `${mode}-${mix}-run${run}.json`),
            JSON.stringify(report),
          );
        }
      }
    }

    await assert.rejects(
      execFileAsync(process.execPath, [issue34CodecComparator, baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /sourceCommit/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 codec comparator rejects a decode failure", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-codec-benchmark-"));
  try {
    const baseline = path.join(root, "baseline");
    const candidate = path.join(root, "candidate");
    await mkdir(baseline, { recursive: true });
    await mkdir(candidate, { recursive: true });
    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baseline : candidate;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          const report = codecReport(mode, mix, run);
          if (mode === "candidate" && mix === "current-heavy" && run === 2) {
            report.metrics.decodeFailures = 1;
          }
          await writeFile(
            path.join(directory, `${mode}-${mix}-run${run}.json`),
            JSON.stringify(report),
          );
        }
      }
    }

    await assert.rejects(
      execFileAsync(process.execPath, [issue34CodecComparator, baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /decode failures/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

function issue34Report(
  p95,
  p99,
  lockWaitP95,
  unexpectedErrorRate,
  unintendedRetryExhaustionRate,
  scenarioMismatchRate = 0,
  mode = "candidate",
) {
  return {
    schemaVersion: 1,
    benchmark: "issue-34-patient-appointment-cancel",
    mode,
    environment: issue34Environment(mode),
    runs: [1, 2, 3].map((run) => ({
      run,
      cancelP95Millis: p95,
      cancelP99Millis: p99,
      unexpectedErrorRate,
      unintendedRetryExhaustionRate,
      lockWaitP95Millis: lockWaitP95,
      expectedConflictRate: 0.2,
      expectedRetryExhaustionRate: 0.1,
      scenarioMismatchRate,
    })),
  };
}

function issue34Environment(mode) {
  return {
    datasetAppointments: 100,
    warmupSeconds: 30,
    measureSeconds: 300,
    sameAppointmentConcurrency: 10,
    differentAppointmentConcurrency: 20,
    seed: 34,
    postgresqlImage: "postgres:18-alpine",
    jdk: "OpenJDK Runtime Environment",
    vm: "OpenJDK 64-Bit Server VM",
    sourceCommit: mode === "baseline" ? "pre-change-commit" : "candidate-commit",
  };
}

function codecReport(mode, mix, run, metrics = {}) {
  return {
    schemaVersion: 1,
    benchmark: "issue-34-notification-codec-backlog",
    mode,
    mix,
    run,
    environment: {
      database: "h2",
      datasetRows: 10000,
      warmupSeconds: 30,
      measureSeconds: 300,
      detailLength: 15,
      batchSize: 500,
      legacyRatio: mix === "legacy-heavy" ? 0.8 : 0.2,
      jdk: "OpenJDK Runtime Environment",
      vm: "OpenJDK 64-Bit Server VM",
      sourceCommit: mode === "baseline" ? "pre-change-commit" : "candidate-commit",
    },
    metrics: {
      throughputRowsPerSecond: 1000,
      decodeP95Millis: 10,
      decodeP99Millis: 20,
      decodeFailures: 0,
      drainTimeMillis: 100,
      decodedRows: 10000,
      latencySamples: 10000,
      passes: 1,
      ...metrics,
    },
  };
}

function rawConsumer(operation, rows, mode, scoreUnit, score) {
  return {
    benchmark: `io.bluetape4k.clinic.appointment.benchmark.PostgreSqlAppointmentConsumerBenchmark.${operation}`,
    mode,
    params: { consumerRows: rows },
    primaryMetric: {
      score,
      scoreUnit,
      scorePercentiles: { "50.0": score, "95.0": score * 1.1, "99.0": score * 1.2 },
    },
  };
}
