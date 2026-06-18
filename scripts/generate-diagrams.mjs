#!/usr/bin/env node
/**
 * clinic-appointment README diagram generator
 * Generates architecture, ERD, state machine, sequence, and flow diagrams
 * for all modules following the bluetape4k diagram visual language.
 *
 * Usage: node scripts/generate-diagrams.mjs [--module <name>]
 */

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname ?? process.cwd(), "..");
const outDir = path.join(root, "docs/images/readme-diagrams");
const chartDir = path.join(root, "docs/images/readme-charts");

// ─── Fonts ────────────────────────────────────────────────────────────────────
const TITLE_FONT = "Architects Daughter";
const DETAIL_FONT = "Comic Mono";
const FONT_DIR = `${process.env.HOME}/Library/Fonts`;
const TITLE_FONT_FILE = path.join(FONT_DIR, "ArchitectsDaughter-Regular.ttf");
const DETAIL_FONT_FILE = path.join(FONT_DIR, "ComicMono.ttf");

// ─── Palette ──────────────────────────────────────────────────────────────────
const P = {
  blue:   { bg: "#E8F3FF", stroke: "#5B8DEF", text: "#1A3A5C" },
  green:  { bg: "#EAF7EF", stroke: "#58A978", text: "#1A3A26" },
  teal:   { bg: "#E9F7F6", stroke: "#45A7A1", text: "#0E3533" },
  amber:  { bg: "#FFF3D9", stroke: "#D6A441", text: "#4A3000" },
  pink:   { bg: "#FDECEF", stroke: "#DC6B82", text: "#4A0E1C" },
  purple: { bg: "#F1ECFF", stroke: "#8A72D6", text: "#2A1A5C" },
  olive:  { bg: "#EEF6D9", stroke: "#8BA84D", text: "#2A3A0E" },
  gray:   { bg: "#F2F5F9", stroke: "#9AA8B8", text: "#2A3340" },
  canvas: "#F7F9FC",
  frame:  "#FFFFFF",
  frameStroke: "#D0D8E4",
};

