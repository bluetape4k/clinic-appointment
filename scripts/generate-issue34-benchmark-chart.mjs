#!/usr/bin/env node

import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const BENCHMARKS = {
  cancel: "issue-34-patient-appointment-cancel",
  codec: "issue-34-notification-codec-backlog",
};
const CANCEL_ENVIRONMENT_KEYS = [
  "datasetAppointments",
  "warmupSeconds",
  "measureSeconds",
  "sameAppointmentConcurrency",
  "differentAppointmentConcurrency",
  "seed",
  "postgresqlImage",
  "jdk",
  "vm",
];
const CODEC_ENVIRONMENT_KEYS = [
  "database",
  "datasetRows",
  "warmupSeconds",
  "measureSeconds",
  "detailLength",
  "batchSize",
  "legacyRatio",
  "jdk",
  "vm",
];
const MIXES = ["legacy-heavy", "current-heavy"];
const RUNS = [1, 2, 3];

try {
  const args = parseArgs(process.argv.slice(2));
  const inputs = {
    cancelBaseline: required(args, "cancel-baseline"),
    cancelCandidate: required(args, "cancel-candidate"),
    codecBaseline: required(args, "codec-baseline-dir"),
    codecCandidate: required(args, "codec-candidate-dir"),
    outputDir: required(args, "output-dir"),
  };

  const cancel = await readCancelComparison(inputs.cancelBaseline, inputs.cancelCandidate);
  const codec = await readCodecComparison(inputs.codecBaseline, inputs.codecCandidate);
  const summary = buildSummary(cancel, codec, inputs);

  await mkdir(inputs.outputDir, { recursive: true });
  const outputs = [
    ["issue-34-patient-appointment-cancel-latency-ko.svg", renderCancelLatency(cancel)],
    ["issue-34-patient-appointment-cancel-safety-ko.svg", renderCancelSafety(cancel)],
    ["issue-34-notification-codec-latency-ko.svg", renderCodecLatency(codec)],
    ["issue-34-notification-codec-throughput-ko.svg", renderCodecThroughput(codec)],
    ["issue-34-benchmark-analysis.ko.md", renderAnalysis(summary)],
    ["issue-34-benchmark-summary.json", `${JSON.stringify(summary, null, 2)}\n`],
  ];
  for (const [filename, content] of outputs) {
    const output = path.join(inputs.outputDir, filename);
    await writeFile(output, `${content.trimEnd()}\n`);
    console.log(`Generated ${output}`);
  }
  console.log(`Issue #34 benchmark chart verdict: ${summary.verdict}`);
} catch (error) {
  console.error(`Issue #34 benchmark chart generation failed: ${error.message}`);
  process.exitCode = 1;
}

async function readCancelComparison(baselinePath, candidatePath) {
  const baseline = await readJson(baselinePath, "cancel baseline");
  const candidate = await readJson(candidatePath, "cancel candidate");
  validateCancelReport(baseline, "baseline");
  validateCancelReport(candidate, "candidate");
  validateComparableEnvironment(baseline.environment, candidate.environment, CANCEL_ENVIRONMENT_KEYS);
  validateSourceCommits(baseline.environment.sourceCommit, candidate.environment.sourceCommit, "cancel");
  const baselineMedian = summarizeCancel(baseline);
  const candidateMedian = summarizeCancel(candidate);
  return {
    baseline,
    candidate,
    baselineMedian,
    candidateMedian,
    checks: cancelChecksFromMedians(baselineMedian, candidateMedian),
  };
}

async function readCodecComparison(baselineDirectory, candidateDirectory) {
  const baseline = await readCodecReports(baselineDirectory, "baseline");
  const candidate = await readCodecReports(candidateDirectory, "candidate");
  const byMix = {};
  for (const mix of MIXES) {
    const baselineRuns = baseline.filter((report) => report.mix === mix).sort(byRun);
    const candidateRuns = candidate.filter((report) => report.mix === mix).sort(byRun);
    validateComparableEnvironment(
      baselineRuns[0].environment,
      candidateRuns[0].environment,
      CODEC_ENVIRONMENT_KEYS,
    );
    validateEnvironmentWithinRuns(baselineRuns, CODEC_ENVIRONMENT_KEYS, `baseline/${mix}`);
    validateEnvironmentWithinRuns(candidateRuns, CODEC_ENVIRONMENT_KEYS, `candidate/${mix}`);
    validateSourceCommits(
      baselineRuns[0].environment.sourceCommit,
      candidateRuns[0].environment.sourceCommit,
      `codec/${mix}`,
    );
    for (const report of [...baselineRuns, ...candidateRuns]) validateCodecMetrics(report);
    const checks = codecChecks(baselineRuns, candidateRuns);
    byMix[mix] = {
      baselineRuns,
      candidateRuns,
      baselineMedian: summarizeCodec(baselineRuns),
      candidateMedian: summarizeCodec(candidateRuns),
      checks,
      verdict: checks.every((check) => check.passed) ? "PASS" : "FAIL",
    };
  }
  const baselineCommits = new Set(MIXES.map((mix) => byMix[mix].baselineRuns[0].environment.sourceCommit));
  const candidateCommits = new Set(MIXES.map((mix) => byMix[mix].candidateRuns[0].environment.sourceCommit));
  if (baselineCommits.size !== 1 || candidateCommits.size !== 1) {
    throw new Error("codec baseline/candidate sourceCommit must be consistent across mixes");
  }
  return { baseline, candidate, byMix };
}

