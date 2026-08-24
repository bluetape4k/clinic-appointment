#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const BENCHMARK_FAMILY =
  "io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotBenchmark";
const OPERATIONS = ["hot-hit", "cold-fill", "invalidation", "cold-start"];
const COLORS = { baseline: "#2563eb", candidate: "#ea580c" };
const LABELS = {
  "hot-hit": "hot-hit 조회",
  "cold-fill": "cold-fill 채우기",
  invalidation: "clinic 무효화",
  "cold-start": "cold-start 생성",
};

try {
  const args = parseArgs(process.argv.slice(2));
  const input = required(args, "input");
  const output = required(args, "output");
  const semanticOutput = required(args, "semantic-output");
  const dataOutput = args["data-output"];
  const report = JSON.parse(await readFile(input, "utf8"));
  const model = validateAndModel(report, input);

  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, renderChart(model) + "\n");
  await mkdir(path.dirname(semanticOutput), { recursive: true });
  await writeFile(semanticOutput, JSON.stringify(model.semantic, null, 2) + "\n");
  if (dataOutput) {
    await mkdir(path.dirname(dataOutput), { recursive: true });
    await writeFile(dataOutput, JSON.stringify(model.data, null, 2) + "\n");
  }
  console.log("Generated " + output);
  console.log("Generated " + semanticOutput);
  if (dataOutput) console.log("Generated " + dataOutput);
  console.log("Issue #313 JDBC Caffeine pilot chart verdict: HOLD");
} catch (error) {
  console.error("Issue #313 JDBC Caffeine pilot chart generation failed: " + error.message);
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
  if (report?.benchmarkFamily !== BENCHMARK_FAMILY) {
    throw new Error("benchmarkFamily must identify the Issue #313 pilot benchmark");
  }
  if (!report.sourceCommit || report.sourceCommit === "unprovided") {
    throw new Error("sourceCommit is required");
  }
  if (report.productionSloEvidence !== false) throw new Error("productionSloEvidence must be false");
  if (report.rawPayloadIncluded !== false) throw new Error("rawPayloadIncluded must be false");
  if (!Number.isInteger(report.warmupRounds) || report.warmupRounds <= 0) {
    throw new Error("warmupRounds must be positive");
  }
  if (!Number.isInteger(report.measurementRounds) || report.measurementRounds <= 4) {
    throw new Error("measurementRounds must be greater than four");
  }
  if (!Array.isArray(report.profiles) || report.profiles.length !== OPERATIONS.length * 2) {
    throw new Error("report must contain baseline and candidate for each operation");
  }
  const profiles = report.profiles.map((profile) => {
    if (!OPERATIONS.includes(profile.name)) throw new Error("unknown operation " + profile.name);
    if (!Object.hasOwn(COLORS, profile.implementation)) {
      throw new Error("unknown implementation " + profile.implementation);
    }
    const p50 = profile.latencyNanos?.p50;
    const p95 = profile.latencyNanos?.p95;
    const allocationP50 = profile.allocationBytes?.p50;
    if (![p50, p95].every((value) => Number.isFinite(value) && value > 0)) {
      throw new Error("latency p50/p95 must be positive for " + profile.name + "/" + profile.implementation);
    }
    if (!(allocationP50 == null || Number.isFinite(allocationP50) && allocationP50 >= 0)) {
      throw new Error("allocation p50 must be non-negative or null");
    }
    return {
      name: profile.name,
      label: LABELS[profile.name],
      implementation: profile.implementation,
      operation: profile.operation,
      latencyP50Nanos: p50,
      latencyP95Nanos: p95,
      allocationP50Bytes: allocationP50,
      sampleCount: profile.latencyNanos?.samples?.length ?? 0,
    };
  });
  for (const operation of OPERATIONS) {
    const group = profiles.filter((profile) => profile.name === operation);
    if (group.length !== 2 || new Set(group.map((profile) => profile.implementation)).size !== 2) {
      throw new Error("operation must have exactly one baseline and one candidate: " + operation);
    }
  }
  const data = {
    schemaVersion: 1,
    issue: 313,
    source: input,
    sourceCommit: report.sourceCommit,
    metric: {
      latencyP50Nanos: { unit: "ns/op", lowerIsBetter: true },
      latencyP95Nanos: { unit: "ns/op", lowerIsBetter: true },
      allocationP50Bytes: { unit: "bytes/op", lowerIsBetter: true },
    },
    warmupRounds: report.warmupRounds,
    measurementRounds: report.measurementRounds,
    productionSloEvidence: false,
    profiles,
  };
  const semantic = {
    kind: "chart",
    source: {
      question: "JDBC Caffeine 후보의 latency·allocation·cold-start 비용이 기존 캐시 대비 adoption을 정당화하는가?",
      revision: report.sourceCommit,
      paths: [input, "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotBenchmark.kt"],
    },
    nodes: profiles.map((profile) => ({
      id: profile.implementation + "-" + profile.name,
      label: profile.implementation + " / " + profile.name,
      source: input,
    })),
    edges: OPERATIONS.map((operation) => ({
      id: operation + "-comparison",
      from: "baseline-" + operation,
      to: "candidate-" + operation,
      kind: "comparison",
      source: input,
    })),
    behavior: { branches: 0, loops: 0 },
    repairs: [],
    chart: {
      pipeline: "svg-to-png",
      scale: "logarithmic",
      units: ["ns/op", "bytes/op"],
      lowerIsBetter: true,
      productionSloEvidence: false,
    },
  };
  return { data, semantic, profiles, warmupRounds: report.warmupRounds, measurementRounds: report.measurementRounds, sourceCommit: report.sourceCommit };
}

