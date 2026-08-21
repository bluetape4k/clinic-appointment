#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const CARDINALITIES = [10, 100, 1000];
const CHURNS = [0, 0.5, 1];
const CARDINALITY_COLORS = ["#73d2de", "#f2b880", "#eb6f92"];
const DEFAULT_TARGET_P99_MS = 250;

try {
  const args = parseArgs(process.argv.slice(2));
  const input = required(args, "input");
  const output = required(args, "output");
  const targetP99Ms = numberArg(args, "target-p99-ms", DEFAULT_TARGET_P99_MS);
  const report = JSON.parse(await readFile(input, "utf8"));
  const model = validateAndModel(report, targetP99Ms, input);
  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, `${renderChart(model)}\n`);
  console.log(`Generated ${output}`);
  console.log(`Issue #369 Redis admission chart verdict: ${model.targetStatus}`);
} catch (error) {
  console.error(`Issue #369 Redis admission chart generation failed: ${error.message}`);
  process.exitCode = 1;
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) throw new Error(`unexpected argument ${token}`);
    const key = token.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`--${key} requires a value`);
    args[key] = value;
    index += 1;
  }
  return args;
}

function required(args, key) {
  const value = args[key];
  if (!value) throw new Error(`--${key} is required`);
  return value;
}

function numberArg(args, key, fallback) {
  const value = args[key] === undefined ? fallback : Number(args[key]);
  if (!Number.isFinite(value) || value <= 0) throw new Error(`--${key} must be a positive number`);
  return value;
}

function validateAndModel(report, targetP99Ms, input) {
  if (report?.schemaVersion !== 1) throw new Error("schemaVersion must be 1");
  if (report?.benchmarkFamily !== "io.bluetape4k.clinic.appointment.notification.RedisNotificationAdmissionBenchmark") {
    throw new Error("benchmarkFamily must identify RedisNotificationAdmissionBenchmark");
  }
  if (report?.redisImage !== "redis:8.8") throw new Error("redisImage must be redis:8.8");
  if (!report.sourceCommit || report.sourceCommit === "unknown") throw new Error("sourceCommit is required");
  const summary = report.summary;
  if (!summary || typeof summary !== "object") throw new Error("summary is required");
  const latency = summary.admissionLatencyMs;
  for (const field of ["p50", "p95", "p99"]) positiveNumber(latency?.[field], `summary.admissionLatencyMs.${field}`);
  for (const field of [
    "steadyStateThroughputOpsPerSecond",
    "successfulOperations",
    "backpressuredOperations",
  ]) positiveNumber(summary[field], `summary.${field}`);
  if (!Array.isArray(report.scenarios) || report.scenarios.length !== 18) {
    throw new Error("report must contain exactly 18 scenarios");
  }

  const scenarios = new Map();
  for (const scenario of report.scenarios) {
    if (!CARDINALITIES.includes(scenario.clinicCardinality)) {
      throw new Error(`unsupported clinicCardinality ${scenario.clinicCardinality}`);
    }
    if (!CHURNS.includes(scenario.churnRate)) throw new Error(`unsupported churnRate ${scenario.churnRate}`);
    if (!["cold", "warm"].includes(scenario.cacheMode)) throw new Error(`unsupported cacheMode ${scenario.cacheMode}`);
    const key = `${scenario.cacheMode}/${scenario.clinicCardinality}/${scenario.churnRate}`;
    if (scenarios.has(key)) throw new Error(`duplicate scenario ${key}`);
    positiveNumber(scenario.workloadElapsedMillis, `${key}.workloadElapsedMillis`);
    nonNegativeNumber(scenario.warmupMillis, `${key}.warmupMillis`);
    positiveNumber(scenario.redisKeyCountAfter, `${key}.redisKeyCountAfter`);
    positiveNumber(scenario.admissionLatencyMs?.p99, `${key}.admissionLatencyMs.p99`);
    scenarios.set(key, scenario);
  }

  const warm = CHURNS.map((churnRate) =>
    CARDINALITIES.map((clinicCardinality) => {
      const key = `warm/${clinicCardinality}/${churnRate}`;
      const scenario = scenarios.get(key);
      if (!scenario) throw new Error(`missing scenario ${key}`);
      return {
        clinicCardinality,
        churnRate,
        warmupMillis: scenario.warmupMillis,
        redisKeyCountAfter: scenario.redisKeyCountAfter,
        admissionP99Ms: scenario.admissionLatencyMs.p99,
        source: scenario.name,
      };
    }),
  );

  return {
    input,
    redisImage: report.redisImage,
    summary,
    targetP99Ms,
    targetStatus: latency.p99 <= targetP99Ms ? "within-target" : "over-target",
    warm,
  };
}