async function readCodecReports(directory, expectedMode) {
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    throw new Error(`${expectedMode} codec directory cannot be read: ${error.message}`);
  }
  const reports = [];
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith(".json")) continue;
    const file = path.join(directory, entry.name);
    const report = await readJson(file, `${expectedMode} codec report ${entry.name}`);
    if (report.benchmark !== BENCHMARKS.codec) continue;
    if (report.schemaVersion !== 1) throw new Error(`${file}: schemaVersion must be 1`);
    if (report.mode !== expectedMode) throw new Error(`${file}: mode must be ${expectedMode}`);
    if (!MIXES.includes(report.mix)) throw new Error(`${file}: unsupported mix ${report.mix}`);
    if (!RUNS.includes(report.run)) throw new Error(`${file}: run must be 1, 2, or 3`);
    if (!report.environment || typeof report.environment !== "object") {
      throw new Error(`${file}: environment is required`);
    }
    reports.push({ ...report, sourceFile: file });
  }
  if (reports.length !== MIXES.length * RUNS.length) {
    throw new Error(
      `${expectedMode} codec directory must contain six ${BENCHMARKS.codec} reports (two mixes x three runs)`,
    );
  }
  for (const mix of MIXES) {
    const runs = reports.filter((report) => report.mix === mix).map((report) => report.run).sort((a, b) => a - b);
    if (runs.join(",") !== RUNS.join(",")) {
      throw new Error(`${expectedMode}/${mix} codec reports must contain runs 1, 2, and 3 exactly once`);
    }
  }
  return reports;
}

function validateCancelReport(report, expectedMode) {
  if (report?.schemaVersion !== 1 || report?.benchmark !== BENCHMARKS.cancel || report?.mode !== expectedMode) {
    throw new Error(`cancel ${expectedMode} report has an unsupported schema, benchmark, or mode`);
  }
  if (!Array.isArray(report.runs) || report.runs.length !== RUNS.length) {
    throw new Error(`cancel ${expectedMode} report must contain exactly three runs`);
  }
  const runNumbers = report.runs.map((run) => run.run).sort((a, b) => a - b);
  if (runNumbers.join(",") !== RUNS.join(",")) {
    throw new Error(`cancel ${expectedMode} report runs must be numbered 1, 2, and 3 exactly once`);
  }
  if (!report.environment || typeof report.environment !== "object") {
    throw new Error(`cancel ${expectedMode} report must include an environment object`);
  }
  for (const key of CANCEL_ENVIRONMENT_KEYS) {
    if (!(key in report.environment)) throw new Error(`cancel ${expectedMode} environment key ${key} is required`);
  }
  if (report.environment.datasetAppointments !== 100) throw new Error("cancel datasetAppointments must be 100");
  if (report.environment.warmupSeconds !== 30) throw new Error("cancel warmupSeconds must be 30");
  if (report.environment.measureSeconds !== 300) throw new Error("cancel measureSeconds must be 300");
  if (report.environment.sameAppointmentConcurrency !== 10) {
    throw new Error("cancel sameAppointmentConcurrency must be 10");
  }
  if (report.environment.differentAppointmentConcurrency !== 20) {
    throw new Error("cancel differentAppointmentConcurrency must be 20");
  }
  for (const run of report.runs) {
    for (const field of [
      "cancelP95Millis",
      "cancelP99Millis",
      "unexpectedErrorRate",
      "unintendedRetryExhaustionRate",
      "lockWaitP95Millis",
      "expectedConflictRate",
      "expectedRetryExhaustionRate",
      "scenarioMismatchRate",
    ]) {
      nonNegative(run[field], `cancel ${expectedMode} run ${run.run} ${field}`);
    }
    if (run.cancelP99Millis < run.cancelP95Millis) {
      throw new Error(`cancel ${expectedMode} run ${run.run}: p99 must be >= p95`);
    }
  }
}

