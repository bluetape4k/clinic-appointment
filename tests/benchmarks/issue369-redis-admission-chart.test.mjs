import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { test } from "node:test";

const execFileAsync = promisify(execFile);
const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const generator = path.join(repositoryRoot, "scripts/generate-issue369-redis-admission-chart.mjs");
const input = path.join(repositoryRoot, "docs/benchmarks/issue-369-redis-admission-benchmark/main.json");

test("Issue #369 chart is generated from the committed main benchmark report", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-369-chart-"));
  try {
    const output = path.join(root, "issue-369-redis-admission-summary-ko.svg");
    const { stdout } = await execFileAsync(process.execPath, [generator, "--input", input, "--output", output], {
      cwd: repositoryRoot,
    });
    const chart = await readFile(output, "utf8");
    assert.match(stdout, /within-target/);
    assert.match(chart, /Admission latency percentile/);
    assert.match(chart, /138\.923 ms/);
    assert.match(chart, /1,701\.328/);
    assert.match(chart, /5,410/);
    assert.doesNotMatch(chart, /NaN|undefined/);
    assert.match(chart, /data-source="warm-cardinality-1000-churn-1_0"/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Issue #369 chart rejects a report with an incomplete scenario matrix", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "issue-369-chart-invalid-"));
  try {
    const output = path.join(root, "chart.svg");
    const report = JSON.parse(await readFile(input, "utf8"));
    report.scenarios.pop();
    const invalidInput = path.join(root, "invalid.json");
    await writeFile(invalidInput, JSON.stringify(report));
    await assert.rejects(
      execFileAsync(process.execPath, [generator, "--input", invalidInput, "--output", output], { cwd: repositoryRoot }),
      (error) => {
        assert.notEqual(error.code, 0);
        assert.match(`${error.stdout}\n${error.stderr}`, /exactly 18 scenarios/);
        return true;
      },
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