function positiveNumber(value, label) {
  if (!Number.isFinite(value) || value <= 0) throw new Error(`${label} must be positive`);
}

function nonNegativeNumber(value, label) {
  if (!Number.isFinite(value) || value < 0) throw new Error(`${label} must be non-negative`);
}

function renderChart(model) {
  const width = 1500;
  const height = 1080;
  const summary = model.summary;
  const targetLabel = `${format(model.targetP99Ms)} ms target`;
  const statusLabel = model.targetStatus === "within-target" ? "targetStatus=within-target" : "targetStatus=over-target";
  const warmupMax = 2000;
  const keyMax = 6000;
  const percentileRows = [
    ["p50", summary.admissionLatencyMs.p50, "#73d2de"],
    ["p95", summary.admissionLatencyMs.p95, "#f2b880"],
    ["p99", summary.admissionLatencyMs.p99, "#eb6f92"],
  ];
  const summaryBars = renderSummaryBars(percentileRows, model.targetP99Ms);
  const warmupPanel = renderGroupedPanel({
    x: 60,
    y: 470,
    width: 680,
    height: 450,
    title: "Warm cache 준비 시간",
    unit: "ms · 낮을수록 좋음",
    max: warmupMax,
    ticks: [0, 500, 1000, 1500, 2000],
    valueKey: "warmupMillis",
    valueFormat: (value) => format(value),
    rows: model.warm,
    note: "cardinality 1,000에서 1.59–1.70s",
  });
  const keyPanel = renderGroupedPanel({
    x: 780,
    y: 470,
    width: 660,
    height: 450,
    title: "Warm 시나리오 종료 후 Redis key",
    unit: "count · 낮을수록 좋음",
    max: keyMax,
    ticks: [0, 1500, 3000, 4500, 6000],
    valueKey: "redisKeyCountAfter",
    valueFormat: (value) => formatInteger(value),
    rows: model.warm,
    note: "cardinality 1,000 / churn 100%: 5,410 keys",
  });

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="Issue #369 Redis notification admission 벤치마크 차트">
  <title>Issue #369 Redis notification admission 벤치마크</title>
  <desc>${escapeXml(`Redis 8.8 local characterization. Admission p99 ${format(summary.admissionLatencyMs.p99)} ms vs ${targetLabel}; warm cardinality and key-count scaling are shown.`)}</desc>
  <style>
    .background { fill: #101827; }
    .panel { fill: #172235; stroke: #33445f; stroke-width: 2; }
    .title { font: 700 34px "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; fill: #f6f7fb; }
    .subtitle, .axis-title, .axis-value, .group-label, .legend-label, .kpi-label, .note, .target-label { font-family: "goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; fill: #c9d3e5; }
    .subtitle { font-size: 18px; }
    .note { font-size: 17px; fill: #ffcf8a; }
    .panel-title { font: 700 22px "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; fill: #f6f7fb; }
    .axis-title { font-size: 15px; fill: #9fb1cb; }
    .axis-value { font-size: 14px; fill: #9fb1cb; }
    .group-label { font-size: 16px; fill: #f6f7fb; }
    .legend-label { font-size: 14px; fill: #d8e1ef; }
    .group-values { font: 11px "goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; fill: #9fb1cb; }
    .bar-label { font: 700 17px "goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; fill: #f6f7fb; }
    .value { font: 700 18px "goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; fill: #f6f7fb; }
    .grid { stroke: #40506a; stroke-width: 1; stroke-dasharray: 6 8; }
    .axis { stroke: #8ea2be; stroke-width: 2; }
    .target { stroke: #ff8f70; stroke-width: 2; stroke-dasharray: 8 8; }
    .target-label { font-size: 14px; fill: #ffb39f; }
    .bar { stroke: #f6f7fb; stroke-width: 1.5; }
    .kpi-card { fill: #202f45; stroke: #40506a; stroke-width: 1.5; }
    .kpi-label { font-size: 15px; fill: #9fb1cb; }
    .kpi-value { font: 700 22px "goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; fill: #f6f7fb; }
    .status { fill: #9de6bd; }
  </style>
  <rect width="${width}" height="${height}" class="background"/>
  <text x="60" y="70" class="title">Issue #369 Redis notification admission 벤치마크</text>
  <text x="60" y="105" class="subtitle">${escapeXml(model.redisImage)} · 18개 시나리오 · cold / warm · 로컬 characterization</text>
  <text x="60" y="136" class="note">벤치마크 근거이며 배포 SLO가 아닙니다.</text>

  <rect x="60" y="170" width="1380" height="250" rx="20" class="panel"/>
  <text x="90" y="212" class="panel-title">Admission latency percentile</text>
  <text x="90" y="238" class="axis-title">ms · 낮을수록 좋음</text>
  ${summaryBars}
  <rect x="1100" y="218" width="300" height="178" rx="14" class="kpi-card"/>
  <text x="1125" y="251" class="kpi-label">steady-state throughput</text>
  <text x="1125" y="281" class="kpi-value">${format(summary.steadyStateThroughputOpsPerSecond)} ops/s</text>
  <text x="1125" y="316" class="kpi-label">lease recovery</text>
  <text x="1125" y="345" class="kpi-value">reacquired</text>
  <text x="1125" y="374" class="kpi-label">${formatInteger(summary.successfulOperations)} success · ${formatInteger(summary.backpressuredOperations)} backpressure</text>

  ${warmupPanel}
  ${keyPanel}
  <text x="60" y="970" class="subtitle">${escapeXml(statusLabel)} · admission p99 ${format(summary.admissionLatencyMs.p99)} ms / ${targetLabel} · source: main.json</text>
  <text x="60" y="1012" class="note">warmup은 workload elapsed와 분리된 준비 비용이며, key count는 시나리오 종료 직후 관측값입니다.</text>
</svg>`;
}

function renderSummaryBars(rows, target) {
  const plotX = 280;
  const plotY = 260;
  const plotWidth = 760;
  const rowGap = 44;
  const max = target * 1.1;
  const ticks = [0, target / 2, target, max];
  const grid = ticks
    .map((tick) => {
      const x = plotX + (tick / max) * plotWidth;
      return `<line x1="${x.toFixed(1)}" y1="246" x2="${x.toFixed(1)}" y2="390" class="grid"/><text x="${x.toFixed(1)}" y="406" class="axis-value" text-anchor="middle">${format(tick)}</text>`;
    })
    .join("");
  const targetX = plotX + (target / max) * plotWidth;
  const bars = rows
    .map(([label, value, color], index) => {
      const y = plotY + index * rowGap;
      const width = Math.max(1, (value / max) * plotWidth);
      return `<text x="${plotX - 18}" y="${y + 20}" class="bar-label" text-anchor="end">${label}</text><rect x="${plotX}" y="${y}" width="${width.toFixed(1)}" height="26" rx="9" class="bar" fill="${color}" data-source="summary.admissionLatencyMs.${label}"/><text x="${Math.min(plotX + width + 12, 1060).toFixed(1)}" y="${y + 20}" class="value">${format(value)} ms</text>`;
    })
    .join("");
  return `${grid}<line x1="${targetX.toFixed(1)}" y1="246" x2="${targetX.toFixed(1)}" y2="390" class="target"/><text x="${targetX.toFixed(1)}" y="238" class="target-label" text-anchor="middle">${format(target)} ms target</text>${bars}`;
}

function renderGroupedPanel({ x, y, width, height, title, unit, max, ticks, valueKey, valueFormat, rows, note }) {
  const plotX = x + 86;
  const plotY = y + 112;
  const plotWidth = width - 124;
  const plotHeight = 235;
  const groupCenters = [plotX + 80, plotX + plotWidth / 2, plotX + plotWidth - 80];
  const groupWidth = 144;
  const barWidth = 31;
  const barGap = 8;
  const grid = ticks
    .map((tick) => {
      const yTick = plotY + plotHeight - (tick / max) * plotHeight;
      return `<line x1="${plotX}" y1="${yTick.toFixed(1)}" x2="${(plotX + plotWidth).toFixed(1)}" y2="${yTick.toFixed(1)}" class="grid"/><text x="${plotX - 12}" y="${(yTick + 5).toFixed(1)}" class="axis-value" text-anchor="end">${formatInteger(tick)}</text>`;
    })
    .join("");
  const bars = rows
    .map((group, groupIndex) =>
      group
        .map((row, cardinalityIndex) => {
          const value = row[valueKey];
          const barHeight = Math.max(1, (value / max) * plotHeight);
          const barX = groupCenters[groupIndex] - groupWidth / 2 + cardinalityIndex * (barWidth + barGap);
          const barY = plotY + plotHeight - barHeight;
          const labelX = barX + barWidth / 2;
          const labelY = Math.max(plotY - 10, barY - 8);
          const label = value < max * 0.2
            ? ""
            : `<text x="${labelX.toFixed(1)}" y="${labelY.toFixed(1)}" class="value" text-anchor="middle">${valueFormat(value)}</text>`;
          return `<rect x="${barX.toFixed(1)}" y="${barY.toFixed(1)}" width="${barWidth}" height="${barHeight.toFixed(1)}" rx="7" class="bar" fill="${CARDINALITY_COLORS[cardinalityIndex]}" data-source="${escapeXml(row.source)}"/>${label}`;
        })
        .join("") +
      `<text x="${groupCenters[groupIndex]}" y="${plotY + plotHeight + 34}" class="group-label" text-anchor="middle">churn ${formatPercent(CHURNS[groupIndex])}</text>${group.map((row, cardinalityIndex) => `<text x="${(groupCenters[groupIndex] - groupWidth / 2 - 2).toFixed(1)}" y="${plotY + plotHeight + 52 + cardinalityIndex * 14}" class="group-values">${formatInteger(row.clinicCardinality)}=${valueFormat(row[valueKey])}</text>`).join("")}`,
    )
    .join("");
  const legend = CARDINALITIES.map(
    (cardinality, index) => `<rect x="${x + 30 + index * 130}" y="${y + 66}" width="16" height="16" rx="4" fill="${CARDINALITY_COLORS[index]}"/><text x="${x + 54 + index * 130}" y="${y + 79}" class="legend-label">N=${formatInteger(cardinality)}</text>`,
  ).join("");
  return `<rect x="${x}" y="${y}" width="${width}" height="${height}" rx="20" class="panel"/><text x="${x + 30}" y="${y + 38}" class="panel-title">${escapeXml(title)}</text><text x="${x + 30}" y="${y + 60}" class="axis-title">${escapeXml(unit)}</text>${legend}<line x1="${plotX}" y1="${plotY + plotHeight}" x2="${plotX + plotWidth}" y2="${plotY + plotHeight}" class="axis"/>${grid}${bars}<text x="${x + 30}" y="${y + height - 10}" class="note">${escapeXml(note)}</text>`;
}

function format(value) {
  return Number(value).toLocaleString("en-US", { minimumFractionDigits: 3, maximumFractionDigits: 3 });
}

function formatInteger(value) {
  return Number(value).toLocaleString("en-US", { maximumFractionDigits: 0 });
}

function formatPercent(value) {
  return `${Math.round(value * 100)}%`;
}

function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}
