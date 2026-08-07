#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const args = parseArgs(process.argv.slice(2));
const input = required(args, "input");
const outputDir = required(args, "output-dir");
const report = JSON.parse(fs.readFileSync(input, "utf8"));
const throughput = report.measurements.filter((measurement) => measurement.scoreUnit === "ops/ms");
const contention = report.measurements.filter((measurement) => measurement.operation === "duplicateInboxInsertContention");
if (throughput.length !== 4 || contention.length !== 2) fail("consumer chart requires four throughput and two contention measurements");

const width = 1440;
const height = 900;
const panels = [
  { title: "Throughput", note: "bounded cleanup and duplicate lookup", unit: "ops/ms", measurements: throughput, top: 230, height: 260 },
  { title: "Duplicate insert contention", note: "same-key two-participant transaction latency", unit: contention[0].scoreUnit, measurements: contention, top: 630, height: 190 },
];
fs.mkdirSync(outputDir, { recursive: true });
for (const locale of ["en", "ko"]) {
  const strings = locale === "en"
    ? {
        title: "PostgreSQL appointment consumer benchmark",
        subtitle: `${report.postgresImage} / smoke configuration / seed ${report.seed}`,
        note: "Local benchmark evidence only; it is not a deployment SLO.",
        aria: "PostgreSQL appointment consumer throughput and lock contention benchmark",
        labels: { Throughput: "Throughput", "Duplicate insert contention": "Duplicate insert contention", "bounded cleanup": "bounded cleanup", "duplicate lookup": "duplicate lookup", "same-key contention": "same-key contention" },
      }
    : {
        title: "PostgreSQL 예약 consumer benchmark",
        subtitle: `${report.postgresImage} / smoke configuration / seed ${report.seed}`,
        note: "로컬 benchmark 근거이며 배포 SLO가 아닙니다.",
        aria: "PostgreSQL 예약 consumer throughput과 lock contention benchmark",
        labels: { Throughput: "처리량", "Duplicate insert contention": "중복 insert contention", "bounded cleanup": "bounded cleanup", "duplicate lookup": "duplicate lookup", "same-key contention": "동일 key contention" },
      };
  const svg = renderSvg(locale, strings, report, panels, width, height);
  const output = path.join(outputDir, `appointment-messaging-consumer-postgresql-benchmark-01-${locale}.svg`);
  fs.writeFileSync(output, `${svg}\n`);
  console.log(`Generated ${output}`);
}

