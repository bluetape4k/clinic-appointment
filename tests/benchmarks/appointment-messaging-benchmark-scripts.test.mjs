import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
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
const issue34Simulation = path.join(
  repositoryRoot,
  "appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/PatientAppointmentCancelPostgresSimulation.kt",
);
const issue34Fixture = path.join(
  repositoryRoot,
  "appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/commitment/PatientAppointmentCancelPostgresFixture.kt",
);

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

test("issue 34 comparator accepts monotonic spans independent of audit timestamps", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.1, 0.01, 0, "baseline");
    const candidateReport = issue34Report(105, 210, 21, 0.001, 0.0001);
    for (const run of [...baselineReport.runs, ...candidateReport.runs]) {
      run.measurementEndedAtEpochMillis = 401_000;
    }
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

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
    setIssue34SourceCommit(candidateReport, baselineReport.environment.sourceCommit);
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

test("issue 34 comparator rejects a lock-wait sampling failure", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 0, 0.001, 0.0001, 0, "candidate");
    candidateReport.runs[1].lockWaitSampleFailures = 1;
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /lock-wait sampling failures/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects smoke-window artifacts", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    baselineReport.environment.warmupSeconds = 1;
    baselineReport.environment.measureSeconds = 2;
    candidateReport.environment.warmupSeconds = 1;
    candidateReport.environment.measureSeconds = 2;
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /warmupSeconds|measureSeconds/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects mixed run source commits", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    candidateReport.runs[1].sourceCommit = "different-candidate-commit";
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /run sourceCommit/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator requires auditable warm-up and measurement request counts", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    candidateReport.runs[0].warmupRequests = 0;
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /warmupRequests/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 cancel load synchronizes phase boundaries before measurement and lock sampling", async () => {
  const simulation = await readFile(issue34Simulation, "utf8");
  const fixture = await readFile(issue34Fixture, "utf8");

  assert.match(simulation, /BenchmarkPhase\.WARMUP/);
  assert.match(simulation, /BenchmarkPhase\.MEASUREMENT/);
  assert.match(simulation, /awaitMeasurementStart/);
  assert.match(simulation, /awaitMeasurementEnd/);
  assert.match(fixture, /CyclicBarrier/);
  assert.match(fixture, /measurementStartedAtEpochMillis/);
  assert.match(fixture, /measurementEndedAtEpochMillis/);
  assert.match(fixture, /measurementStartedAtNanos/);
  assert.match(fixture, /measurementEndedAtNanos/);
  assert.match(fixture, /TimeUnit\.NANOSECONDS\.toMillis/);
  assert.match(fixture, /stopLockWaitSampling\(\)[\s\S]*measurementEndedAtEpochMillis\.compareAndSet/);
  assert.match(fixture, /get\(SAMPLER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit\.SECONDS\)/);
  assert.match(fixture, /queryTimeout = LOCK_WAIT_QUERY_TIMEOUT_SECONDS/);
  assert.match(fixture, /issue34\.pauseMillis/);
  assert.match(
    fixture,
    /override fun close\(\) \{\s*try \{\s*stopLockWaitSampling\(\)\s*} finally \{\s*samplerExecutor\.shutdownNow\(\)\s*try \{\s*awaitTermination\(replacementExecutor\)\s*} finally \{\s*awaitTermination\(contentionExecutor\)/,
  );
  assert.doesNotMatch(fixture, /measurementStartsAtNanos/);
});

test("issue 34 report append accepts formatted JSON and Gatling cleanup is fail-safe", async () => {
  const simulation = await readFile(issue34Simulation, "utf8");
  const fixture = await readFile(issue34Fixture, "utf8");

  assert.equal(fixture.includes('Regex("\\\"runs\\\"\\\\s*:\\\\s*\\\\[")'), true);
  assert.match(simulation, /override fun after\(\) \{\s*try \{/);
  assert.match(simulation, /finally \{[\s\S]*fixture\.close\(\)[\s\S]*server\.stop\(0\)[\s\S]*executor\.shutdownNow\(\)/);
});

test("issue 34 comparator rejects a run from a different environment fingerprint", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    candidateReport.runs[1].environmentFingerprint = "mixed-environment-fingerprint";
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /environmentFingerprint/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects a stale canonical environment fingerprint", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    candidateReport.environment.environmentFingerprint = "stale-environment-fingerprint";
    for (const run of candidateReport.runs) {
      run.environmentFingerprint = "stale-environment-fingerprint";
      run.environment.environmentFingerprint = "stale-environment-fingerprint";
    }
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /canonical SHA-256/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects a run with a different request pause", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    candidateReport.runs[1].environment.pauseMillis = 0;
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /pauseMillis/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("issue 34 comparator rejects a measurement span shorter than the configured window", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-benchmark-"));
  try {
    const baseline = path.join(root, "baseline.json");
    const candidate = path.join(root, "candidate.json");
    const baselineReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "baseline");
    const candidateReport = issue34Report(100, 200, 20, 0.001, 0.0001, 0, "candidate");
    candidateReport.runs[0].measurementEndedAtEpochMillis = 2_000;
    candidateReport.runs[0].measurementSpanMillis = 1_000;
    await writeFile(baseline, JSON.stringify(baselineReport));
    await writeFile(candidate, JSON.stringify(candidateReport));

    await assert.rejects(
      execFileAsync(issue34Comparator, [baseline, candidate], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /measurementSpanMillis/);
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
  const environment = issue34Environment(mode);
  return {
    schemaVersion: 1,
    benchmark: "issue-34-patient-appointment-cancel",
    mode,
    environment,
    runs: [1, 2, 3].map((run) => ({
      run,
      sourceCommit: environment.sourceCommit,
      environmentFingerprint: environment.environmentFingerprint,
      environment: { ...environment },
      measurementStartedAtEpochMillis: 1_000,
      measurementEndedAtEpochMillis: 301_000,
      measurementSpanMillis: 300_000,
      measurementClock: "SYSTEM_NANO_TIME",
      cancelP95Millis: p95,
      cancelP99Millis: p99,
      unexpectedErrorRate,
      unintendedRetryExhaustionRate,
      lockWaitP95Millis: lockWaitP95,
      lockWaitSampleQueries: 30,
      lockWaitSampleFailures: 0,
      warmupRequests: 30,
      requests: 100,
      expectedConflictRate: 0.2,
      expectedRetryExhaustionRate: 0.1,
      scenarioMismatchRate,
    })),
  };
}

function issue34Environment(mode) {
  const environment = {
    datasetAppointments: 100,
    warmupSeconds: 30,
    measureSeconds: 300,
    sameAppointmentConcurrency: 10,
    differentAppointmentConcurrency: 20,
    pauseMillis: 1_000,
    seed: 34,
    postgresqlImage: "postgres:18-alpine",
    jdk: "OpenJDK Runtime Environment",
    vm: "OpenJDK 64-Bit Server VM",
    sourceCommit: mode === "baseline" ? "pre-change-commit" : "candidate-commit",
  };
  environment.environmentFingerprint = issue34EnvironmentFingerprint(environment);
  return environment;
}

function issue34EnvironmentFingerprint(environment) {
  return createHash("sha256").update(JSON.stringify(environment)).digest("hex");
}

function setIssue34SourceCommit(report, sourceCommit) {
  report.environment.sourceCommit = sourceCommit;
  delete report.environment.environmentFingerprint;
  report.environment.environmentFingerprint = issue34EnvironmentFingerprint(report.environment);
  for (const run of report.runs) {
    run.sourceCommit = sourceCommit;
    run.environment = { ...report.environment };
    run.environmentFingerprint = report.environment.environmentFingerprint;
  }
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
