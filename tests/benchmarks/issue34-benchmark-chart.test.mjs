import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { test } from "node:test";

const execFileAsync = promisify(execFile);
const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const generator = path.join(repositoryRoot, "scripts/generate-issue34-benchmark-chart.mjs");

test("Issue #34 chart generator writes SVG panels and a measured Korean analysis", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-chart-"));
  try {
    const baselineCancel = path.join(root, "cancel-baseline.json");
    const candidateCancel = path.join(root, "cancel-candidate.json");
    const baselineCodec = path.join(root, "codec-baseline");
    const candidateCodec = path.join(root, "codec-candidate");
    const output = path.join(root, "charts");
    await mkdir(baselineCodec, { recursive: true });
    await mkdir(candidateCodec, { recursive: true });
    await writeFile(baselineCancel, JSON.stringify(cancelReport("baseline")));
    await writeFile(candidateCancel, JSON.stringify(cancelReport("candidate", 105, 210, 21)));

    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baselineCodec : candidateCodec;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          await writeFile(
            path.join(directory, `${mode}-${mix}-run${run}.json`),
            JSON.stringify(codecReport(mode, mix, run)),
          );
        }
      }
    }

    await execFileAsync(
      process.execPath,
      [
        generator,
        "--cancel-baseline",
        baselineCancel,
        "--cancel-candidate",
        candidateCancel,
        "--codec-baseline-dir",
        baselineCodec,
        "--codec-candidate-dir",
        candidateCodec,
        "--output-dir",
        output,
      ],
      { cwd: repositoryRoot },
    );

    const expectedCharts = [
      "issue-34-patient-appointment-cancel-latency-ko.svg",
      "issue-34-patient-appointment-cancel-safety-ko.svg",
      "issue-34-notification-codec-latency-ko.svg",
      "issue-34-notification-codec-throughput-ko.svg",
      "issue-34-benchmark-analysis.ko.md",
    ];
    for (const filename of expectedCharts) {
      const content = await readFile(path.join(output, filename), "utf8");
      assert.notEqual(content.trim(), "");
      assert.doesNotMatch(content, /NaN|undefined/);
    }

    const latency = await readFile(path.join(output, expectedCharts[0]), "utf8");
    assert.match(latency, /취소 latency/);
    assert.match(latency, /baseline/);
    assert.match(latency, /candidate/);
    assert.match(latency, /ms/);

    const codecLatency = await readFile(path.join(output, "issue-34-notification-codec-latency-ko.svg"), "utf8");
    assert.match(codecLatency, /판정: PASS/);

    const analysis = await readFile(path.join(output, "issue-34-benchmark-analysis.ko.md"), "utf8");
    assert.match(analysis, /판정.*PASS/);
    assert.match(analysis, /sourceCommit/);
    assert.match(analysis, /benchmark 근거이며 배포 SLO가 아니다/);
    assert.match(analysis, /lock-wait 표본 신뢰도/);
    assert.match(analysis, /run1=30, run2=30, run3=30/);
    assert.match(analysis, /run1=300000ms, run2=300000ms, run3=300000ms/);
    assert.match(analysis, /조회 실패를 `0 ms`로 해석하지 않는다/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Issue #34 chart generator rejects a same-commit comparison before writing charts", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-chart-invalid-"));
  try {
    const baselineCancel = path.join(root, "cancel-baseline.json");
    const candidateCancel = path.join(root, "cancel-candidate.json");
    const baselineCodec = path.join(root, "codec-baseline");
    const candidateCodec = path.join(root, "codec-candidate");
    await mkdir(baselineCodec, { recursive: true });
    await mkdir(candidateCodec, { recursive: true });
    await writeFile(baselineCancel, JSON.stringify(cancelReport("baseline")));
    const sameCommit = cancelReport("candidate");
    sameCommit.environment.sourceCommit = "pre-change-commit";
    await writeFile(candidateCancel, JSON.stringify(sameCommit));

    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baselineCodec : candidateCodec;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          await writeFile(
            path.join(directory, `${mode}-${mix}-run${run}.json`),
            JSON.stringify(codecReport(mode, mix, run)),
          );
        }
      }
    }

    await assert.rejects(
      execFileAsync(
        process.execPath,
        [
          generator,
          "--cancel-baseline",
          baselineCancel,
          "--cancel-candidate",
          candidateCancel,
          "--codec-baseline-dir",
          baselineCodec,
          "--codec-candidate-dir",
          candidateCodec,
          "--output-dir",
          path.join(root, "charts"),
        ],
        { cwd: repositoryRoot },
      ),
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

test("Issue #34 chart generator rejects failed lock-wait sampling", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-chart-lock-wait-"));
  try {
    const baselineCancel = path.join(root, "cancel-baseline.json");
    const candidateCancel = path.join(root, "cancel-candidate.json");
    const baselineCodec = path.join(root, "codec-baseline");
    const candidateCodec = path.join(root, "codec-candidate");
    await mkdir(baselineCodec, { recursive: true });
    await mkdir(candidateCodec, { recursive: true });
    await writeFile(baselineCancel, JSON.stringify(cancelReport("baseline")));
    const failedSampling = cancelReport("candidate");
    failedSampling.runs[0].lockWaitSampleFailures = 1;
    await writeFile(candidateCancel, JSON.stringify(failedSampling));

    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baselineCodec : candidateCodec;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          await writeFile(
            path.join(directory, `${mode}-${mix}-run${run}.json`),
            JSON.stringify(codecReport(mode, mix, run)),
          );
        }
      }
    }

    await assert.rejects(
      execFileAsync(
        process.execPath,
        [
          generator,
          "--cancel-baseline",
          baselineCancel,
          "--cancel-candidate",
          candidateCancel,
          "--codec-baseline-dir",
          baselineCodec,
          "--codec-candidate-dir",
          candidateCodec,
          "--output-dir",
          path.join(root, "charts"),
        ],
        { cwd: repositoryRoot },
      ),
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

test("Issue #34 chart generator rejects mixed cancel run source commits", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-chart-provenance-"));
  try {
    const baselineCancel = path.join(root, "cancel-baseline.json");
    const candidateCancel = path.join(root, "cancel-candidate.json");
    const baselineCodec = path.join(root, "codec-baseline");
    const candidateCodec = path.join(root, "codec-candidate");
    await mkdir(baselineCodec, { recursive: true });
    await mkdir(candidateCodec, { recursive: true });
    await writeFile(baselineCancel, JSON.stringify(cancelReport("baseline")));
    const candidate = cancelReport("candidate");
    candidate.runs[1].sourceCommit = "different-candidate-commit";
    await writeFile(candidateCancel, JSON.stringify(candidate));
    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baselineCodec : candidateCodec;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          await writeFile(path.join(directory, `${mode}-${mix}-run${run}.json`), JSON.stringify(codecReport(mode, mix, run)));
        }
      }
    }

    await assert.rejects(
      execFileAsync(process.execPath, [generator, "--cancel-baseline", baselineCancel, "--cancel-candidate", candidateCancel, "--codec-baseline-dir", baselineCodec, "--codec-candidate-dir", candidateCodec, "--output-dir", path.join(root, "charts")], { cwd: repositoryRoot }),
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

test("Issue #34 chart generator rejects a mixed cancel run environment", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-chart-run-environment-"));
  try {
    const candidate = cancelReport("candidate");
    candidate.runs[1].environment.jdk = "different-jdk";
    await assertGeneratorRejectsCancelReport(root, candidate, /environment key jdk/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Issue #34 chart generator rejects a short cancel measurement span", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-chart-measurement-span-"));
  try {
    const candidate = cancelReport("candidate");
    candidate.runs[0].measurementEndedAtEpochMillis = 2_000;
    candidate.runs[0].measurementSpanMillis = 1_000;
    await assertGeneratorRejectsCancelReport(root, candidate, /measurementSpanMillis/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Issue #34 chart generator rejects codec reports without a fixed environment field", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-34-chart-environment-"));
  try {
    const baselineCancel = path.join(root, "cancel-baseline.json");
    const candidateCancel = path.join(root, "cancel-candidate.json");
    const baselineCodec = path.join(root, "codec-baseline");
    const candidateCodec = path.join(root, "codec-candidate");
    await mkdir(baselineCodec, { recursive: true });
    await mkdir(candidateCodec, { recursive: true });
    await writeFile(baselineCancel, JSON.stringify(cancelReport("baseline")));
    await writeFile(candidateCancel, JSON.stringify(cancelReport("candidate")));

    for (const mode of ["baseline", "candidate"]) {
      const directory = mode === "baseline" ? baselineCodec : candidateCodec;
      for (const mix of ["legacy-heavy", "current-heavy"]) {
        for (const run of [1, 2, 3]) {
          const report = codecReport(mode, mix, run);
          if (mode === "candidate" && mix === "current-heavy" && run === 2) {
            delete report.environment.batchSize;
          }
          await writeFile(
            path.join(directory, `${mode}-${mix}-run${run}.json`),
            JSON.stringify(report),
          );
        }
      }
    }

    await assert.rejects(
      execFileAsync(
        process.execPath,
        [
          generator,
          "--cancel-baseline",
          baselineCancel,
          "--cancel-candidate",
          candidateCancel,
          "--codec-baseline-dir",
          baselineCodec,
          "--codec-candidate-dir",
          candidateCodec,
          "--output-dir",
          path.join(root, "charts"),
        ],
        { cwd: repositoryRoot },
      ),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /batchSize/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("root Gradle test task forwards sourceCommit to codec benchmark JVM", async () => {
  const buildScript = await readFile(path.join(repositoryRoot, "build.gradle.kts"), "utf8");
  assert.match(
    buildScript,
    /listOf\([\s\S]*"issue34\.codec\.artifact"[\s\S]*"issue34\.sourceCommit"[\s\S]*\)\.forEach/,
  );
});

function cancelReport(mode, p95 = 100, p99 = 200, lockWaitP95 = 20) {
  const environment = {
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
    environmentFingerprint: `${mode}-environment-fingerprint`,
  };
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
      cancelP95Millis: p95,
      cancelP99Millis: p99,
      unexpectedErrorRate: 0.001,
      unintendedRetryExhaustionRate: 0.0001,
      lockWaitP95Millis: lockWaitP95,
      lockWaitSampleQueries: 30,
      lockWaitSampleFailures: 0,
      warmupRequests: 30,
      requests: 100,
      expectedConflictRate: 0.2,
      expectedRetryExhaustionRate: 0.1,
      scenarioMismatchRate: 0,
    })),
  };
}

function codecReport(mode, mix, run) {
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
    },
  };
}

async function assertGeneratorRejectsCancelReport(root, candidateReport, messagePattern) {
  const baselineCancel = path.join(root, "cancel-baseline.json");
  const candidateCancel = path.join(root, "cancel-candidate.json");
  const baselineCodec = path.join(root, "codec-baseline");
  const candidateCodec = path.join(root, "codec-candidate");
  await mkdir(baselineCodec, { recursive: true });
  await mkdir(candidateCodec, { recursive: true });
  await writeFile(baselineCancel, JSON.stringify(cancelReport("baseline")));
  await writeFile(candidateCancel, JSON.stringify(candidateReport));
  for (const mode of ["baseline", "candidate"]) {
    const directory = mode === "baseline" ? baselineCodec : candidateCodec;
    for (const mix of ["legacy-heavy", "current-heavy"]) {
      for (const run of [1, 2, 3]) {
        await writeFile(path.join(directory, `${mode}-${mix}-run${run}.json`), JSON.stringify(codecReport(mode, mix, run)));
      }
    }
  }

  await assert.rejects(
    execFileAsync(process.execPath, [generator, "--cancel-baseline", baselineCancel, "--cancel-candidate", candidateCancel, "--codec-baseline-dir", baselineCodec, "--codec-candidate-dir", candidateCodec, "--output-dir", path.join(root, "charts")], { cwd: repositoryRoot }),
    (error) => {
      assert.notEqual(error.code, 0);
      assert.match(`${error.stdout}\n${error.stderr}`, messagePattern);
      return true;
    },
  );
}