function validateCodecMetrics(report) {
  const context = `${report.mode}/${report.mix}/run${report.run}`;
  if (report.environment.datasetRows !== 10_000) throw new Error(`${context}: datasetRows must be 10000`);
  if (report.environment.warmupSeconds !== 30) throw new Error(`${context}: warmupSeconds must be 30`);
  if (report.environment.measureSeconds !== 300) throw new Error(`${context}: measureSeconds must be 300`);
  if (report.environment.detailLength !== 15) throw new Error(`${context}: detailLength must be 15`);
  const metrics = report.metrics;
  if (!metrics || typeof metrics !== "object") throw new Error(`${context}: metrics are required`);
  nonNegative(metrics.throughputRowsPerSecond, `${context}: throughputRowsPerSecond`);
  nonNegative(metrics.decodeP95Millis, `${context}: decodeP95Millis`);
  nonNegative(metrics.decodeP99Millis, `${context}: decodeP99Millis`);
  nonNegative(metrics.decodeFailures, `${context}: decodeFailures`);
  nonNegative(metrics.drainTimeMillis, `${context}: drainTimeMillis`);
  nonNegative(metrics.decodedRows, `${context}: decodedRows`);
  nonNegative(metrics.latencySamples, `${context}: latencySamples`);
  nonNegative(metrics.passes, `${context}: passes`);
  if (metrics.throughputRowsPerSecond <= 0) throw new Error(`${context}: throughput must be positive`);
  if (metrics.drainTimeMillis <= 0) throw new Error(`${context}: drain time must be positive`);
  if (metrics.latencySamples <= 0) throw new Error(`${context}: latency samples must be positive`);
  if (metrics.passes <= 0) throw new Error(`${context}: passes must be positive`);
  if (metrics.decodedRows < report.environment.datasetRows) throw new Error(`${context}: rows not drained`);
  if (metrics.decodeP99Millis < metrics.decodeP95Millis) throw new Error(`${context}: p99 must be >= p95`);
}

function validateEnvironmentWithinRuns(reports, keys, label) {
  const first = reports[0].environment;
  for (const report of reports.slice(1)) {
    for (const key of keys) {
      if (report.environment[key] !== first[key]) {
        throw new Error(`${label}: environment key ${key} differs between runs`);
      }
    }
    if (report.environment.sourceCommit !== first.sourceCommit) {
      throw new Error(`${label}: sourceCommit differs between runs`);
    }
  }
}

function validateComparableEnvironment(baseline, candidate, keys) {
  for (const key of keys) {
    if (!(key in baseline) || !(key in candidate)) throw new Error(`environment key ${key} is required in both reports`);
    if (baseline[key] !== candidate[key]) throw new Error(`environment key ${key} differs between baseline and candidate`);
  }
}

function validateSourceCommits(baseline, candidate, scope) {
  for (const [label, value] of [["baseline", baseline], ["candidate", candidate]]) {
    if (typeof value !== "string" || value.trim() === "" || value === "unknown") {
      throw new Error(`${scope} ${label} environment sourceCommit must identify the measured source`);
    }
  }
  if (baseline === candidate) throw new Error(`${scope} baseline and candidate sourceCommit must differ`);
}

function summarizeCancel(report) {
  return medianFields(report.runs, [
    "cancelP95Millis",
    "cancelP99Millis",
    "unexpectedErrorRate",
    "unintendedRetryExhaustionRate",
    "lockWaitP95Millis",
    "expectedConflictRate",
    "expectedRetryExhaustionRate",
    "scenarioMismatchRate",
  ]);
}

function summarizeCodec(reports) {
  return medianFields(reports.map((report) => report.metrics), [
    "throughputRowsPerSecond",
    "decodeP95Millis",
    "decodeP99Millis",
    "decodeFailures",
    "drainTimeMillis",
    "decodedRows",
    "latencySamples",
    "passes",
  ]);
}

function medianFields(rows, fields) {
  return Object.fromEntries(fields.map((field) => [field, median(rows.map((row) => row[field]))]));
}

function cancelChecksFromMedians(baseline, candidate) {
  return [
    checkRelative("취소 p95 상대 회귀", baseline.cancelP95Millis, candidate.cancelP95Millis, 0.1),
    checkRelative("취소 p99 상대 회귀", baseline.cancelP99Millis, candidate.cancelP99Millis, 0.15),
    checkAbsolute("취소 p95 절대 상한", candidate.cancelP95Millis, 500),
    checkAbsolute("취소 p99 절대 상한", candidate.cancelP99Millis, 1000),
    checkAbsolute("예상 밖 오류율", candidate.unexpectedErrorRate, 0.01),
    checkAbsolute("비의도 retry exhaustion 비율", candidate.unintendedRetryExhaustionRate, 0.001),
    checkAbsolute("lock-wait p95", candidate.lockWaitP95Millis, 50),
    checkAbsolute("scenario mismatch 비율", candidate.scenarioMismatchRate, 0),
  ];
}