// ─── Helpers ──────────────────────────────────────────────────────────────────
function esc(text) {
  return String(text ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function fontconfigEnv() {
  const tmpCfg = `/tmp/clinic_fc_${Date.now()}.conf`;
  const titlePath = TITLE_FONT_FILE.replace(/&/g, "&amp;");
  const detailPath = DETAIL_FONT_FILE.replace(/&/g, "&amp;");
  fs.writeFileSync(tmpCfg, `<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>${FONT_DIR}</dir>
  <alias><family>Architects Daughter</family><prefer><family>Architects Daughter</family></prefer></alias>
  <alias><family>Comic Mono</family><prefer><family>Comic Mono</family></prefer></alias>
</fontconfig>`);
  return { FONTCONFIG_FILE: tmpCfg };
}

function run(cmd, args, cwd = root) {
  const result = spawnSync(cmd, args, {
    cwd,
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, ...fontconfigEnv() },
  });
  return {
    ok: result.status === 0,
    stdout: result.stdout?.toString() ?? "",
    stderr: result.stderr?.toString() ?? "",
  };
}

function renderPng(svgPath, pngPath) {
  // Try rsvg-convert first, then ImageMagick convert
  let r = run("rsvg-convert", ["-o", pngPath, svgPath]);
  if (!r.ok) {
    r = run("convert", ["-density", "192", svgPath, pngPath]);
  }
  if (!r.ok) {
    r = run("/opt/homebrew/bin/convert", ["-density", "192", svgPath, pngPath]);
  }
  if (!r.ok) {
    // Try sips as last resort
    r = run("sips", ["-s", "format", "png", svgPath, "--out", pngPath]);
  }
  return r.ok;
}

function dotRun(dotContent, fmt, outPath) {
  const tmpDot = `/tmp/clinic_dot_${Date.now()}.dot`;
  fs.writeFileSync(tmpDot, dotContent);
  const r = run("/opt/homebrew/bin/dot", ["-T" + fmt, tmpDot, "-o", outPath]);
  try { fs.unlinkSync(tmpDot); } catch {}
  return r;
}

function saveSvgPng(slug, svgContent, dir = outDir) {
  fs.mkdirSync(dir, { recursive: true });
  const svgPath = path.join(dir, `${slug}.svg`);
  const pngPath = path.join(dir, `${slug}.png`);
  fs.writeFileSync(svgPath, svgContent, "utf8");
  const ok = renderPng(svgPath, pngPath);
  const status = ok ? "✓" : "⚠ PNG failed";
  console.log(`  ${status}  ${slug}.svg + .png`);
  return { svgPath, pngPath, ok };
}

// ─── SVG Building blocks ───────────────────────────────────────────────────────
function svgHeader(w, h, title) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="${esc(title)}">
<defs>
  <filter id="shadow" x="-8%" y="-8%" width="116%" height="116%">
    <feDropShadow dx="0" dy="4" stdDeviation="6" flood-color="#1f2937" flood-opacity="0.10"/>
  </filter>
  <marker id="arr" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth">
    <path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="#758297"/>
  </marker>
  <marker id="arr-blue" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth">
    <path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="${P.blue.stroke}"/>
  </marker>
  <marker id="arr-green" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth">
    <path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="${P.green.stroke}"/>
  </marker>
  <marker id="arr-amber" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth">
    <path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="${P.amber.stroke}"/>
  </marker>
  <marker id="arr-pink" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth">
    <path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="${P.pink.stroke}"/>
  </marker>
  <marker id="arr-purple" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth">
    <path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="${P.purple.stroke}"/>
  </marker>
  <marker id="arr-teal" markerWidth="5" markerHeight="5" refX="4.5" refY="2.5" orient="auto" markerUnits="strokeWidth">
    <path d="M 0.5 0.5 L 4.5 2.5 L 0.5 4.5 Z" fill="${P.teal.stroke}"/>
  </marker>
  <marker id="inherit" markerWidth="14" markerHeight="12" refX="12" refY="6" orient="auto" markerUnits="strokeWidth">
    <path d="M 0 1 L 12 6 L 0 11 Z" fill="#ffffff" stroke="#758297" stroke-width="1.5"/>
  </marker>
  <style>
    .canvas{fill:${P.canvas}}
    .frame{fill:${P.frame};stroke:${P.frameStroke};stroke-width:1.5}
    .title{font-family:"Architects Daughter","Comic Sans MS",cursive;font-size:40px;fill:#102033}
    .subtitle{font-family:"Comic Mono","Comic Sans MS",cursive;font-size:13px;fill:#536273}
    .label{font-family:"Architects Daughter","Comic Sans MS",cursive;font-size:22px;fill:#102033}
    .smallLabel{font-family:"Architects Daughter","Comic Sans MS",cursive;font-size:17px;fill:#102033}
    .mono{font-family:"Comic Mono","Comic Sans MS",cursive;font-size:12px;fill:#102033}
    .small{font-family:"Comic Mono","Comic Sans MS",cursive;font-size:11px;fill:#536273}
    .layerTitle{font-family:"Architects Daughter","Comic Sans MS",cursive;font-size:14px;fill:#536273;font-style:italic}
    .card{stroke-width:2;filter:url(#shadow)}
    .line{stroke:#758297;stroke-width:2;fill:none;marker-end:url(#arr)}
    .line-blue{stroke:${P.blue.stroke};stroke-width:2;fill:none;marker-end:url(#arr-blue)}
    .line-green{stroke:${P.green.stroke};stroke-width:2;fill:none;marker-end:url(#arr-green)}
    .line-amber{stroke:${P.amber.stroke};stroke-width:2;fill:none;marker-end:url(#arr-amber)}
    .line-pink{stroke:${P.pink.stroke};stroke-width:2;fill:none;marker-end:url(#arr-pink)}
    .line-purple{stroke:${P.purple.stroke};stroke-width:2;fill:none;marker-end:url(#arr-purple)}
    .line-teal{stroke:${P.teal.stroke};stroke-width:2;fill:none;marker-end:url(#arr-teal)}
    .dashed{stroke:#758297;stroke-width:1.8;stroke-dasharray:7 5;fill:none;marker-end:url(#arr)}
    .dashed-blue{stroke:${P.blue.stroke};stroke-width:1.8;stroke-dasharray:7 5;fill:none;marker-end:url(#arr-blue)}
    .dashed-green{stroke:${P.green.stroke};stroke-width:1.8;stroke-dasharray:7 5;fill:none;marker-end:url(#arr-green)}
    .dashed-amber{stroke:${P.amber.stroke};stroke-width:1.8;stroke-dasharray:7 5;fill:none;marker-end:url(#arr-amber)}
    .dashed-pink{stroke:${P.pink.stroke};stroke-width:1.8;stroke-dasharray:7 5;fill:none;marker-end:url(#arr-pink)}
    .dashed-purple{stroke:${P.purple.stroke};stroke-width:1.8;stroke-dasharray:7 5;fill:none;marker-end:url(#arr-purple)}
    .dashed-teal{stroke:${P.teal.stroke};stroke-width:1.8;stroke-dasharray:7 5;fill:none;marker-end:url(#arr-teal)}
    .inheritLine{stroke:#758297;stroke-width:2;fill:none;marker-end:url(#inherit)}
  </style>
</defs>
<rect class="canvas" width="${w}" height="${h}"/>
<rect class="frame" x="28" y="24" width="${w - 56}" height="${h - 48}" rx="22"/>`;
}

function svgFooter(w, h, repo, module) {
  const fy = h - 28;
  return `<text class="small" x="${w / 2}" y="${fy}" text-anchor="middle">${esc(repo)}${module ? ` · ${esc(module)}` : ""} · github.com/bluetape4k/clinic-appointment</text>
</svg>`;
}

function card(x, y, w, h, title, details, color, rx = 10) {
  const c = P[color] ?? P.blue;
  const titleLines = Array.isArray(title) ? title : [title];
  const detailLines = Array.isArray(details) ? details : details ? [details] : [];
  const totalLines = titleLines.length + detailLines.length;
  const lineH = 16;
  const totalH = totalLines * lineH + 16;
  const startY = y + (h - totalH) / 2 + lineH;
  let out = `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${c.bg}" stroke="${c.stroke}" class="card"/>`;
  let cy = startY;
  for (const tl of titleLines) {
    out += `<text class="smallLabel" x="${x + w / 2}" y="${cy}" text-anchor="middle" dominant-baseline="middle">${esc(tl)}</text>`;
    cy += lineH + 2;
  }
  for (const dl of detailLines) {
    out += `<text class="mono" x="${x + w / 2}" y="${cy}" text-anchor="middle" dominant-baseline="middle" fill="${c.text}" opacity="0.75">${esc(dl)}</text>`;
    cy += lineH;
  }
  return out;
}

function layerBand(x, y, w, h, label, color) {
  const c = P[color] ?? P.gray;
  return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="14" fill="${c.bg}" stroke="${c.stroke}" stroke-width="1" opacity="0.5"/>
<text class="layerTitle" x="${x + 14}" y="${y + 20}" dominant-baseline="middle">${esc(label)}</text>`;
}

function line(x1, y1, x2, y2, cls = "line") {
  return `<path class="${cls}" d="M${x1} ${y1} L${x2} ${y2}"/>`;
}

function path90(x1, y1, x2, y2, cls = "line") {
  const mx = (x1 + x2) / 2;
  return `<path class="${cls}" d="M${x1} ${y1} L${mx} ${y1} L${mx} ${y2} L${x2} ${y2}"/>`;
}

function viaBottom(x1, y1, x2, y2, cls = "line") {
  return `<path class="${cls}" d="M${x1} ${y1} L${x1} ${y2} L${x2} ${y2}"/>`;
}

function viaTop(x1, y1, x2, y2, cls = "line") {
  return `<path class="${cls}" d="M${x1} ${y1} L${x2} ${y1} L${x2} ${y2}"/>`;
}

function lineLabel(lx, ly, text, color = "#536273") {
  return `<rect x="${lx - 3}" y="${ly - 11}" width="${text.length * 7 + 8}" height="15" rx="4" fill="white" stroke="${color}" stroke-width="0.8"/>
<text class="small" x="${lx + 1}" y="${ly}" fill="${color}">${esc(text)}</text>`;
}

// ─── Diagram: Root Architecture ───────────────────────────────────────────────
function generateRootArchitecture() {
  const W = 1100, H = 640;
  const parts = [];
  parts.push(svgHeader(W, H, "Clinic Appointment — System Architecture"));
  parts.push(`<text class="title" x="62" y="78">Clinic Appointment</text>`);
  parts.push(`<text class="subtitle" x="66" y="102">System Architecture — Kotlin 2.3 · Spring Boot 4 · Timefold Solver · Redis</text>`);

  // Layer bands
  parts.push(layerBand(48, 118, W - 96, 72, "Client / Frontend", "blue"));
  parts.push(layerBand(48, 202, W - 96, 72, "API Layer", "green"));
  parts.push(layerBand(48, 286, W - 96, 72, "Domain Logic", "teal"));
  parts.push(layerBand(48, 370, W - 96, 72, "AI Optimization", "amber"));
  parts.push(layerBand(48, 454, W - 96, 72, "Notification & HA", "pink"));
  parts.push(layerBand(48, 538, W - 96, 74, "Infrastructure", "gray"));

  // Row 1: Client
  parts.push(card(68, 130, 200, 50, "Angular 18 SPA", ["appointment-frontend"], "blue"));
  parts.push(card(286, 130, 200, 50, "REST Client", ["HTTP / JWT Bearer"], "blue"));
  parts.push(card(504, 130, 200, 50, "Swagger UI", ["springdoc-openapi"], "blue"));
  parts.push(card(722, 130, 200, 50, "Gatling Tests", ["load simulation"], "blue"));

  // Row 2: API
  parts.push(card(68, 214, 380, 50, "appointment-api", ["Spring Boot 4 MVC · JWT · Flyway"], "green"));
  parts.push(card(466, 214, 200, 50, "SecurityConfig", ["JwtAuthenticationFilter"], "green"));
  parts.push(card(684, 214, 220, 50, "GlobalExceptionHandler", ["ApiResponse envelope"], "green"));

  // Row 3: Domain
  parts.push(card(68, 298, 420, 50, "appointment-core", ["16 entities · Exposed ORM · State Machine · Slot Calc"], "teal"));
  parts.push(card(506, 298, 280, 50, "appointment-event", ["Spring ApplicationEvent · EventLog"], "teal"));

  // Row 4: AI
  parts.push(card(68, 382, 420, 50, "appointment-solver", ["Timefold Solver · 11 Hard + 2 Soft constraints"], "amber"));
  parts.push(card(506, 382, 280, 50, "SolverService", ["bulk optimization · SolutionConverter"], "amber"));

  // Row 5: Notification
  parts.push(card(68, 466, 300, 50, "appointment-notification", ["HA Scheduler · Reminder"], "pink"));
  parts.push(card(386, 466, 200, 50, "Redis Leader Election", ["bluetape4k-leader"], "pink"));
  parts.push(card(604, 466, 200, 50, "Resilience4j", ["CircuitBreaker · Retry · Bulkhead"], "pink"));

  // Row 6: Infra
  parts.push(card(68, 554, 180, 46, "PostgreSQL", ["Exposed JDBC · Flyway"], "gray"));
  parts.push(card(262, 554, 160, 46, "Redis", ["Leader Election · Cache"], "gray"));
  parts.push(card(438, 554, 160, 46, "Docker", ["Testcontainers"], "gray"));
  parts.push(card(614, 554, 180, 46, "Micrometer", ["Observability"], "gray"));
  parts.push(card(810, 554, 160, 46, "Timefold AI", ["Solver Engine"], "gray"));

  // Connectors
  // Frontend → API
  parts.push(line(168, 180, 168, 214, "line-green"));
  // API → Core
  parts.push(line(258, 264, 258, 298, "line-teal"));
  // API → Event
  parts.push(viaTop(448, 264, 646, 298, "line-teal"));
  // Core → Solver
  parts.push(line(258, 348, 258, 382, "line-amber"));
  // Core → Notification (via left)
  parts.push(viaBottom(468, 348, 218, 466, "line-pink"));
  // Notification → Redis
  parts.push(line(368, 491, 386, 491, "line-pink"));
  // Notification → Resilience4j
  parts.push(line(586, 491, 604, 491, "line-pink"));
  // API → PostgreSQL
  parts.push(viaBottom(258, 264, 158, 554, "dashed"));
  // API → Redis
  parts.push(viaBottom(350, 264, 342, 554, "dashed"));

  parts.push(svgFooter(W, H, "clinic-appointment"));
  saveSvgPng("clinic-appointment-architecture-01", parts.join("\n"), path.join(root, "docs/assets/readme-diagrams"));
}

// ─── Diagram: Root Module Overview ────────────────────────────────────────────
function generateRootModuleOverview() {
  const W = 1060, H = 560;
  const parts = [];
  parts.push(svgHeader(W, H, "Clinic Appointment — Module Overview"));
  parts.push(`<text class="title" x="62" y="78">Module Overview</text>`);
  parts.push(`<text class="subtitle" x="66" y="100">clinic-appointment · Gradle multi-module project</text>`);

  // Module boxes in dependency order
  const modules = [
    { id: "core",   x: 380, y: 140, w: 300, h: 80, title: "appointment-core", details: ["Domain model, Exposed ORM", "16 entities, State machine", "SlotCalculationService"], color: "teal" },
    { id: "event",  x: 68,  y: 280, w: 280, h: 80, title: "appointment-event", details: ["Spring ApplicationEvent", "Domain event publishing", "EventLog persistence"], color: "blue" },
    { id: "solver", x: 388, y: 280, w: 280, h: 80, title: "appointment-solver", details: ["Timefold Solver AI", "11 hard + 2 soft constraints", "Bulk optimization"], color: "amber" },
    { id: "notif",  x: 710, y: 280, w: 280, h: 80, title: "appointment-notification", details: ["HA notification scheduler", "Redis Leader Election", "Resilience4j guards"], color: "pink" },
    { id: "api",    x: 240, y: 420, w: 560, h: 80, title: "appointment-api", details: ["Spring Boot 4 REST API", "JWT auth · Flyway · Swagger", "Gatling load tests"], color: "green" },
    { id: "fe",     x: 710, y: 140, w: 280, h: 80, title: "frontend", details: ["appointment-frontend", "Angular 18 SPA", "appointment management UI"], color: "purple" },
  ];

  for (const m of modules) {
    parts.push(card(m.x, m.y, m.w, m.h, m.title, m.details, m.color));
  }

  // Dependency arrows (→ means "depends on")
  // event → core
  parts.push(viaTop(208, 280, 530, 220, "line-teal"));
  parts.push(lineLabel(330, 244, "depends on"));
  // solver → core
  parts.push(line(528, 280, 528, 220, "line-teal"));
  // notif → core
  parts.push(viaTop(850, 280, 530, 220, "line-teal"));
  // api → core (via center)
  parts.push(viaBottom(520, 420, 530, 360, "line-teal"));
  // api → event
  parts.push(viaBottom(380, 420, 208, 360, "line-blue"));
  // api → solver
  parts.push(line(520, 420, 528, 360, "line-amber"));
  // api → notif (via right)
  parts.push(viaBottom(660, 420, 850, 360, "line-pink"));
  // frontend → api
  parts.push(viaTop(850, 140, 520, 420, "dashed-purple"));
  parts.push(lineLabel(700, 280, "HTTP REST"));

  parts.push(svgFooter(W, H, "clinic-appointment"));
  saveSvgPng("root-readme-overview-01", parts.join("\n"), path.join(root, "docs/assets/readme-diagrams"));
}

// ─── Diagram: appointment-core ERD ────────────────────────────────────────────
function generateCoreErd() {
  const W = 1200, H = 780;
  const parts = [];
  parts.push(svgHeader(W, H, "appointment-core — Entity Relationship Diagram"));
  parts.push(`<text class="title" x="62" y="78">Domain Entity Relationships</text>`);
  parts.push(`<text class="subtitle" x="66" y="100">appointment-core · 16 entities · scheduling_* tables · Exposed ORM</text>`);

  function erdTable(x, y, w, name, pk, cols, color) {
    const c = P[color] ?? P.blue;
    const rowH = 20;
    const headerH = 30;
    const totalH = headerH + (cols.length + 1) * rowH + 8;
    let out = `<rect x="${x}" y="${y}" width="${w}" height="${totalH}" rx="6" fill="${c.bg}" stroke="${c.stroke}" stroke-width="2" filter="url(#shadow)"/>`;
    // Header
    out += `<rect x="${x}" y="${y}" width="${w}" height="${headerH}" rx="6" fill="${c.stroke}" stroke="${c.stroke}" stroke-width="2"/>`;
    out += `<rect x="${x}" y="${y + headerH - 6}" width="${w}" height="6" fill="${c.stroke}"/>`;
    out += `<text class="smallLabel" x="${x + w / 2}" y="${y + headerH / 2 + 1}" text-anchor="middle" dominant-baseline="middle" fill="white">${esc(name)}</text>`;
    // PK row
    out += `<text class="mono" x="${x + 8}" y="${y + headerH + rowH / 2 + 4}" dominant-baseline="middle" fill="${c.text}">🔑 ${esc(pk)}</text>`;
    let ry = y + headerH + rowH + 2;
    for (const col of cols) {
      const isFk = col.startsWith("⟶");
      out += `<text class="small" x="${x + 10}" y="${ry + rowH / 2}" dominant-baseline="middle" fill="${isFk ? c.stroke : "#536273"}">${esc(col)}</text>`;
      ry += rowH;
    }
    return out;
  }

  // Clinics (central)
  parts.push(erdTable(440, 130, 240, "scheduling_clinics", "id: Long", [
    "tenant_group_id ⟶ TenantGroups",
    "name, timezone, locale",
    "slot_duration_minutes",
    "max_concurrent_patients",
    "open_on_holidays",
  ], "teal"));

  // Doctors (left of Clinics)
  parts.push(erdTable(150, 120, 240, "scheduling_doctors", "id: Long", [
    "⟶ clinic_id",
    "name, specialty",
    "provider_type",
    "max_concurrent_patients",
  ], "blue"));

  // TreatmentTypes (right of Clinics)
  parts.push(erdTable(730, 120, 240, "scheduling_treatment_types", "id: Long", [
    "⟶ clinic_id",
    "name, category",
    "default_duration_minutes",
    "required_provider_type",
    "requires_equipment",
  ], "green"));

  // Equipments (far right)
  parts.push(erdTable(1000, 120, 180, "scheduling_equipments", "id: Long", [
    "⟶ clinic_id",
    "name",
    "usage_duration_minutes",
    "quantity",
  ], "olive"));

  // Appointments (center, below)
  parts.push(erdTable(360, 320, 480, "scheduling_appointments", "id: Long", [
    "⟶ clinic_id, doctor_id",
    "⟶ treatment_type_id, equipment_id",
    "patient_name, patient_phone",
    "appointment_date, start_time, end_time",
    "status (AppointmentState)",
    "reschedule_from_id",
  ], "pink"));

  // OperatingHours
  parts.push(erdTable(50, 320, 240, "scheduling_operating_hours", "id: Long", [
    "⟶ clinic_id",
    "day_of_week",
    "open_time, close_time",
    "is_active",
  ], "amber"));

  // DoctorSchedules
  parts.push(erdTable(50, 490, 240, "scheduling_doctor_schedules", "id: Long", [
    "⟶ doctor_id",
    "day_of_week",
    "start_time, end_time",
  ], "blue"));

  // DoctorAbsences
  parts.push(erdTable(50, 610, 240, "scheduling_doctor_absences", "id: Long", [
    "⟶ doctor_id",
    "absence_date",
    "start_time, end_time (opt)",
  ], "blue"));

  // ClinicClosures
  parts.push(erdTable(780, 320, 240, "scheduling_clinic_closures", "id: Long", [
    "⟶ clinic_id",
    "closure_date",
    "is_full_day",
    "start_time, end_time (opt)",
  ], "amber"));

  // Holidays
  parts.push(erdTable(780, 480, 240, "scheduling_holidays", "id: Long", [
    "holiday_date",
    "name",
    "is_recurring",
  ], "amber"));

  // EquipmentUnavailabilities
  parts.push(erdTable(780, 590, 240, "scheduling_equipment_unavailabilities", "id: Long", [
    "⟶ equipment_id",
    "start_datetime, end_datetime",
    "recurrence_rule",
  ], "olive"));

  // RescheduleCandidates
  parts.push(erdTable(360, 580, 360, "scheduling_reschedule_candidates", "id: Long", [
    "⟶ appointment_id",
    "⟶ clinic_id",
    "proposed_date, proposed_start_time",
    "status, score",
  ], "purple"));

  // AppointmentStateHistory
  parts.push(erdTable(360, 700, 360, "scheduling_appointment_state_history", "id: Long", [
    "⟶ appointment_id",
    "from_state, to_state, event",
    "occurred_at",
  ], "gray"));

  // FK connectors
  // Doctors → Clinics
  parts.push(line(390, 160, 440, 160, "line-teal"));
  // TreatmentTypes → Clinics
  parts.push(line(730, 160, 680, 160, "line-teal"));
  // Equipments → Clinics
  parts.push(viaTop(1090, 120, 560, 130, "line-teal"));
  // OperatingHours → Clinics
  parts.push(viaTop(170, 320, 560, 260, "line-teal"));
  // Appointments → Clinics
  parts.push(line(600, 320, 600, 260, "line-teal"));
  // Appointments → Doctors
  parts.push(viaBottom(360, 360, 270, 260, "line-blue"));
  // Appointments → TreatmentTypes
  parts.push(viaBottom(840, 360, 850, 260, "line-green"));
  // Appointments → Equipments
  parts.push(viaBottom(840, 380, 1090, 260, "line-olive"));
  // DoctorSchedules → Doctors
  parts.push(line(170, 490, 170, 380, "line-blue"));
  // DoctorAbsences → Doctors
  parts.push(viaTop(170, 610, 170, 380, "line-blue"));
  // ClinicClosures → Clinics
  parts.push(line(900, 320, 780, 260, "line-amber"));
  // EquipmentUnavailabilities → Equipments
  parts.push(viaTop(900, 590, 1090, 260, "line-olive"));
  // RescheduleCandidates → Appointments
  parts.push(line(540, 580, 540, 500, "line-pink"));
  // StateHistory → Appointments
  parts.push(line(540, 700, 540, 500, "line-gray"));

  parts.push(svgFooter(W, H, "appointment-core", "scheduling_* tables"));
  saveSvgPng("appointment-core-erd-01", parts.join("\n"), outDir);
}

// ─── Diagram: State Machine ────────────────────────────────────────────────────
function generateStateMachine() {
  const W = 1000, H = 680;
  const parts = [];
  parts.push(svgHeader(W, H, "Appointment State Machine"));
  parts.push(`<text class="title" x="62" y="78">Appointment State Machine</text>`);
  parts.push(`<text class="subtitle" x="66" y="100">10 states · 11 transitions · AppointmentStateMachine.kt</text>`);

  function stateNode(x, y, w, h, name, label, color) {
    return card(x, y, w, h, name, label, color);
  }

  // States layout
  const SH = 52, SW = 170;
  // Column layout:
  // Col 0: PENDING (top)
  // Col 1: REQUESTED → CONFIRMED
  // Col 2: CHECKED_IN → IN_PROGRESS → COMPLETED
  // Side: NO_SHOW, CANCELLED, PENDING_RESCHEDULE, RESCHEDULED

  parts.push(stateNode(410, 130, SW, SH, "PENDING", ["가예약 / 미확정"], "gray"));
  parts.push(stateNode(410, 230, SW, SH, "REQUESTED", ["예약 요청"], "blue"));
  parts.push(stateNode(410, 330, SW, SH, "CONFIRMED", ["예약 확정"], "green"));
  parts.push(stateNode(410, 430, SW, SH, "CHECKED_IN", ["내원 확인"], "teal"));
  parts.push(stateNode(410, 530, SW, SH, "IN_PROGRESS", ["진료 중"], "amber"));
  parts.push(stateNode(410, 610, SW, SH, "COMPLETED", ["진료 완료"], "green"));

  // Side states
  parts.push(stateNode(660, 310, SW, SH, "NO_SHOW", ["미내원"], "pink"));
  parts.push(stateNode(660, 430, SW, SH, "PENDING_RESCHEDULE", ["재배정 대기"], "purple"));
  parts.push(stateNode(660, 530, SW, SH, "RESCHEDULED", ["재배정 완료"], "teal"));
  parts.push(stateNode(150, 500, SW + 10, SH, "CANCELLED", ["취소 (모든 상태에서 가능)"], "pink"));

  // Normal transitions (left column, going down)
  parts.push(line(495, 182, 495, 230, "line-blue"));
  parts.push(lineLabel(500, 206, "Request"));

  parts.push(line(495, 282, 495, 330, "line-green"));
  parts.push(lineLabel(500, 306, "Confirm"));

  parts.push(line(495, 382, 495, 430, "line-teal"));
  parts.push(lineLabel(500, 406, "CheckIn"));

  parts.push(line(495, 482, 495, 530, "line-amber"));
  parts.push(lineLabel(500, 506, "StartTreatment"));

  parts.push(line(495, 582, 495, 610, "line-green"));
  parts.push(lineLabel(500, 596, "Complete"));

  // CONFIRMED → NO_SHOW
  parts.push(viaTop(580, 356, 660, 310, "line-pink"));
  parts.push(lineLabel(620, 320, "MarkNoShow"));

  // CONFIRMED → PENDING_RESCHEDULE
  parts.push(line(580, 360, 660, 430, "line-purple"));
  parts.push(lineLabel(605, 394, "RequestReschedule"));

  // REQUESTED → PENDING_RESCHEDULE
  parts.push(viaTop(580, 254, 660, 430, "line-purple"));

  // PENDING_RESCHEDULE → RESCHEDULED
  parts.push(line(745, 482, 745, 530, "line-teal"));
  parts.push(lineLabel(750, 506, "ConfirmReschedule"));

  // CANCELLED from various states (left side)
  parts.push(viaBottom(410, 254, 260, 500, "line-pink"));
  parts.push(viaBottom(410, 354, 240, 500, "dashed-pink"));
  parts.push(viaBottom(410, 454, 220, 500, "dashed-pink"));
  parts.push(viaBottom(660, 454, 260, 526, "dashed-pink"));
  parts.push(lineLabel(140, 430, "Cancel"));

  // CONFIRMED → PENDING (reschedule back to pending)
  parts.push(viaTop(410, 354, 390, 130, "dashed-blue"));
  parts.push(lineLabel(330, 240, "Reschedule"));

  // Pinned status indicator
  parts.push(`<rect x="795" y="310" width="185" height="130" rx="8" fill="${P.amber.bg}" stroke="${P.amber.stroke}" stroke-width="1.5"/>`)
  parts.push(`<text class="layerTitle" x="887" y="332" text-anchor="middle" dominant-baseline="middle">Pinned States</text>`);
  parts.push(`<text class="small" x="807" y="352" fill="${P.amber.text}">CONFIRMED, CHECKED_IN</text>`);
  parts.push(`<text class="small" x="807" y="370" fill="${P.amber.text}">IN_PROGRESS, COMPLETED</text>`);
  parts.push(`<text class="small" x="807" y="390" fill="${P.amber.stroke}">→ Solver cannot move</text>`);
  parts.push(`<text class="small" x="807" y="410" fill="${P.teal.stroke}">Movable: REQUESTED,</text>`);
  parts.push(`<text class="small" x="807" y="428" fill="${P.teal.stroke}">PENDING_RESCHEDULE</text>`);

  parts.push(svgFooter(W, H, "appointment-core", "AppointmentStateMachine.kt"));
  saveSvgPng("appointment-core-architecture-02", parts.join("\n"), outDir);
}

// ─── Diagram: appointment-event Flow ──────────────────────────────────────────
function generateEventFlow() {
  const W = 920, H = 560;
  const parts = [];
  parts.push(svgHeader(W, H, "appointment-event — Domain Event Flow"));
  parts.push(`<text class="title" x="62" y="78">Domain Event Flow</text>`);
  parts.push(`<text class="subtitle" x="66" y="100">appointment-event · Spring ApplicationEvent · EventLog persistence</text>`);

  // Publisher side
  parts.push(layerBand(48, 118, 380, 380, "Publishers", "green"));
  parts.push(layerBand(458, 118, 414, 180, "Event Bus", "blue"));
  parts.push(layerBand(458, 318, 414, 180, "Subscribers", "teal"));

  // Publisher cards
  parts.push(card(68, 148, 280, 64, "AppointmentController", ["POST /appointments", "PATCH .../status", "DELETE .../cancel"], "green"));
  parts.push(card(68, 228, 280, 64, "RescheduleController", ["POST .../reschedule", "batch reschedule stream"], "green"));
  parts.push(card(68, 308, 280, 64, "SlotController", ["slot query triggers", "availability events"], "green"));
  parts.push(card(68, 392, 280, 64, "Domain Services", ["ClosureRescheduleService", "ConcurrencyResolver"], "teal"));

  // Events
  parts.push(card(478, 148, 180, 52, "Created", ["appointmentId, clinicId"], "blue"));
  parts.push(card(678, 148, 180, 52, "StatusChanged", ["fromState → toState"], "blue"));
  parts.push(card(478, 218, 180, 52, "Cancelled", ["appointmentId, reason"], "pink"));
  parts.push(card(678, 218, 180, 52, "Rescheduled", ["originalId, newId"], "teal"));

  // Subscribers
  parts.push(card(478, 348, 180, 64, "AppointmentEventLogger", ["@EventListener", "persists to EventLog DB"], "teal"));
  parts.push(card(678, 348, 180, 64, "NotificationEventListener", ["@EventListener", "calls NotificationChannel"], "pink"));
  parts.push(card(478, 432, 180, 52, "AppointmentEventLogs", ["Exposed table", "event_type, payload_json"], "gray"));

  // Connectors
  // Publishers → Event types
  parts.push(line(348, 180, 478, 174, "line-blue"));
  parts.push(line(348, 200, 678, 174, "line-blue"));
  parts.push(line(348, 260, 478, 244, "line-pink"));
  parts.push(line(348, 294, 678, 244, "line-teal"));
  parts.push(line(348, 424, 478, 244, "dashed-pink"));

  // Events → EventBus → Subscribers
  parts.push(line(568, 200, 568, 348, "line-teal"));
  parts.push(line(768, 200, 768, 348, "line-pink"));

  // EventLogger → EventLog table
  parts.push(line(568, 412, 568, 432, "line-gray"));

  // Label
  parts.push(lineLabel(570, 300, "Spring ApplicationEvent"));

  parts.push(svgFooter(W, H, "appointment-event"));
  saveSvgPng("appointment-event-architecture-01", parts.join("\n"), outDir);
}

// ─── Diagram: Solver Data Flow ─────────────────────────────────────────────────
function generateSolverDataFlow() {
  const W = 1060, H = 600;
  const parts = [];
  parts.push(svgHeader(W, H, "appointment-solver — Timefold Solver Data Flow"));
  parts.push(`<text class="title" x="62" y="78">Solver Data Flow</text>`);
  parts.push(`<text class="subtitle" x="66" y="100">appointment-solver · Timefold AI · 11 Hard + 2 Soft constraints</text>`);

  // Layers
  parts.push(layerBand(48, 118, W - 96, 68, "Input — Load from DB", "blue"));
  parts.push(layerBand(48, 200, W - 96, 68, "Planning Domain", "teal"));
  parts.push(layerBand(48, 282, W - 96, 68, "Constraint Evaluation", "amber"));
  parts.push(layerBand(48, 364, W - 96, 68, "Timefold Solver Engine", "green"));
  parts.push(layerBand(48, 446, W - 96, 68, "Output — Write Results", "purple"));

  // Row 1: Input
  parts.push(card(68, 132, 220, 50, "SolverService.solve()", ["clinicId, appointmentIds", "dateRange"], "blue"));
  parts.push(card(310, 132, 200, 50, "AppointmentRepository", ["load REQUESTED &", "PENDING_RESCHEDULE"], "blue"));
  parts.push(card(530, 132, 200, 50, "Problem Facts", ["Doctors, Clinics", "ClosureRecords, Holidays", "EquipUnavailabilities"], "blue"));
  parts.push(card(750, 132, 200, 50, "SolutionConverter", ["DB records →", "AppointmentPlanning"], "blue"));

  // Row 2: Planning Domain
  parts.push(card(68, 214, 300, 50, "AppointmentPlanning (@PlanningEntity)", ["doctorId, appointmentDate", "startTime = planning variables", "Pinned if CONFIRMED+"], "teal"));
  parts.push(card(390, 214, 300, 50, "ScheduleSolution (@PlanningSolution)", ["AppointmentPlanning list", "ProblemFacts", "scoreHolder"], "teal"));

  // Row 3: Constraints
  parts.push(card(68, 296, 260, 50, "Hard Constraints (11)", ["business hours, doctor schedule", "absence, closure, holiday", "capacity, equipment, provider match"], "amber"));
  parts.push(card(350, 296, 200, 50, "Soft Constraints (2)", ["doctor load balance", "schedule gap minimize"], "amber"));
  parts.push(card(572, 296, 260, 50, "AppointmentConstraintProvider", ["Timefold ConstraintProvider", "H1–H11, S1–S2"], "amber"));

  // Row 4: Solver
  parts.push(card(68, 378, 200, 50, "SolverConfig", ["termination config", "move filters"], "green"));
  parts.push(card(290, 378, 300, 50, "Timefold Solver Engine", ["local search · tabu search", "score evaluation · SSA"], "green"));
  parts.push(card(612, 378, 200, 50, "BestSolution", ["optimized assignments", "HardSoftScore"], "green"));

  // Row 5: Output
  parts.push(card(68, 460, 300, 50, "SolverResult", ["assignments: Map<Long, Assignment>", "appointmentId → (doctorId, date, time)"], "purple"));
  parts.push(card(390, 460, 280, 50, "Caller (SolverController)", ["receives SolverResult", "calls AppointmentRepository.save()"], "purple"));
  parts.push(card(692, 460, 200, 50, "DB: PostgreSQL", ["updated appointments", "Exposed JDBC transaction"], "gray"));

  // Connectors
  parts.push(line(288, 157, 310, 157, "line-blue"));
  parts.push(line(510, 157, 530, 157, "line-blue"));
  parts.push(line(730, 157, 750, 157, "line-blue"));
  parts.push(line(850, 182, 850, 200, "line-teal"));
  parts.push(line(218, 264, 218, 282, "line-amber"));
  parts.push(line(390, 246, 430, 282, "dashed-amber"));
  parts.push(line(540, 296, 572, 296, "line-amber"));
  parts.push(line(630, 296, 630, 364, "line-green"));
  parts.push(line(268, 378, 290, 378, "line-green"));
  parts.push(line(590, 378, 612, 378, "line-green"));
  parts.push(line(712, 403, 712, 446, "line-purple"));
  parts.push(line(218, 428, 218, 460, "line-purple"));
  parts.push(line(368, 485, 390, 485, "line-purple"));
  parts.push(line(670, 485, 692, 485, "line-gray"));

  parts.push(svgFooter(W, H, "appointment-solver", "Timefold Solver"));
  saveSvgPng("appointment-solver-architecture-01", parts.join("\n"), outDir);
}

// ─── Diagram: Notification HA Flow ────────────────────────────────────────────
function generateNotificationFlow() {
  const W = 1040, H = 620;
  const parts = [];
  parts.push(svgHeader(W, H, "appointment-notification — HA Notification Flow"));
  parts.push(`<text class="title" x="62" y="78">HA Notification Flow</text>`);
  parts.push(`<text class="subtitle" x="66" y="100">appointment-notification · Redis Leader Election · Resilience4j guards</text>`);

  // Layers
  parts.push(layerBand(48, 118, W - 96, 72, "Event Sources", "blue"));
  parts.push(layerBand(48, 204, W - 96, 72, "Notification Module", "pink"));
  parts.push(layerBand(48, 290, W - 96, 72, "Leader Election (HA)", "purple"));
  parts.push(layerBand(48, 376, W - 96, 72, "Resilience4j Guards", "amber"));
  parts.push(layerBand(48, 462, W - 96, 72, "Notification Channels", "teal"));
  parts.push(layerBand(48, 548, W - 96, 52, "Persistence", "gray"));

  // Row 1: Event Sources
  parts.push(card(68, 134, 280, 50, "AppointmentController", ["publishes domain events", "via Spring ApplicationEvent"], "blue"));
  parts.push(card(370, 134, 260, 50, "AppointmentReminderScheduler", ["@Scheduled(fixedRate=1h)", "tomorrow + same-day CONFIRMED"], "blue"));
  parts.push(card(652, 134, 200, 50, "RescheduleController", ["reschedule completion events", "closure-triggered"], "blue"));

  // Row 2: Notification Module
  parts.push(card(68, 220, 280, 50, "NotificationEventListener", ["@EventListener", "Created/StatusChanged/Cancelled/Rescheduled"], "pink"));
  parts.push(card(390, 220, 240, 50, "NotificationAutoConfiguration", ["Spring @Configuration", "registers notification beans"], "pink"));
  parts.push(card(660, 220, 200, 50, "DuplicateGuard", ["Redis SETNX", "prevents duplicate sends"], "pink"));

  // Row 3: Leader Election
  parts.push(card(68, 306, 360, 50, "bluetape4k-leader (Redis SETNX)", ["if (!leaderElection.isLeader()) return", "single node runs scheduler"], "purple"));
  parts.push(card(450, 306, 260, 50, "Lettuce (Redis Client)", ["SETNX key, TTL=60s", "periodic heartbeat"], "purple"));
  parts.push(card(734, 306, 180, 50, "Redis Server", ["Leader key storage", "cluster-safe"], "purple"));

  // Row 4: Resilience4j
  parts.push(card(68, 392, 240, 50, "CircuitBreaker", ["failure rate threshold 50%", "30s open state wait"], "amber"));
  parts.push(card(330, 392, 200, 50, "Retry", ["max 3 attempts", "1s wait between"], "amber"));
  parts.push(card(552, 392, 200, 50, "Bulkhead", ["max 10 concurrent calls", "prevents cascade"], "amber"));
  parts.push(card(774, 392, 200, 50, "ResilientNotificationChannel", ["wraps channel", "with all 3 guards"], "amber"));

  // Row 5: Channels
  parts.push(card(68, 478, 280, 50, "DummyNotificationChannel", ["logs + stores history", "always returns SUCCESS"], "teal"));
  parts.push(card(380, 478, 280, 50, ["Future: KakaoTalk", "Email, SMS Channels"], ["implement NotificationChannel", "interface"], "teal"));
  parts.push(card(692, 478, 220, 50, "NotificationHistoryRepository", ["Exposed table", "stores send history"], "teal"));

  // Row 6: Persistence
  parts.push(card(68, 560, 280, 30, "notification_history (PostgreSQL)", null, "gray"));
  parts.push(card(380, 560, 260, 30, "event_logs (appointment-event)", null, "gray"));

  // Connectors
  // Events → NotificationEventListener
  parts.push(line(208, 184, 208, 220, "line-pink"));
  parts.push(viaTop(500, 184, 208, 220, "dashed-pink"));
  parts.push(viaTop(752, 184, 208, 220, "dashed-pink"));
  // NotificationEventListener → Leader check
  parts.push(line(248, 270, 248, 306, "line-purple"));
  // Leader → Lettuce
  parts.push(line(428, 331, 450, 331, "line-purple"));
  parts.push(line(710, 331, 734, 331, "line-purple"));
  // NotificationEventListener → Resilience4j
  parts.push(line(248, 356, 248, 392, "line-amber"));
  // Resilience4j chain
  parts.push(line(308, 417, 330, 417, "line-amber"));
  parts.push(line(530, 417, 552, 417, "line-amber"));
  parts.push(line(752, 417, 774, 417, "line-amber"));
  // Resilience → DummyChannel
  parts.push(line(874, 442, 874, 478, "line-teal"));
  parts.push(viaBottom(874, 478, 208, 478, "dashed-teal"));
  // Channel → History
  parts.push(line(614, 503, 692, 503, "line-teal"));
  // History → DB
  parts.push(line(802, 528, 802, 560, "dashed-gray"));
  // Scheduler → Notification
  parts.push(viaTop(500, 184, 390, 220, "dashed-blue"));

  parts.push(svgFooter(W, H, "appointment-notification"));
  saveSvgPng("appointment-notification-architecture-01", parts.join("\n"), outDir);
}

// ─── Diagram: API Sequence ─────────────────────────────────────────────────────
function generateApiSequence() {
  const W = 1060, H = 760;
  const parts = [];
  parts.push(svgHeader(W, H, "appointment-api — Appointment Creation Sequence"));
  parts.push(`<text class="title" x="62" y="78">Appointment Creation Flow</text>`);
  parts.push(`<text class="subtitle" x="66" y="100">POST /api/{tenantCode}/appointments · JWT auth · Exposed transaction · Event publish</text>`);

  // Participants
  const participants = [
    { id: "fe", x: 90, label: "Frontend", color: "purple" },
    { id: "sec", x: 230, label: "SecurityFilter", color: "green" },
    { id: "ctrl", x: 380, label: "AppointmentController", color: "green" },
    { id: "svc", x: 540, label: "AppointmentService", color: "teal" },
    { id: "repo", x: 700, label: "AppointmentRepository", color: "teal" },
    { id: "evt", x: 860, label: "EventPublisher", color: "blue" },
    { id: "db", x: 985, label: "PostgreSQL", color: "gray" },
  ];

  const headerH = 52, lifelineStart = 152, lifelineEnd = 720;
  for (const p of participants) {
    const c = P[p.color] ?? P.blue;
    const lw = p.label.length * 9 + 20;
    const lx = p.x - lw / 2;
    parts.push(`<rect x="${lx}" y="116" width="${lw}" height="${headerH}" rx="8" fill="${c.bg}" stroke="${c.stroke}" stroke-width="2" filter="url(#shadow)"/>`);
    parts.push(`<text class="small" x="${p.x}" y="${116 + headerH / 2 + 4}" text-anchor="middle" dominant-baseline="middle" fill="${c.text}">${esc(p.label)}</text>`);
    parts.push(`<line x1="${p.x}" y1="${lifelineStart}" x2="${p.x}" y2="${lifelineEnd}" stroke="#C0C8D4" stroke-width="1.5" stroke-dasharray="6 4"/>`);
  }

  // Messages
  function msg(fromX, toX, y, label, cls = "line", labelOffset = -14) {
    parts.push(`<path class="${cls}" d="M${fromX} ${y} L${toX} ${y}"/>`);
    const lx = Math.min(fromX, toX) + Math.abs(toX - fromX) * 0.35;
    parts.push(`<rect x="${lx - 2}" y="${y + labelOffset - 10}" width="${label.length * 7 + 10}" height="14" rx="3" fill="white" stroke="#C0C8D4" stroke-width="0.8" opacity="0.9"/>`);
    parts.push(`<text class="small" x="${lx}" y="${y + labelOffset}" dominant-baseline="middle">${esc(label)}</text>`);
  }

  function altBox(y1, y2, label, color) {
    const c = P[color] ?? P.gray;
    parts.push(`<rect x="60" y="${y1}" width="${W - 120}" height="${y2 - y1}" rx="6" fill="${c.bg}" stroke="${c.stroke}" stroke-width="1" opacity="0.25"/>`)
    parts.push(`<rect x="60" y="${y1}" width="50" height="18" rx="3" fill="${c.stroke}" opacity="0.7"/>`);
    parts.push(`<text class="small" x="85" y="${y1 + 9}" text-anchor="middle" dominant-baseline="middle" fill="white">${esc(label)}</text>`);
  }

  let y = 180;
  const step = 48;

  msg(participants[0].x, participants[1].x, y, "POST /api/{tenantCode}/appointments", "line-purple");
  y += step;

  // alt: JWT validation
  altBox(y - 12, y + step * 2 - 4, "alt", "green");
  msg(participants[1].x, participants[0].x, y, "401 Unauthorized (invalid/missing JWT)", "dashed-pink", -14);
  y += step;
  msg(participants[1].x, participants[2].x, y, "SchedulingUserPrincipal (tenantCode validated)", "line-green");
  y += step;

  msg(participants[2].x, participants[3].x, y, "validateAndCreate(CreateAppointmentRequest)", "line-teal");
  y += step;

  msg(participants[3].x, participants[4].x, y, "findSlotConflicts(clinicId, doctorId, date, time)", "line-teal");
  y += step;
  msg(participants[4].x, participants[6].x, y, "SELECT appointments WHERE doctor + date overlap", "line-gray");
  y += step;
  msg(participants[6].x, participants[4].x, y, "conflicting: List<AppointmentRecord>", "dashed", -14);
  y += step;

  // alt: conflict check
  altBox(y - 12, y + step * 2 - 4, "alt", "pink");
  msg(participants[4].x, participants[3].x, y, "ConflictDetectedException (409 Conflict)", "dashed-pink", -14);
  y += step;
  msg(participants[4].x, participants[3].x, y, "OK (no conflict)", "dashed-green", -14);
  y += step;

  msg(participants[3].x, participants[4].x, y, "save(AppointmentRecord)", "line-teal");
  y += step;
  msg(participants[4].x, participants[6].x, y, "INSERT scheduling_appointments", "line-gray");
  y += step;
  msg(participants[6].x, participants[4].x, y, "id: Long (new appointmentId)", "dashed", -14);
  y += step;

  msg(participants[3].x, participants[5].x, y, "publishEvent(AppointmentDomainEvent.Created)", "line-blue");
  y += step;
  msg(participants[5].x, participants[2].x, y, "AppointmentResponse(id, status=REQUESTED, timezone)", "dashed-green", -14);
  y += step;
  msg(participants[2].x, participants[0].x, y, "201 Created {id, appointmentDate, startTime, timezone}", "dashed-green", -14);

  parts.push(svgFooter(W, H, "appointment-api", "AppointmentController.kt"));
  saveSvgPng("appointment-api-sequence-01", parts.join("\n"), outDir);
}

// ─── Main ──────────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
const moduleFilter = args.indexOf("--module") >= 0 ? args[args.indexOf("--module") + 1] : null;

function shouldRun(name) {
  return !moduleFilter || name === moduleFilter;
}

console.log("clinic-appointment diagram generator");
console.log(`Output: ${outDir}`);
console.log(`Fonts: ${TITLE_FONT_FILE.replace(process.env.HOME, "~")} / ${DETAIL_FONT_FILE.replace(process.env.HOME, "~")}`);
console.log("");

fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(path.join(root, "docs/assets/readme-diagrams"), { recursive: true });
fs.mkdirSync(chartDir, { recursive: true });

if (shouldRun("root")) {
  console.log("▶ Root Architecture + Module Overview");
  generateRootArchitecture();
  generateRootModuleOverview();
}

if (shouldRun("core")) {
  console.log("▶ appointment-core: ERD + State Machine");
  generateCoreErd();
  generateStateMachine();
}

if (shouldRun("event")) {
  console.log("▶ appointment-event: Event Flow");
  generateEventFlow();
}

if (shouldRun("solver")) {
  console.log("▶ appointment-solver: Data Flow");
  generateSolverDataFlow();
}

if (shouldRun("notification")) {
  console.log("▶ appointment-notification: HA Flow");
  generateNotificationFlow();
}

if (shouldRun("api")) {
  console.log("▶ appointment-api: Creation Sequence");
  generateApiSequence();
}

console.log("\nDone.");
