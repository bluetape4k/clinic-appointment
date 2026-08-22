import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { test } from "node:test";

const execFileAsync = promisify(execFile);
const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const validator = path.join(repositoryRoot, "scripts/validate-issue372-redis-key-lifecycle-benchmark.mjs");

test("Issue #372 lifecycle validator accepts a complete lifecycle report", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-372-lifecycle-validator-"));
  try {
    const input = path.join(root, "report.json");
    await writeFile(input, JSON.stringify(report()));
    const result = await execFileAsync(
      process.execPath,
      [validator, "--input", input, "--target-admission-p99-ms", "250", "--target-lifecycle-coverage", "1"],
      { cwd: repositoryRoot },
    );
    assert.match(result.stdout, /"lifecycleObservationCoverage": 1/);
    assert.match(result.stdout, /"targetStatus": "within-target"/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Issue #372 lifecycle validator rejects a missing retention stage", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-372-lifecycle-validator-invalid-"));
  try {
    const input = path.join(root, "invalid.json");
    const invalid = report();
    delete invalid.scenarios[0].lifecycle.afterRetentionWindow;
    await writeFile(input, JSON.stringify(invalid));
    await assert.rejects(
      execFileAsync(process.execPath, [validator, "--input", input], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(error.stderr, /afterRetentionWindow 누락/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

function report() {
  return {
    schemaVersion: 1,
    benchmarkFamily: "io.bluetape4k.clinic.appointment.notification.RedisNotificationKeyLifecycleBenchmark",
    redisImage: "redis:8.8",
    configuration: "smoke",
    sourceCommit: "e3941686888b5bec98baabc61613835cb9412e44",
    environment: { java: "21", os: "Mac OS X", arch: "aarch64" },
    workload: {
      operationsPerRound: 2,
      longRunRounds: 1,
      concurrency: 1,
      actionMillis: 0,
      retentionWaitMillis: 10,
      clinicCardinalities: [10],
      churnRates: [0],
      cacheModes: ["cold"],
    },
    summary: {
      elapsedMillis: 1,
      admissionLatencyMs: { sampleCount: 4, p50: 1, p95: 2, p99: 3 },
      successfulOperations: 4,
      backpressuredOperations: 0,
      lifecycleObservationCoverage: 1,
      requiredLifecycleStages: ["workload-end", "long-run", "after-coordinator-close", "after-retention-window"],
      persistentKeyCountAfterRetentionMax: 5,
    },
    scenarios: [
      {
        name: "cold-cardinality-10-churn-0_0",
        clinicCardinality: 10,
        churnRate: 0,
        cacheMode: "cold",
        operationsPerRound: 2,
        longRunRounds: 1,
        successfulOperations: 4,
        backpressuredOperations: 0,
        warmupMillis: 0,
        workloadElapsedMillis: 1,
        admissionLatencyMs: { sampleCount: 4, p50: 1, p95: 2, p99: 3 },
        uniqueClinicIds: 1,
        lifecycle: {
          workloadEnd: snapshot("workload-end"),
          longRun: [snapshot("long-run-round-1")],
          afterCoordinatorClose: snapshot("after-coordinator-close"),
          afterRetentionWindow: snapshot("after-retention-window"),
          retentionWaitMillis: 10,
        },
      },
    ],
    leaseRecovery: { status: "reacquired", leaseMillis: 1000, reacquireLatencyMs: 1 },
    deploymentSloEvidence: false,
  };
}

function snapshot(stage) {
  return {
    stage,
    keyCount: 5,
    ttlBuckets: { expiring: 0, missing: 0, persistent: 5 },
    keyKinds: { available: 1, capacity: 1, "capacity-contract": 1, generation: 1, requests: 1 },
  };
}