function renderChart(model) {
  const width = 1600;
  const height = 1220;
  const latencyProfiles = model.profiles.filter((profile) => profile.latencyP50Nanos > 0);
  const allocationProfiles = model.profiles.filter((profile) => profile.allocationP50Bytes != null);
  const latencyDomain = domain(latencyProfiles.map((profile) => profile.latencyP50Nanos), 100, 500_000);
  const allocationDomain = domain(allocationProfiles.map((profile) => profile.allocationP50Bytes), 1, 50_000);
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<svg xmlns="http://www.w3.org/2000/svg" width="' + width + '" height="' + height + '" viewBox="0 0 ' + width + ' ' + height + '" role="img" aria-labelledby="title desc">',
    '<title id="title">Issue #313 JDBC Caffeine effective policy 파일럿 benchmark</title>',
    '<desc id="desc">기존 EffectivePolicyCache와 test-only JDBC Caffeine 후보의 hot-hit, cold-fill, clinic 무효화, cold-start latency 및 allocation 비교. 로그 스케일이며 production SLO 증거가 아닙니다.</desc>',
    style(),
    '<rect width="' + width + '" height="' + height + '" class="background"/>',
    '<text x="70" y="68" class="title">Issue #313 JDBC Caffeine 정책 캐시 파일럿</text>',
    '<text x="70" y="104" class="subtitle">H2 characterization · warm-up ' + model.warmupRounds + '회 · 측정 ' + model.measurementRounds + '회 · 로그 스케일 · 낮을수록 좋음</text>',
    '<rect x="1110" y="82" width="18" height="18" rx="4" fill="' + COLORS.baseline + '"/>',
    '<text x="1138" y="97" class="legend">기존 EffectivePolicyCache</text>',
    '<rect x="1370" y="82" width="18" height="18" rx="4" fill="' + COLORS.candidate + '"/>',
    '<text x="1398" y="97" class="legend">JDBC Caffeine 후보</text>',
    renderLatencyPanel(model, latencyDomain),
    renderAllocationPanel(model, allocationDomain),
    renderInterpretationBand(model),
    '<text x="70" y="1188" class="footnote">sourceCommit=' + escapeXml(model.sourceCommit) + ' · rawPayloadIncluded=false · productionSloEvidence=false · 기본 판정 HOLD</text>',
    '</svg>',
  ];
  return lines.join("\n");
}

