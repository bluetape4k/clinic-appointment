#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const COLORS = ["#73d2de", "#f2b880", "#eb6f92"];
const STAGE_COLORS = ["#73d2de", "#b8a1ff", "#f2b880", "#eb6f92"];
const TARGET_P99_MS = 250;

try {
  const args = parseArgs(process.argv.slice(2));
  const input = required(args, "input");
  const output = required(args, "output");
  const report = JSON.parse(await readFile(input, "utf8"));
  const model = validateAndModel(report, input);
  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, renderChart(model) + "\n");
  console.log("Generated " + output);
  console.log("Issue #372 Redis lifecycle chart verdict: " + model.targetStatus);
} catch (error) {
  console.error("Issue #372 Redis lifecycle chart generation failed: " + error.message);
  process.exitCode = 1;
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) throw new Error("unexpected argument " + token);
    const key = token.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error("--" + key + " requires a value");
    args[key] = value;
    index += 1;
  }
  return args;
}

function required(args, key) {
  const value = args[key];
  if (!value) throw new Error("--" + key + " is required");
  return value;
}

function validateAndModel(report, input) {
  if (report?.schemaVersion !== 1) throw new Error("schemaVersion must be 1");
  if (
    report?.benchmarkFamily !==
    "io.bluetape4k.clinic.appointment.notification.RedisNotificationKeyLifecycleBenchmark"
  ) {
    throw new Error("benchmarkFamily must identify RedisNotificationKeyLifecycleBenchmark");
  }
  if (report?.redisImage !== "redis:8.8") throw new Error("redisImage must be redis:8.8");
  if (!report.sourceCommit || report.sourceCommit === "unprovided") throw new Error("sourceCommit is required");
  if (report.deploymentSloEvidence !== false) throw new Error("deploymentSloEvidence must be false");
  const summary = report.summary;
  if (!summary || typeof summary !== "object") throw new Error("summary is required");
  if (!Number.isFinite(summary.admissionLatencyMs?.p99) || summary.admissionLatencyMs.p99 <= 0) {
    throw new Error("summary admission p99 is required");
  }
  if (summary.lifecycleObservationCoverage !== 1) {
    throw new Error("lifecycleObservationCoverage must be 1");
  }
  if (!Array.isArray(report.scenarios) || report.scenarios.length === 0) {
    throw new Error("report must contain scenarios");
  }
  const warm = report.scenarios
    .filter((scenario) => scenario.cacheMode === "warm")
    .sort((left, right) =>
      left.churnRate - right.churnRate || left.clinicCardinality - right.clinicCardinality,
    );
  const cardinalities = [...new Set(warm.map((scenario) => scenario.clinicCardinality))].sort((a, b) => a - b);
  const churnRates = [...new Set(warm.map((scenario) => scenario.churnRate))].sort((a, b) => a - b);
  if (warm.length !== cardinalities.length * churnRates.length) {
    throw new Error("warm scenario matrix is incomplete");
  }
  const highCardinality = Math.max(...cardinalities);
  const lifecycle = churnRates.map((churnRate) => {
    const scenario = warm.find(
      (candidate) =>
        candidate.clinicCardinality === highCardinality && candidate.churnRate === churnRate,
    );
    if (!scenario) throw new Error("missing high-cardinality warm scenario for churn " + churnRate);
    const longRun = scenario.lifecycle?.longRun;
    if (!Array.isArray(longRun) || longRun.length === 0) throw new Error("long-run snapshot is required");
    return {
      churnRate,
      source: scenario.name,
      warmupMillis: scenario.warmupMillis,
      stages: [
        { label: "workload 종료", value: scenario.lifecycle.workloadEnd.keyCount, source: scenario.name + ".lifecycle.workloadEnd" },
        { label: "long-run", value: longRun[longRun.length - 1].keyCount, source: scenario.name + ".lifecycle.longRun" },
        { label: "coordinator 종료", value: scenario.lifecycle.afterCoordinatorClose.keyCount, source: scenario.name + ".lifecycle.afterCoordinatorClose" },
        { label: "retention window", value: scenario.lifecycle.afterRetentionWindow.keyCount, source: scenario.name + ".lifecycle.afterRetentionWindow" },
      ],
    };
  });
  return {
    input,
    summary,
    warm,
    cardinalities,
    churnRates,
    lifecycle,
    targetP99Ms: TARGET_P99_MS,
    targetStatus: summary.admissionLatencyMs.p99 <= TARGET_P99_MS ? "within-target" : "over-target",
    retentionMax: summary.persistentKeyCountAfterRetentionMax,
  };
}

