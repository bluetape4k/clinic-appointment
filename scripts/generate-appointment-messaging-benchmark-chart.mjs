#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const args = parseArgs(process.argv.slice(2));
const input = required(args, "input");
const outputDir = required(args, "output-dir");
const report = JSON.parse(fs.readFileSync(input, "utf8"));
const metrics = [
  ["p50", report.percentiles.p50],
  ["p95", report.percentiles.p95],
  ["p99", report.percentiles.p99],
];
const maxValue = Math.max(...metrics.map(([, value]) => value));
const chartMax = maxValue > 0 ? maxValue * 1.25 : 1;
const width = 1280;
const height = 560;
const plot = { left: 230, top: 150, width: 910, height: 270 };

fs.mkdirSync(outputDir, { recursive: true });
for (const locale of ["en", "ko"]) {
  const strings = locale === "en"
    ? {
        title: "PostgreSQL appointment outbox claim",
        subtitle: `${report.postgresImage} / ${report.rows.toLocaleString("en-US")} rows / seed ${report.seed}`,
        axis: report.scoreUnit,
        note: "Benchmark evidence only; it is not a deployment SLO.",
        aria: "PostgreSQL appointment outbox claim percentile benchmark",
      }
    : {
        title: "PostgreSQL 예약 outbox claim",
        subtitle: `${report.postgresImage} / ${report.rows.toLocaleString("ko-KR")}건 / seed ${report.seed}`,
        axis: report.scoreUnit,
        note: "벤치마크 근거이며 배포 SLO가 아닙니다.",
        aria: "PostgreSQL 예약 outbox claim percentile benchmark",
      };
  const svg = renderSvg(locale, strings, report, metrics, chartMax, width, height, plot);
  const output = path.join(outputDir, `appointment-messaging-postgresql-benchmark-01-${locale}.svg`);
  fs.writeFileSync(output, `${svg}\n`);
  console.log(`Generated ${output}`);
}

function renderSvg(locale, strings, report, metrics, chartMax, width, height, plot) {
  const titleFont = locale === "ko"
    ? '"goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", "Arial Unicode MS", sans-serif'
    : '"Architects Daughter", "Comic Sans MS", sans-serif';
  const bodyFont = locale === "ko"
    ? '"goorm Sans Code", "goorm Sans", "Apple SD Gothic Neo", "Noto Sans KR", "Arial Unicode MS", sans-serif'
    : '"Comic Mono", "SFMono-Regular", monospace';
  const grid = [0, 0.25, 0.5, 0.75, 1]
    .map((fraction) => {
      const y = plot.top + plot.height - plot.height * fraction;
      const value = (chartMax * fraction).toFixed(6);
      return `<line x1="${plot.left}" y1="${y.toFixed(1)}" x2="${plot.left + plot.width}" y2="${y.toFixed(1)}" class="grid"/><text x="${plot.left - 18}" y="${(y + 6).toFixed(1)}" class="axis-value" text-anchor="end">${value}</text>`;
    })
    .join("");
  const bars = metrics.map(([label, value], index) => {
    const barWidth = 180;
    const gap = 95;
    const x = plot.left + 165 + index * (barWidth + gap);
    const barHeight = plot.height * (value / chartMax);
    const y = plot.top + plot.height - barHeight;
    return `<rect x="${x}" y="${y.toFixed(1)}" width="${barWidth}" height="${barHeight.toFixed(1)}" rx="12" class="bar bar-${index}"/><text x="${x + barWidth / 2}" y="${(y - 16).toFixed(1)}" class="value" text-anchor="middle">${value.toFixed(6)}</text><text x="${x + barWidth / 2}" y="${plot.top + plot.height + 52}" class="bar-label" text-anchor="middle">${label}</text>`;
  }).join("");
  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeXml(strings.aria)}">
  <title>${escapeXml(strings.title)}</title>
  <desc>${escapeXml(strings.subtitle)}. ${escapeXml(strings.note)}</desc>
  <style>
    .background { fill: #101827; }
    .title { font: 700 34px ${titleFont}; fill: #f6f7fb; }
    .subtitle, .note, .axis-title, .axis-value, .bar-label { font-family: ${bodyFont}; fill: #c9d3e5; }
    .subtitle { font-size: 18px; }
    .note { font-size: 17px; fill: #ffcf8a; }
    .axis-title { font-size: 18px; }
    .axis-value { font-size: 15px; }
    .bar-label { font-size: 22px; fill: #f6f7fb; font-weight: 700; }
    .value { font: 700 20px ${bodyFont}; fill: #f6f7fb; }
    .grid { stroke: #40506a; stroke-width: 1; stroke-dasharray: 6 8; }
    .bar { stroke: #f6f7fb; stroke-width: 2; }
    .bar-0 { fill: #73d2de; }
    .bar-1 { fill: #f2b880; }
    .bar-2 { fill: #eb6f92; }
  </style>
  <rect width="${width}" height="${height}" rx="26" class="background"/>
  <text x="64" y="66" class="title">${escapeXml(strings.title)}</text>
  <text x="64" y="101" class="subtitle">${escapeXml(strings.subtitle)}</text>
  <text x="64" y="132" class="note">${escapeXml(strings.note)}</text>
  <text x="${plot.left + plot.width}" y="132" class="axis-title" text-anchor="end">${escapeXml(strings.axis)}</text>
  ${grid}
  ${bars}
</svg>`;
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

function escapeXml(value) {
  return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
}

function fail(message) {
  console.error(`chart generation failed: ${message}`);
  process.exit(1);
}