function renderLatencyPanel(model, scale) {
  const x = 70;
  const y = 145;
  const width = 1460;
  const height = 500;
  const plotX = 365;
  const plotY = 270;
  const plotWidth = 1055;
  const rowGap = 86;
  const maxBar = plotX + plotWidth;
  const parts = [
    '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height + '" rx="18" class="panel"/>',
    '<text x="105" y="192" class="panel-title">Latency p50 비교</text>',
    '<text x="105" y="222" class="axis-title">단위: ns/op · 로그 스케일 · p95는 막대 오른쪽에 함께 표시</text>',
  ];
  logTicks(scale).forEach((tick) => {
    const xTick = plotX + scalePosition(tick, scale) * plotWidth;
    parts.push('<line x1="' + fixed(xTick) + '" y1="250" x2="' + fixed(xTick) + '" y2="600" class="grid"/>');
    parts.push('<text x="' + fixed(xTick) + '" y="238" class="axis-value" text-anchor="middle">' + formatCompact(tick) + '</text>');
  });
  OPERATIONS.forEach((operation, index) => {
    const center = plotY + index * rowGap;
    const group = model.profiles.filter((profile) => profile.name === operation);
    parts.push('<text x="105" y="' + fixed(center + 8) + '" class="row-label">' + escapeXml(LABELS[operation]) + '</text>');
    group.forEach((profile, implementationIndex) => {
      const barY = center - 29 + implementationIndex * 34;
      const barWidth = Math.max(5, scalePosition(profile.latencyP50Nanos, scale) * plotWidth);
      parts.push('<rect x="' + plotX + '" y="' + fixed(barY) + '" width="' + fixed(barWidth) + '" height="22" rx="6" fill="' + COLORS[profile.implementation] + '" data-source="' + escapeXml(profile.implementation + "." + profile.name + ".latencyNanos.p50") + '"/>');
      parts.push('<text x="' + fixed(plotX + barWidth + 12) + '" y="' + fixed(barY + 16) + '" class="value">' + formatInteger(profile.latencyP50Nanos) + ' ns · p95 ' + formatInteger(profile.latencyP95Nanos) + '</text>');
    });
  });
  parts.push('<text x="' + maxBar + '" y="620" class="axis-title" text-anchor="end">latency · lower is better</text>');
  return parts.join("");
}

function renderAllocationPanel(model, scale) {
  const x = 70;
  const y = 675;
  const width = 1460;
  const height = 360;
  const plotX = 365;
  const plotY = 775;
  const plotWidth = 1055;
  const rowGap = 60;
  const parts = [
    '<rect x="' + x + '" y="' + y + '" width="' + width + '" height="' + height + '" rx="18" class="panel"/>',
    '<text x="105" y="722" class="panel-title">Allocation p50 비교</text>',
    '<text x="105" y="752" class="axis-title">단위: bytes/op · 로그 스케일 · ThreadMXBean 지원 JVM에서 측정</text>',
  ];
  logTicks(scale).forEach((tick) => {
    const xTick = plotX + scalePosition(tick, scale) * plotWidth;
    parts.push('<line x1="' + fixed(xTick) + '" y1="765" x2="' + fixed(xTick) + '" y2="1000" class="grid"/>');
    parts.push('<text x="' + fixed(xTick) + '" y="' + 755 + '" class="axis-value" text-anchor="middle">' + formatCompact(tick) + '</text>');
  });
  OPERATIONS.forEach((operation, index) => {
    const center = plotY + index * rowGap;
    const group = model.profiles.filter((profile) => profile.name === operation);
    parts.push('<text x="105" y="' + fixed(center + 8) + '" class="row-label">' + escapeXml(LABELS[operation]) + '</text>');
    group.forEach((profile, implementationIndex) => {
      const value = profile.allocationP50Bytes;
      if (value == null) {
        parts.push('<text x="' + plotX + '" y="' + fixed(center + implementationIndex * 30 + 16) + '" class="value">측정 불가</text>');
        return;
      }
      const barY = center - 23 + implementationIndex * 30;
      const barWidth = Math.max(5, scalePosition(value, scale) * plotWidth);
      parts.push('<rect x="' + plotX + '" y="' + fixed(barY) + '" width="' + fixed(barWidth) + '" height="20" rx="6" fill="' + COLORS[profile.implementation] + '" data-source="' + escapeXml(profile.implementation + "." + profile.name + ".allocationBytes.p50") + '"/>');
      parts.push('<text x="' + fixed(plotX + barWidth + 12) + '" y="' + fixed(barY + 15) + '" class="value">' + formatInteger(value) + ' bytes</text>');
    });
  });
  parts.push('<text x="' + (plotX + plotWidth) + '" y="1015" class="axis-title" text-anchor="end">allocation · lower is better</text>');
  return parts.join("");
}

