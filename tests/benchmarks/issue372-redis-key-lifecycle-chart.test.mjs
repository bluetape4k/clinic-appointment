import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { test } from "node:test";

const execFileAsync = promisify(execFile);
const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const generator = path.join(repositoryRoot, "scripts/generate-issue372-redis-key-lifecycle-chart.mjs");
const input = path.join(repositoryRoot, "docs/benchmarks/issue-372-redis-key-lifecycle-benchmark/main.json");

test("Issue #372 chart is generated from the committed lifecycle report", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-372-chart-"));
  try {
    const output = path.join(root, "issue-372-redis-key-lifecycle-chart-ko.svg");
    const { stdout } = await execFileAsync(process.execPath, [generator, "--input", input, "--output", output], {
      cwd: repositoryRoot,
    });
    const chart = await readFile(output, "utf8");
    assert.match(stdout, /within-target/);
    assert.match(chart, /lifecycle coverage/);
    assert.match(chart, /137\.907 ms/);
    assert.match(chart, /1,613\.107/);
    assert.match(chart, /5,405/);
    assert.match(chart, /persistent=-1/);
    assert.match(chart, /data-source="warm-cardinality-1000-churn-1_0\.lifecycle\.afterRetentionWindow"/);
    assert.doesNotMatch(chart, /NaN|undefined/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Issue #372 chart rejects a report without complete lifecycle coverage", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-372-chart-invalid-"));
  try {
    const output = path.join(root, "chart.svg");
    const report = JSON.parse(await readFile(input, "utf8"));
    report.summary.lifecycleObservationCoverage = 0;
    const invalidInput = path.join(root, "invalid.json");
    await writeFile(invalidInput, JSON.stringify(report));
    await assert.rejects(
      execFileAsync(process.execPath, [generator, "--input", invalidInput, "--output", output], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /lifecycleObservationCoverage must be 1/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