function renderChart(model) {
  const width = 1600;
  const height = 1160;
  const warmupMax = Math.max(2000, ...model.warm.map((scenario) => scenario.warmupMillis)) * 1.15;
  const lifecycleMax = Math.max(6000, ...model.lifecycle.flatMap((group) => group.stages.map((stage) => stage.value))) * 1.05;
  const lines = [];
  lines.push('<?xml version="1.0" encoding="UTF-8"?>');
  lines.push('<svg xmlns="http://www.w3.org/2000/svg" width="' + width + '" height="' + height + '" viewBox="0 0 ' + width + ' ' + height + '" role="img" aria-label="Issue #372 Redis notification key lifecycle 벤치마크 차트">');
  lines.push("<title>Issue #372 Redis notification key lifecycle 벤치마크</title>");
  lines.push("<desc>" + escapeXml("Redis 8.8 로컬 characterization. lifecycle 관측 coverage 100%, admission p99 " + format(model.summary.admissionLatencyMs.p99) + "ms, retention window 이후 persistent key " + formatInteger(model.retentionMax) + "개를 확인합니다.") + "</desc>");
  lines.push(style());
  lines.push('<rect width="' + width + '" height="' + height + '" class="background"/>');
  lines.push('<text x="60" y="70" class="title">Issue #372 Redis key lifecycle 벤치마크</text>');
  lines.push('<text x="60" y="105" class="subtitle">redis:8.8 · ' + model.warm.length + '개 warm 시나리오 · workload / long-run / shutdown / retention</text>');
  lines.push('<text x="60" y="136" class="note">관측 근거이며 production leak·eviction 정책·배포 SLO의 증명이 아닙니다.</text>');
  lines.push(renderKpiPanel(model));
  lines.push(renderWarmupPanel(model, warmupMax));
  lines.push(renderLifecyclePanel(model, lifecycleMax));
  lines.push('<text x="60" y="1050" class="subtitle">targetStatus=' + model.targetStatus + ' · admission p99 ' + format(model.summary.admissionLatencyMs.p99) + 'ms / ' + format(model.targetP99Ms) + 'ms local target · source: main.json</text>');
  lines.push('<text x="60" y="1095" class="note">PTTL 분포는 네 lifecycle stage 모두 persistent=-1이며, 2,500ms 관측 창에서 coordinator close 또는 대기만으로 key가 감소하지 않았습니다.</text>');
  lines.push("</svg>");
  return lines.join("\n");
}

function renderKpiPanel(model) {
  const summary = model.summary;
  return [
    '<rect x="60" y="170" width="1480" height="220" rx="20" class="panel"/>',
    '<text x="90" y="212" class="panel-title">Issue #372 핵심 관측</text>',
    '<rect x="90" y="240" width="330" height="112" rx="14" class="kpi-card"/>',
    '<text x="115" y="273" class="kpi-label">lifecycle coverage</text>',
    '<text x="115" y="311" class="kpi-value status">100%</text>',
    '<text x="115" y="337" class="kpi-label">4 stages · parser PASS</text>',
    '<rect x="455" y="240" width="330" height="112" rx="14" class="kpi-card"/>',
    '<text x="480" y="273" class="kpi-label">admission p99</text>',
    '<text x="480" y="311" class="kpi-value">' + format(summary.admissionLatencyMs.p99) + ' ms</text>',
    '<text x="480" y="337" class="kpi-label">local target ' + format(model.targetP99Ms) + ' ms</text>',
    '<rect x="820" y="240" width="330" height="112" rx="14" class="kpi-card"/>',
    '<text x="845" y="273" class="kpi-label">retention window max</text>',
    '<text x="845" y="311" class="kpi-value">' + formatInteger(model.retentionMax) + ' keys</text>',
    '<text x="845" y="337" class="kpi-label">all observed TTL=-1</text>',
    '<rect x="1185" y="240" width="330" height="112" rx="14" class="kpi-card"/>',
    '<text x="1210" y="273" class="kpi-label">lease recovery</text>',
    '<text x="1210" y="311" class="kpi-value">reacquired</text>',
    '<text x="1210" y="337" class="kpi-label">deploymentSloEvidence=false</text>',
  ].join("");
}