function codecChecks(baselineRuns, candidateRuns) {
  const baseline = summarizeCodec(baselineRuns);
  const candidate = summarizeCodec(candidateRuns);
  return [
    checkAbsolute("decode p95 절대 상한", candidate.decodeP95Millis, 500),
    checkAbsolute("decode p99 절대 상한", candidate.decodeP99Millis, 1000),
    checkRelative("decode p95 상대 회귀", baseline.decodeP95Millis, candidate.decodeP95Millis, 0.1),
    checkRelative("decode p99 상대 회귀", baseline.decodeP99Millis, candidate.decodeP99Millis, 0.15),
    checkRelativeMin("throughput 상대 회귀", baseline.throughputRowsPerSecond, candidate.throughputRowsPerSecond, 0.1),
    checkRelative("drain-time 상대 회귀", baseline.drainTimeMillis, candidate.drainTimeMillis, 0.1),
    { name: "decode failures", passed: candidate.decodeFailures === 0, actual: candidate.decodeFailures, limit: 0 },
  ];
}

function checkRelative(name, baseline, candidate, budget) {
  const passed = baseline === 0 || candidate <= baseline * (1 + budget);
  return { name, passed, actual: candidate, limit: baseline * (1 + budget), baseline, budget };
}

function checkRelativeMin(name, baseline, candidate, budget) {
  const passed = baseline === 0 || candidate >= baseline * (1 - budget);
  return { name, passed, actual: candidate, limit: baseline * (1 - budget), baseline, budget };
}

function checkAbsolute(name, actual, limit) {
  return { name, passed: actual <= limit, actual, limit };
}

function buildSummary(cancel, codec, inputs) {
  const codecMixes = Object.fromEntries(
    MIXES.map((mix) => {
      const value = codec.byMix[mix];
      return [mix, {
        baseline: value.baselineMedian,
        candidate: value.candidateMedian,
        checks: value.checks,
        verdict: value.checks.every((check) => check.passed) ? "PASS" : "FAIL",
        sourceFiles: [...value.baselineRuns, ...value.candidateRuns].map((report) => relativePath(report.sourceFile)),
      }];
    }),
  );
  const cancelSummary = {
    baseline: cancel.baselineMedian,
    candidate: cancel.candidateMedian,
    checks: cancelChecksFromMedians(cancel.baselineMedian, cancel.candidateMedian),
    verdict: cancelChecksFromMedians(cancel.baselineMedian, cancel.candidateMedian).every((check) => check.passed)
      ? "PASS"
      : "FAIL",
    sourceFiles: [inputs.cancelBaseline, inputs.cancelCandidate].map(relativePath),
  };
  const verdict = cancelSummary.verdict === "PASS" && Object.values(codecMixes).every((mix) => mix.verdict === "PASS")
    ? "PASS"
    : "FAIL";
  return {
    schemaVersion: 1,
    benchmark: "issue-34-patient-commitment",
    verdict,
    deploymentSloEvidence: false,
    cancel: cancelSummary,
    codec: codecMixes,
    sourceCommits: {
      cancel: {
        baseline: cancel.baseline.environment.sourceCommit,
        candidate: cancel.candidate.environment.sourceCommit,
      },
      codec: Object.fromEntries(MIXES.map((mix) => [mix, {
        baseline: codec.byMix[mix].baselineRuns[0].environment.sourceCommit,
        candidate: codec.byMix[mix].candidateRuns[0].environment.sourceCommit,
      }])),
    },
    environments: {
      cancel: cancel.baseline.environment,
      codec: Object.fromEntries(MIXES.map((mix) => [mix, codec.byMix[mix].baselineRuns[0].environment])),
    },
  };
}

function renderCancelLatency(comparison) {
  return renderComparisonChart({
    title: "Issue #34 환자 예약 취소 latency",
    subtitle: environmentSubtitle(comparison.baseline.environment),
    note: "benchmark 근거이며 배포 SLO가 아니다. p95/p99/lock-wait p95는 ms 단위이며 낮을수록 좋다.",
    unit: "밀리초 (ms)",
    metrics: [
      ["취소 p95", "cancelP95Millis"],
      ["취소 p99", "cancelP99Millis"],
      ["lock-wait p95", "lockWaitP95Millis"],
    ],
    baseline: comparison.baselineMedian,
    candidate: comparison.candidateMedian,
    verdict: cancelChecksFromMedians(comparison.baselineMedian, comparison.candidateMedian).every((check) => check.passed)
      ? "PASS"
      : "FAIL",
  });
}