function renderSvg(locale, strings, report, panels, width, height) {
  const titleFont = locale === "ko"
    ? '"goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", "Arial Unicode MS", sans-serif'
    : '"Architects Daughter", "Comic Sans MS", sans-serif';
  const bodyFont = locale === "ko"
    ? '"goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", "Arial Unicode MS", sans-serif'
    : '"Comic Mono", "SFMono-Regular", monospace';
  const renderedPanels = panels.map((panel) => renderPanel(panel, strings, bodyFont)).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeXml(strings.aria)}">
  <title>${escapeXml(strings.title)}</title>
  <desc>${escapeXml(strings.subtitle)}. ${escapeXml(strings.note)}</desc>
  <style>
    .background { fill: #101827; }
    .title { font: 700 38px ${titleFont}; fill: #f6f7fb; }
    .subtitle, .note, .axis-title, .axis-value, .group-label, .legend-label { font-family: ${bodyFont}; fill: #c9d3e5; }
    .subtitle { font-size: 19px; }
    .note { font-size: 17px; fill: #ffcf8a; }
    .panel-title { font: 700 25px ${titleFont}; fill: #f6f7fb; }
    .panel-note { font: 16px ${bodyFont}; fill: #aab8ce; }
    .axis-title { font-size: 16px; }
    .axis-value { font-size: 14px; }
    .group-label { font-size: 15px; fill: #f6f7fb; font-weight: 700; }
    .legend-label { font-size: 14px; }
    .value { font: 700 13px ${bodyFont}; fill: #f6f7fb; }
    .grid { stroke: #40506a; stroke-width: 1; stroke-dasharray: 6 8; }
    .panel { fill: #172236; stroke: #2f405c; stroke-width: 2; }
    .bar { stroke: #f6f7fb; stroke-width: 1.5; }
    .bar-0 { fill: #73d2de; }
    .bar-1 { fill: #f2b880; }
    .bar-2 { fill: #eb6f92; }
  </style>
  <rect width="${width}" height="${height}" rx="28" class="background"/>
  <text x="64" y="68" class="title">${escapeXml(strings.title)}</text>
  <text x="64" y="106" class="subtitle">${escapeXml(strings.subtitle)}</text>
  <text x="64" y="137" class="note">${escapeXml(strings.note)}</text>
  ${renderedPanels}
</svg>`;
}

function renderPanel(panel, strings, bodyFont) {
  const left = 180;
  const plotLeft = 260;
  const plotWidth = 1060;
  const plotBottom = panel.top + panel.height;
  const maxValue = Math.max(...panel.measurements.flatMap((measurement) => [measurement.percentiles.p50, measurement.percentiles.p95, measurement.percentiles.p99]));
  const chartMax = maxValue > 0 ? maxValue * 1.25 : 1;
  const grid = [0, 0.25, 0.5, 0.75, 1].map((fraction) => {
    const y = plotBottom - panel.height * fraction;
    const value = formatValue(chartMax * fraction);
    return `<line x1="${plotLeft}" y1="${y.toFixed(1)}" x2="${plotLeft + plotWidth}" y2="${y.toFixed(1)}" class="grid"/><text x="${plotLeft - 18}" y="${(y + 5).toFixed(1)}" class="axis-value" text-anchor="end">${value}</text>`;
  }).join("");
  const groupWidth = plotWidth / panel.measurements.length;
  const bars = panel.measurements.map((measurement, groupIndex) => {
    const groupX = plotLeft + groupIndex * groupWidth;
    const barWidth = Math.min(42, groupWidth / 5);
    const gap = 8;
    const values = [measurement.percentiles.p50, measurement.percentiles.p95, measurement.percentiles.p99];
    const barsForMeasurement = values.map((value, barIndex) => {
      const x = groupX + groupWidth / 2 - (barWidth * 1.5 + gap) + barIndex * (barWidth + gap);
      const barHeight = panel.height * (value / chartMax);
      const y = plotBottom - barHeight;
      return `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${barWidth}" height="${barHeight.toFixed(1)}" rx="5" class="bar bar-${barIndex}"/><text x="${(x + barWidth / 2).toFixed(1)}" y="${(y - 8).toFixed(1)}" class="value" text-anchor="middle">${formatValue(value)}</text>`;
    }).join("");
    const label = panel.measurements.length === 2
      ? `${measurement.rows.toLocaleString()} rows`
      : `${strings.labels[measurement.operation === "boundedCleanup" ? "bounded cleanup" : "duplicate lookup"]} / ${measurement.rows.toLocaleString()}`;
    return `${barsForMeasurement}<text x="${(groupX + groupWidth / 2).toFixed(1)}" y="${plotBottom + 37}" class="group-label" text-anchor="middle">${escapeXml(label)}</text>`;
  }).join("");
  const legend = ["p50", "p95", "p99"].map((label, index) => {
    const x = plotLeft + 470 + index * 92;
    return `<rect x="${x}" y="${panel.top - 61}" width="16" height="16" rx="3" class="bar bar-${index}"/><text x="${x + 24}" y="${panel.top - 47}" class="legend-label">${label}</text>`;
  }).join("");
  return `<rect x="${left}" y="${panel.top - 82}" width="1160" height="${panel.height + 132}" rx="20" class="panel"/>
  <text x="${left + 28}" y="${panel.top - 46}" class="panel-title">${escapeXml(strings.labels[panel.title])}</text>
  <text x="${left + 430}" y="${panel.top - 18}" class="panel-note">${escapeXml(panel.note)}</text>
  <text x="${plotLeft + plotWidth}" y="${panel.top - 46}" class="axis-title" text-anchor="end">${escapeXml(panel.unit)}</text>
  ${legend}
  ${grid}
  ${bars}`;
}

function parseArgs(raw) {
  const result = {};
  for (let index = 0; index < raw.length; index += 1) {
    const token = raw[index];
    if (!token.startsWith("--")) fail(`Unexpected argument: ${token}`);
    const key = token.slice(2);
    const value = raw[index + 1];
    if (!value || value.startsWith("--")) fail(`Missing value for --${key}`);
    result[key] = value;
    index += 1;
  }
  return result;
}

function required(values, key) {
  if (!values[key]) fail(`Missing required --${key}`);
  return values[key];
}

function formatValue(value) {
  return value >= 10 ? value.toFixed(1) : value.toFixed(4);
}

function escapeXml(value) {
  return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
}

function fail(message) {
  console.error(`consumer chart generation failed: ${message}`);
  process.exit(1);
}