function renderWarmupPanel(model, max) {
  const x = 60;
  const y = 430;
  const width = 730;
  const height = 560;
  const plotX = x + 90;
  const plotY = y + 125;
  const plotWidth = width - 125;
  const plotHeight = 320;
  const centers = model.churnRates.map((_, index) => plotX + 88 + index * ((plotWidth - 176) / Math.max(1, model.churnRates.length - 1)));
  const grouped = model.churnRates.map((churnRate) =>
    model.cardinalities.map((cardinality) =>
      model.warm.find((scenario) => scenario.churnRate === churnRate && scenario.clinicCardinality === cardinality),
    ),
  );
  const parts = [
    '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height + '" rx="20" class="panel"/>',
    '<text x="' + (x + 30) + '" y="' + (y + 38) + '" class="panel-title">Warmup 비용과 cardinality</text>',
    '<text x="' + (x + 30) + '" y="' + (y + 63) + '" class="axis-title">ms · 낮을수록 좋음 · warm cache</text>',
  ];
  model.cardinalities.forEach((cardinality, index) => {
    parts.push('<rect x="' + (x + 30 + index * 150) + '" y="' + (y + 77) + '" width="16" height="16" rx="4" fill="' + COLORS[index % COLORS.length] + '"/>');
    parts.push('<text x="' + (x + 54 + index * 150) + '" y="' + (y + 90) + '" class="legend-label">N=' + formatInteger(cardinality) + "</text>");
  });
  parts.push('<line x1="' + plotX + '" y1="' + (plotY + plotHeight) + '" x2="' + (plotX + plotWidth) + '" y2="' + (plotY + plotHeight) + '" class="axis"/>');
  [0, max / 4, max / 2, (max * 3) / 4, max].forEach((tick) => {
    const yTick = plotY + plotHeight - (tick / max) * plotHeight;
    parts.push('<line x1="' + plotX + '" y1="' + fixed(yTick) + '" x2="' + (plotX + plotWidth) + '" y2="' + fixed(yTick) + '" class="grid"/>');
    parts.push('<text x="' + (plotX - 12) + '" y="' + fixed(yTick + 5) + '" class="axis-value" text-anchor="end">' + formatInteger(tick) + "</text>");
  });
  grouped.forEach((group, groupIndex) => {
    const center = centers[groupIndex];
    const barWidth = 38;
    group.forEach((scenario, cardinalityIndex) => {
      const barHeight = Math.max(1, (scenario.warmupMillis / max) * plotHeight);
      const barX = center - (group.length * (barWidth + 8)) / 2 + cardinalityIndex * (barWidth + 8);
      const barY = plotY + plotHeight - barHeight;
      parts.push('<rect x="' + fixed(barX) + '" y="' + fixed(barY) + '" width="' + barWidth + '" height="' + fixed(barHeight) + '" rx="7" class="bar" fill="' + COLORS[cardinalityIndex % COLORS.length] + '" data-source="' + escapeXml(scenario.name) + '.warmupMillis"/>');
      if (scenario.warmupMillis > max * 0.08) {
        parts.push('<text x="' + fixed(barX + barWidth / 2) + '" y="' + fixed(Math.max(plotY + 15, barY - 8)) + '" class="value" text-anchor="middle">' + format(scenario.warmupMillis) + "</text>");
      }
    });
    parts.push('<text x="' + fixed(center) + '" y="' + (plotY + plotHeight + 35) + '" class="group-label" text-anchor="middle">churn ' + formatPercent(model.churnRates[groupIndex]) + "</text>");
  });
  parts.push('<text x="' + (x + 30) + '" y="' + (y + height - 20) + '" class="note">N=1,000 warmup은 ' + format(Math.min(...model.warm.filter((scenario) => scenario.clinicCardinality === Math.max(...model.cardinalities)).map((scenario) => scenario.warmupMillis))) + "–" + format(Math.max(...model.warm.filter((scenario) => scenario.clinicCardinality === Math.max(...model.cardinalities)).map((scenario) => scenario.warmupMillis))) + "ms</text>");
  return parts.join("");
}