function renderCancelSafety(comparison) {
  return renderComparisonChart({
    title: "Issue #34 환자 예약 취소 시나리오 안전성",
    subtitle: environmentSubtitle(comparison.baseline.environment),
    note: "예상 412와 예상 retry exhaustion은 시나리오 성공이다. 나머지 실패율은 낮을수록 좋다.",
    unit: "비율 (%)",
    percent: true,
    metrics: [
      ["예상 412", "expectedConflictRate"],
      ["예상 retry exhaustion", "expectedRetryExhaustionRate"],
      ["예상 밖 오류", "unexpectedErrorRate"],
      ["비의도 exhaustion", "unintendedRetryExhaustionRate"],
      ["scenario mismatch", "scenarioMismatchRate"],
    ],
    baseline: comparison.baselineMedian,
    candidate: comparison.candidateMedian,
    verdict: cancelChecksFromMedians(comparison.baselineMedian, comparison.candidateMedian).every((check) => check.passed)
      ? "PASS"
      : "FAIL",
  });
}

function renderCodecLatency(codec) {
  const metrics = [];
  for (const mix of MIXES) {
    metrics.push([`${mix} p95`, mix, "decodeP95Millis"]);
    metrics.push([`${mix} p99`, mix, "decodeP99Millis"]);
    metrics.push([`${mix} drain`, mix, "drainTimeMillis"]);
  }
  const baseline = {};
  const candidate = {};
  for (const [label, mix, field] of metrics) {
    baseline[label] = codec.byMix[mix].baselineMedian[field];
    candidate[label] = codec.byMix[mix].candidateMedian[field];
  }
  return renderComparisonChart({
    title: "Issue #34 알림 codec latency",
    subtitle: "legacy-heavy/current-heavy · 10,000 rows · 등록 detail 15자 · warm-up 30초 · 측정 300초",
    note: "benchmark 근거이며 배포 SLO가 아니다. decode p95/p99와 drain time은 ms 단위이며 낮을수록 좋다.",
    unit: "밀리초 (ms)",
    metrics: metrics.map(([label]) => [label, label]),
    baseline,
    candidate,
    verdict: Object.values(codec.byMix).every((mix) => mix.verdict === "PASS") ? "PASS" : "FAIL",
  });
}

function renderCodecThroughput(codec) {
  const baseline = Object.fromEntries(MIXES.map((mix) => [mix, codec.byMix[mix].baselineMedian.throughputRowsPerSecond]));
  const candidate = Object.fromEntries(MIXES.map((mix) => [mix, codec.byMix[mix].candidateMedian.throughputRowsPerSecond]));
  return renderComparisonChart({
    title: "Issue #34 알림 codec throughput",
    subtitle: "legacy-heavy/current-heavy · 10,000 rows · 등록 detail 15자 · warm-up 30초 · 측정 300초",
    note: "benchmark 근거이며 배포 SLO가 아니다. 처리량은 초당 decoded row 수이며 높을수록 좋다.",
    unit: "처리량 (rows/s)",
    metrics: MIXES.map((mix) => [mix, mix]),
    baseline,
    candidate,
    horizontal: true,
    verdict: Object.values(codec.byMix).every((mix) => mix.verdict === "PASS") ? "PASS" : "FAIL",
  });
}

