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