function renderInterpretationBand(model) {
  const candidates = model.profiles.filter((profile) => profile.implementation === "candidate");
  const baselines = model.profiles.filter((profile) => profile.implementation === "baseline");
  const slower = candidates.filter((candidate) => {
    const baseline = baselines.find((profile) => profile.name === candidate.name);
    return baseline && candidate.latencyP50Nanos > baseline.latencyP50Nanos;
  }).length;
  return [
    '<rect x="70" y="1065" width="1460" height="92" rx="18" class="decision"/>',
    '<text x="105" y="1100" class="decision-title">판정: HOLD</text>',
    '<text x="280" y="1100" class="decision-text">후보 latency p50이 ' + slower + '/' + candidates.length + '개 프로필에서 baseline보다 높고, 이 결과는 H2 단일 JVM characterization입니다.</text>',
    '<text x="105" y="1132" class="decision-text">운영 DB·멀티노드·실제 SLO 증거가 없으므로 production EffectivePolicyCache 교체를 승인하지 않습니다.</text>',
  ].join("");
}

function style() {
  return '<style>' +
    '.background{fill:#f8fafc}.panel{fill:#ffffff;stroke:#cbd5e1;stroke-width:2}.decision{fill:#fff7ed;stroke:#fdba74;stroke-width:2}' +
    '.title{font:700 34px "goorm Sans", "Apple SD Gothic Neo", sans-serif;fill:#0f172a}.subtitle,.legend,.axis-title,.axis-value,.footnote,.decision-text{font-family:"goorm Sans", "Apple SD Gothic Neo", sans-serif;fill:#475569}' +
    '.subtitle{font-size:18px}.legend{font-size:15px}.panel-title{font:700 23px "goorm Sans", "Apple SD Gothic Neo", sans-serif;fill:#0f172a}.axis-title{font-size:15px;fill:#64748b}.axis-value{font-size:13px;fill:#64748b}.row-label{font:600 17px "goorm Sans Code", "goorm Sans", monospace;fill:#334155}.value{font:600 14px "goorm Sans Code", monospace;fill:#334155}.grid{stroke:#e2e8f0;stroke-width:1;stroke-dasharray:6 8}.decision-title{font:700 22px "goorm Sans", sans-serif;fill:#9a3412}.decision-text{font-size:16px;fill:#7c2d12}.footnote{font-size:13px;fill:#64748b}' +
    '</style>';
}

function domain(values, minimum, maximum) {
  const finite = values.filter((value) => Number.isFinite(value) && value > 0);
  return { min: Math.min(minimum, ...finite), max: Math.max(maximum, ...finite) };
}

function scalePosition(value, scale) {
  const safe = Math.max(scale.min, Math.min(scale.max, value));
  return (Math.log10(safe) - Math.log10(scale.min)) / (Math.log10(scale.max) - Math.log10(scale.min));
}

function logTicks(scale) {
  return [scale.min, 1_000, 10_000, 100_000, scale.max].filter((value, index, values) =>
    value >= scale.min && value <= scale.max && values.indexOf(value) === index,
  );
}

function formatInteger(value) {
  return Number(value).toLocaleString("en-US", { maximumFractionDigits: 0 });
}

function formatCompact(value) {
  if (value >= 1_000_000) return (value / 1_000_000).toFixed(1) + "M";
  if (value >= 1_000) return (value / 1_000).toFixed(value >= 10_000 ? 0 : 1) + "k";
  return formatInteger(value);
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