function renderComparisonChart({ title, subtitle, note, unit, metrics, baseline, candidate, percent = false, horizontal = false, verdict }) {
  const width = 1480;
  const height = 720;
  const plot = { left: 190, top: 210, width: 1200, height: 390 };
  const rawValues = metrics.flatMap(([, key]) => [baseline[key], candidate[key]]);
  const values = percent ? rawValues.map((value) => value * 100) : rawValues;
  const maxValue = Math.max(...values, 0);
  const chartMax = maxValue > 0 ? maxValue * 1.25 : 1;
  const gap = 18;
  const grid = horizontal
    ? [0, 0.25, 0.5, 0.75, 1].map((fraction) => {
        const x = plot.left + plot.width * fraction;
        return `<line x1="${fixed(x)}" y1="${plot.top}" x2="${fixed(x)}" y2="${plot.top + plot.height}" class="grid"/><text x="${fixed(x)}" y="${plot.top + plot.height + 25}" class="axis-value" text-anchor="middle">${formatChartValue(chartMax * fraction, percent)}${percent ? "%" : ""}</text>`;
      }).join("")
    : [0, 0.25, 0.5, 0.75, 1].map((fraction) => {
        const y = plot.top + plot.height - plot.height * fraction;
        return `<line x1="${plot.left}" y1="${fixed(y)}" x2="${plot.left + plot.width}" y2="${fixed(y)}" class="grid"/><text x="${plot.left - 18}" y="${fixed(y + 6)}" class="axis-value" text-anchor="end">${formatChartValue(chartMax * fraction, percent)}${percent ? "%" : ""}</text>`;
      }).join("");
  const bars = horizontal
    ? metrics.map(([label, key], index) => {
        const groupHeight = plot.height / metrics.length;
        const groupY = plot.top + groupHeight * index;
        const barHeight = Math.min(54, groupHeight / 4);
        const valuesForMetric = [baseline[key], candidate[key]].map((value) => percent ? value * 100 : value);
        const rendered = valuesForMetric.map((value, seriesIndex) => {
          const totalHeight = barHeight * 2 + gap;
          const y = groupY + groupHeight / 2 - totalHeight / 2 + seriesIndex * (barHeight + gap);
          const barWidth = plot.width * (value / chartMax);
          return `<rect x="${plot.left}" y="${fixed(y)}" width="${fixed(barWidth)}" height="${fixed(barHeight)}" rx="8" class="bar bar-${seriesIndex}"/><text x="${fixed(plot.left + barWidth + 10)}" y="${fixed(y + barHeight - 10)}" class="value">${formatChartValue(value, percent)}${percent ? "%" : ""}</text>`;
        }).join("");
        return `${rendered}<text x="${plot.left - 20}" y="${fixed(groupY + groupHeight / 2 + 6)}" class="group-label" text-anchor="end">${escapeXml(label)}</text>`;
      }).join("")
    : metrics.map(([label, key], index) => {
        const groupWidth = plot.width / metrics.length;
        const barWidth = Math.min(76, groupWidth / 4);
        const groupX = plot.left + groupWidth * index;
        const valuesForMetric = [baseline[key], candidate[key]].map((value) => percent ? value * 100 : value);
        const rendered = valuesForMetric.map((value, seriesIndex) => {
          const x = groupX + groupWidth / 2 - barWidth - gap / 2 + seriesIndex * (barWidth + gap);
          const barHeight = plot.height * (value / chartMax);
          const y = plot.top + plot.height - barHeight;
          return `<rect x="${fixed(x)}" y="${fixed(y)}" width="${fixed(barWidth)}" height="${fixed(barHeight)}" rx="8" class="bar bar-${seriesIndex}"/><text x="${fixed(x + barWidth / 2)}" y="${fixed(Math.max(plot.top + 20, y - 14))}" class="value" text-anchor="middle">${formatChartValue(value, percent)}${percent ? "%" : ""}</text>`;
        }).join("");
        return `${rendered}<text x="${fixed(groupX + groupWidth / 2)}" y="${plot.top + plot.height + 45}" class="group-label" text-anchor="middle">${escapeXml(label)}</text>`;
      }).join("");
  const legend = [
    ["baseline", "bar-0"],
    ["candidate", "bar-1"],
  ].map(([label, color], index) => {
    const x = plot.left + 430 + index * 160;
    return `<rect x="${x}" y="145" width="20" height="20" rx="4" class="${color}"/><text x="${x + 30}" y="162" class="legend-label">${label}</text>`;
  }).join("");
  const titleFont = '"goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", "Arial Unicode MS", sans-serif';
  const bodyFont = '"goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", "Arial Unicode MS", sans-serif';
  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeXml(title)}">
  <title>${escapeXml(title)}</title>
  <desc>${escapeXml(subtitle)}. ${escapeXml(note)}</desc>
  <style>
    .background { fill: #101827; }
    .title { font: 700 38px ${titleFont}; fill: #f6f7fb; }
    .subtitle, .note, .axis-title, .axis-value, .group-label, .legend-label { font-family: ${bodyFont}; fill: #c9d3e5; }
    .subtitle { font-size: 19px; }
    .note { font-size: 17px; fill: #ffcf8a; }
    .axis-title { font-size: 18px; }
    .axis-value { font-size: 15px; }
    .group-label { font-size: 16px; fill: #f6f7fb; font-weight: 700; }
    .legend-label { font-size: 17px; }
    .value { font: 700 15px ${bodyFont}; fill: #f6f7fb; }
    .verdict { font: 700 21px ${bodyFont}; }
    .grid { stroke: #40506a; stroke-width: 1; stroke-dasharray: 6 8; }
    .bar, .bar-0, .bar-1 { stroke: #f6f7fb; stroke-width: 2; }
    .bar-0 { fill: #73d2de; }
    .bar-1 { fill: #f2b880; }
  </style>
  <rect width="${width}" height="${height}" class="background"/>
  <text x="64" y="70" class="title">${escapeXml(title)}</text>
  <text x="64" y="110" class="subtitle">${escapeXml(subtitle)}</text>
  <text x="64" y="143" class="note">${escapeXml(note)}</text>
  <text x="1410" y="72" class="verdict" text-anchor="end" fill="${verdict === "PASS" ? "#9be28f" : "#ff9d9d"}">판정: ${verdict}</text>
  ${legend}
  <text x="${plot.left + plot.width}" y="190" class="axis-title" text-anchor="end">${escapeXml(unit)}</text>
  ${grid}
  ${bars}
</svg>`;
}

function renderAnalysis(summary) {
  const cancel = summary.cancel;
  const codecRows = MIXES.map((mix) => {
    const value = summary.codec[mix];
    return `| ${mix} | ${formatNumber(value.baseline.decodeP95Millis)} / ${formatNumber(value.candidate.decodeP95Millis)} | ${formatNumber(value.baseline.decodeP99Millis)} / ${formatNumber(value.candidate.decodeP99Millis)} | ${formatNumber(value.baseline.throughputRowsPerSecond)} / ${formatNumber(value.candidate.throughputRowsPerSecond)} | ${formatNumber(value.baseline.drainTimeMillis)} / ${formatNumber(value.candidate.drainTimeMillis)} | ${value.verdict} |`;
  }).join("\n");
  const failedChecks = [
    ...cancel.checks,
    ...MIXES.flatMap((mix) => summary.codec[mix].checks),
  ].filter((check) => !check.passed);
  const checkLines = failedChecks.length === 0
    ? "- 모든 상대·절대·오류율 gate 통과"
    : failedChecks.map((check) => `- ${check.name}: 실제 ${formatNumber(check.actual)}, 기준 ${formatNumber(check.limit)}`).join("\n");
  return `# Issue #34 benchmark 결과 분석

> 이 문서는 생성기에 전달한 실제 3회 baseline/candidate artifact에서 계산했다. benchmark 근거이며 배포 SLO가 아니다.

## 판정: ${summary.verdict}

- 배포 SLO 증거: \`${summary.deploymentSloEvidence ? "true" : "false"}\`
- 취소 sourceCommit: baseline \`${summary.sourceCommits.cancel.baseline}\` → candidate \`${summary.sourceCommits.cancel.candidate}\`
- codec sourceCommit: legacy-heavy \`${summary.sourceCommits.codec["legacy-heavy"].baseline}\` → \`${summary.sourceCommits.codec["legacy-heavy"].candidate}\`, current-heavy도 같은 provenance를 사용한다.
- 모든 결과는 각 mode의 3회 측정 median이다.

## PostgreSQL 환자 예약 취소

| 메트릭 | baseline | candidate | 변화율 |
|---|---:|---:|---:|
| cancel p95 (ms) | ${formatNumber(cancel.baseline.cancelP95Millis)} | ${formatNumber(cancel.candidate.cancelP95Millis)} | ${formatDelta(cancel.baseline.cancelP95Millis, cancel.candidate.cancelP95Millis)} |
| cancel p99 (ms) | ${formatNumber(cancel.baseline.cancelP99Millis)} | ${formatNumber(cancel.candidate.cancelP99Millis)} | ${formatDelta(cancel.baseline.cancelP99Millis, cancel.candidate.cancelP99Millis)} |
| lock-wait p95 (ms) | ${formatNumber(cancel.baseline.lockWaitP95Millis)} | ${formatNumber(cancel.candidate.lockWaitP95Millis)} | ${formatDelta(cancel.baseline.lockWaitP95Millis, cancel.candidate.lockWaitP95Millis)} |
| 예상 412 비율 | ${formatPercent(cancel.baseline.expectedConflictRate)} | ${formatPercent(cancel.candidate.expectedConflictRate)} | ${formatDelta(cancel.baseline.expectedConflictRate, cancel.candidate.expectedConflictRate)} |
| 예상 retry exhaustion 비율 | ${formatPercent(cancel.baseline.expectedRetryExhaustionRate)} | ${formatPercent(cancel.candidate.expectedRetryExhaustionRate)} | ${formatDelta(cancel.baseline.expectedRetryExhaustionRate, cancel.candidate.expectedRetryExhaustionRate)} |
| 예상 밖 오류율 | ${formatPercent(cancel.baseline.unexpectedErrorRate)} | ${formatPercent(cancel.candidate.unexpectedErrorRate)} | ${formatDelta(cancel.baseline.unexpectedErrorRate, cancel.candidate.unexpectedErrorRate)} |
| 비의도 retry exhaustion 비율 | ${formatPercent(cancel.baseline.unintendedRetryExhaustionRate)} | ${formatPercent(cancel.candidate.unintendedRetryExhaustionRate)} | ${formatDelta(cancel.baseline.unintendedRetryExhaustionRate, cancel.candidate.unintendedRetryExhaustionRate)} |

취소 gate는 p95 상대 10%, p99 상대 15%, 절대 p95 500ms, p99 1초,
예상 밖 오류율 1%, 비의도 retry exhaustion 0.1%, lock-wait p95 50ms,
scenario mismatch 0을 기준으로 판정한다.

## Notification codec mixed backlog

| mix | decode p95 ms (baseline / candidate) | decode p99 ms (baseline / candidate) | throughput rows/s (baseline / candidate) | drain ms (baseline / candidate) | 판정 |
|---|---:|---:|---:|---:|---|
${codecRows}

codec gate는 decode p95/p99 절대 상한 500ms/1초, 상대 회귀 10%/15%,
throughput 10% 이상 감소 금지, drain time 10% 이상 증가 금지, decode failure 0을 사용한다.

## Gate 상세

${checkLines}

## 해석 규칙

- \`expectedConflictRate\`와 \`expectedRetryExhaustionRate\`는 고정 arrival mix의 의도한 결과다. 오류율과 retry exhaustion gate의 분모에서 제외한다.
- \`sourceCommit\`이 없거나 \`unknown\`이거나 baseline/candidate가 같으면 생성기는 결과를 만들지 않는다.
- 입력 artifact가 없거나 3회·환경·dataset 계약을 만족하지 않으면 이 문서 대신 실행이 실패해야 한다. 현재 저장소의 실측 결과가 없을 때는 이 문서의 템플릿 상태를 유지한다.
- 결과는 로컬 PostgreSQL/H2 harness의 비교 근거이며 보호된 backend E2E, 운영 rollout readiness, production SLO를 증명하지 않는다.

## 재현 명령

\`\`\`bash
node scripts/generate-issue34-benchmark-chart.mjs \\
  --cancel-baseline <cancel-baseline.json> \\
  --cancel-candidate <cancel-candidate.json> \\
  --codec-baseline-dir <codec-baseline-dir> \\
  --codec-candidate-dir <codec-candidate-dir> \\
  --output-dir <output-dir>

scripts/compare-issue34-benchmark.sh <cancel-baseline.json> <cancel-candidate.json>
node scripts/compare-issue34-codec-benchmark.mjs <codec-baseline-dir> <codec-candidate-dir>
\`\`\`
`;
}

function environmentSubtitle(environment) {
  return `${environment.postgresqlImage} · ${environment.datasetAppointments.toLocaleString("ko-KR")}건 · warm-up ${environment.warmupSeconds}초 · 측정 ${environment.measureSeconds}초 · seed ${environment.seed}`;
}

function median(values) {
  return [...values].sort((left, right) => left - right)[Math.floor(values.length / 2)];
}

function byRun(left, right) {
  return left.run - right.run;
}

function nonNegative(value, name) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new Error(`${name} must be a finite non-negative number`);
  }
}

async function readJson(file, label) {
  try {
    return JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    throw new Error(`${label} cannot be read: ${error.message}`);
  }
}

function parseArgs(raw) {
  const result = {};
  for (let index = 0; index < raw.length; index += 1) {
    const token = raw[index];
    if (!token.startsWith("--")) throw new Error(`Unexpected argument: ${token}`);
    const key = token.slice(2);
    if (key in result) throw new Error(`Duplicate argument: --${key}`);
    const value = raw[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`Missing value for --${key}`);
    result[key] = value;
    index += 1;
  }
  return result;
}

function required(values, key) {
  if (!values[key]) throw new Error(`Missing required --${key}`);
  return values[key];
}

function relativePath(file) {
  return path.relative(process.cwd(), file).replaceAll(path.sep, "/");
}

function fixed(value) {
  return Number(value).toFixed(1);
}

function formatNumber(value) {
  return Number(value).toFixed(3);
}

function formatPercent(value) {
  return `${(value * 100).toFixed(3)}%`;
}

function formatChartValue(value, percent) {
  return percent ? Number(value).toFixed(3) : Number(value).toFixed(value >= 100 ? 1 : 3);
}

function formatDelta(baseline, candidate) {
  if (baseline === 0) return candidate === 0 ? "0.000%" : "n/a";
  return `${((candidate / baseline - 1) * 100).toFixed(3)}%`;
}

function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}