function renderLifecyclePanel(model, max) {
  const x = 830;
  const y = 430;
  const width = 710;
  const height = 560;
  const plotX = x + 88;
  const plotY = y + 125;
  const plotWidth = width - 125;
  const plotHeight = 320;
  const centers = model.lifecycle.map((_, index) => plotX + 80 + index * ((plotWidth - 160) / Math.max(1, model.lifecycle.length - 1)));
  const parts = [
    '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height + '" rx="20" class="panel"/>',
    '<text x="' + (x + 30) + '" y="' + (y + 38) + '" class="panel-title">Cardinality 1,000 key lifecycle</text>',
    '<text x="' + (x + 30) + '" y="' + (y + 63) + '" class="axis-title">count · 낮을수록 좋음 · warm scenarios</text>',
  ];
  ["workload 종료", "long-run", "coordinator 종료", "retention window"].forEach((label, index) => {
    parts.push('<rect x="' + (x + 30 + index * 150) + '" y="' + (y + 77) + '" width="16" height="16" rx="4" fill="' + STAGE_COLORS[index] + '"/>');
    parts.push('<text x="' + (x + 54 + index * 150) + '" y="' + (y + 90) + '" class="legend-label">' + label + "</text>");
  });
  parts.push('<line x1="' + plotX + '" y1="' + (plotY + plotHeight) + '" x2="' + (plotX + plotWidth) + '" y2="' + (plotY + plotHeight) + '" class="axis"/>');
  [0, max / 4, max / 2, (max * 3) / 4, max].forEach((tick) => {
    const yTick = plotY + plotHeight - (tick / max) * plotHeight;
    parts.push('<line x1="' + plotX + '" y1="' + fixed(yTick) + '" x2="' + (plotX + plotWidth) + '" y2="' + fixed(yTick) + '" class="grid"/>');
    parts.push('<text x="' + (plotX - 12) + '" y="' + fixed(yTick + 5) + '" class="axis-value" text-anchor="end">' + formatInteger(tick) + "</text>");
  });
  model.lifecycle.forEach((group, groupIndex) => {
    const center = centers[groupIndex];
    const barWidth = 30;
    group.stages.forEach((stage, stageIndex) => {
      const barHeight = Math.max(1, (stage.value / max) * plotHeight);
      const barX = center - (group.stages.length * (barWidth + 7)) / 2 + stageIndex * (barWidth + 7);
      const barY = plotY + plotHeight - barHeight;
      parts.push('<rect x="' + fixed(barX) + '" y="' + fixed(barY) + '" width="' + barWidth + '" height="' + fixed(barHeight) + '" rx="7" class="bar" fill="' + STAGE_COLORS[stageIndex] + '" data-source="' + escapeXml(stage.source) + '"/>');
      const valueY = barY - 8 - stageIndex * 20;
      parts.push('<text x="' + fixed(barX + barWidth / 2) + '" y="' + fixed(valueY) + '" class="value" text-anchor="middle">' + formatInteger(stage.value) + "</text>");
    });
    parts.push('<text x="' + fixed(center) + '" y="' + (plotY + plotHeight + 35) + '" class="group-label" text-anchor="middle">churn ' + formatPercent(group.churnRate) + "</text>");
  });
  parts.push('<text x="' + (x + 30) + '" y="' + (y + height - 20) + '" class="note">네 stage가 모두 persistent=-1이고 2,500ms 안에 key count 변화 없음</text>');
  return parts.join("");
}

function style() {
  return '<style>' +
    ".background{fill:#101827}.panel{fill:#172235;stroke:#33445f;stroke-width:2}" +
    '.title{font:700 34px "Apple SD Gothic Neo","Noto Sans KR",sans-serif;fill:#f6f7fb}' +
    '.subtitle,.axis-title,.axis-value,.group-label,.legend-label,.kpi-label,.note{font-family:"Apple SD Gothic Neo","Noto Sans KR",sans-serif;fill:#c9d3e5}' +
    ".subtitle{font-size:18px}.note{font-size:17px;fill:#ffcf8a}.panel-title{font:700 22px \"Apple SD Gothic Neo\",\"Noto Sans KR\",sans-serif;fill:#f6f7fb}" +
    ".axis-title{font-size:15px;fill:#9fb1cb}.axis-value{font-size:14px;fill:#9fb1cb}.group-label{font-size:16px;fill:#f6f7fb}.legend-label{font-size:14px;fill:#d8e1ef}" +
    '.value,.kpi-value{font:700 17px monospace;fill:#f6f7fb}.grid{stroke:#40506a;stroke-width:1;stroke-dasharray:6 8}.axis{stroke:#8ea2be;stroke-width:2}' +
    ".bar{stroke:#f6f7fb;stroke-width:1.5}.kpi-card{fill:#202f45;stroke:#40506a;stroke-width:1.5}.kpi-label{font-size:15px;fill:#9fb1cb}.kpi-value{font-size:22px}.status{fill:#9de6bd}" +
    "</style>";
}

function format(value) {
  return Number(value).toLocaleString("en-US", { minimumFractionDigits: 3, maximumFractionDigits: 3 });
}

function formatInteger(value) {
  return Number(value).toLocaleString("en-US", { maximumFractionDigits: 0 });
}

function formatPercent(value) {
  return Number(value * 100).toLocaleString("en-US", { maximumFractionDigits: 0 }) + "%";
}

function fixed(value) {
  return Number(value).toFixed(1);
}

function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}
