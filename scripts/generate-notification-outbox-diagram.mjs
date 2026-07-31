#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname, "..");
const output = path.join(root, "docs/requirements/assets");
const width = 1800;
const height = 1180;

const copy = {
  en: {
    title: "Durable notification outbox flow",
    subtitle: "One database row, one provider route, and no persisted contact details",
    transaction: "Appointment transaction",
    command: ["Appointment command", "create · confirm · cancel", "reschedule · reminder"],
    outbox: ["Minimal outbox record", "memberId · appointmentId", "typed template parameters", "committed atomically"],
    rollout: "Clinic-scoped rollout",
    gate: ["NotificationDeliveryRouteGate", "select exactly one provider route"],
    event: ["Event bridge", "transitional route · SHADOW", "+ non-canary clinics"],
    worker: ["Background worker", "route · canary allowlist", "+ ACTIVE"],
    modes: [
      ["SHADOW", "event route for every clinic"],
      ["CANARY", "worker for allowlist; event for others"],
      ["ACTIVE", "worker route for every clinic"],
      ["PAUSED", "no provider route; backlog retained"],
    ],
    delivery: "Privacy-safe delivery pipeline",
    claim: ["Conditional DB claim", "exact outbox row · lease · fencing token"],
    profile: ["Current member profile", "contact · language · consent", "resolved after claim · memory only"],
    template: ["Render typed template", "approved key + version", "body exists in memory only"],
    provider: ["Provider call", "deterministic idempotency key", "bounded timeout · retry · bulkhead"],
    terminal: ["Fenced terminal update", "stable outcome · reason code · safe fingerprint", "remove memberId · appointmentId", "remove template parameters"],
    retention: "Retention and operations",
    retained: ["What remains", "privacy-safe outcome and bounded attempt metadata", "SENT 7d · SUPPRESSED 7d · EXHAUSTED 30d"],
    question: "Reader check: Where is intent persisted, when does contact data enter memory, and what remains after completion?",
    atomic: "same transaction",
    route: "one selected route",
    resolve: "after claim",
    redact: "redact and finish",
  },
  ko: {
    title: "내구성 알림 outbox 발송 흐름",
    subtitle: "하나의 DB 행과 하나의 provider 경로를 사용하고 연락처는 저장하지 않는다",
    transaction: "예약 트랜잭션",
    command: ["예약 명령", "생성 · 확정 · 취소 · 재배정 · 리마인더"],
    outbox: ["예약 + 최소 outbox", "memberId · appointmentId", "타입 지정 template parameter", "하나의 트랜잭션으로 커밋"],
    rollout: "병원별 단계 전환",
    gate: ["NotificationDeliveryRouteGate", "provider 경로를 정확히 하나만 선택"],
    event: ["전환기 event bridge", "경로 · SHADOW", "+ 카나리 밖 병원"],
    worker: ["백그라운드 worker", "경로 · 카나리 허용 목록", "+ ACTIVE"],
    modes: [
      ["SHADOW", "모든 병원은 이벤트 경로"],
      ["CANARY", "허용 병원은 worker, 나머지는 이벤트"],
      ["ACTIVE", "모든 병원은 worker 경로"],
      ["PAUSED", "provider 경로 중단, backlog 보존"],
    ],
    delivery: "개인정보를 남기지 않는 발송 절차",
    claim: ["DB 조건부 선점", "정확한 outbox 행 · lease · fencing token"],
    profile: ["최신 회원 프로필 조회", "연락처 · 언어 · 동의", "메모리에서만 사용"],
    template: ["타입 지정 template 렌더링", "승인된 key + version", "본문은 메모리에만 존재"],
    provider: ["Provider 호출", "결정적인 멱등성 키", "제한된 timeout · retry · bulkhead"],
    terminal: ["Fencing 종료 갱신", "안정적인 결과 · 사유 코드 · 안전한 fingerprint", "memberId · appointmentId 제거", "template parameter 제거"],
    retention: "보존과 운영",
    retained: ["완료 후 남는 정보", "개인정보가 없는 결과와 제한된 시도 metadata", "SENT 7일 · SUPPRESSED 7일 · EXHAUSTED 30일"],
    question: "독자 확인: 알림 의도는 어디에 저장되고, 연락처는 언제 메모리에 들어오며, 완료 후 무엇이 남는가?",
    atomic: "같은 트랜잭션",
    route: "선택된 경로 하나",
    resolve: "선점 후 조회",
    redact: "제거 후 종료",
  },
};

const themes = {
  light: {
    bg: "#f4f7fb", frame: "#ffffff", frameStroke: "#cbd7e6", text: "#20324a", muted: "#56677d",
    lane: "#f8fbff", laneStroke: "#c7d6e8", card: "#ffffff", blue: "#4f77c9", teal: "#2f9f93",
    purple: "#8067cf", amber: "#cc8428", red: "#c85c65", note: "#fff9e8", noteStroke: "#d4aa53",
    shadow: "#27415f", rowA: "#eef4fc", rowB: "#f7f9fc",
  },
  dark: {
    bg: "#0f1724", frame: "#172234", frameStroke: "#40526a", text: "#edf4ff", muted: "#b6c4d7",
    lane: "#1b293d", laneStroke: "#49617d", card: "#223148", blue: "#7da6ff", teal: "#63cfbf",
    purple: "#b09aff", amber: "#f0b25b", red: "#f18189", note: "#382f1c", noteStroke: "#d6ad59",
    shadow: "#000000", rowA: "#263852", rowB: "#202f45",
  },
};

const sequenceCopy = {
  en: {
    title: "Durable reminder delivery sequence",
    subtitle: "Reminder correctness comes from the outbox key, database lease, fencing, and provider idempotency",
    participants: ["Reminder materializer", "Outbox DB", "Selected route", "Member directory", "Template renderer", "Provider"],
    steps: [
      [0, 1, "upsert reminder intent with appointment version + slot"],
      [2, 1, "claim ready row with lease + fencing token"],
      [1, 2, "claimed work; one logical notification"],
      [2, 3, "resolve current contact, language, and consent"],
      [3, 2, "current profile; memory only"],
      [2, 4, "render approved typed template"],
      [4, 2, "provider-ready request; memory only"],
      [2, 5, "send with deterministic idempotency key"],
      [5, 2, "stable delivery outcome"],
      [2, 1, "fenced terminal update; redact identifiers"],
      [2, 1, "bounded status-specific retention"],
    ],
    rollout: "SHADOW: event bridge · CANARY: allowlisted worker · ACTIVE: worker · PAUSED: retain backlog",
    missed: "Past catch-up window: SUPPRESSED(REMINDER_WINDOW_MISSED), never send late",
  },
  ko: {
    title: "내구성 리마인더 발송 시퀀스",
    subtitle: "리마인더 정합성은 outbox key, DB lease, fencing, provider 멱등성으로 보장한다",
    participants: ["리마인더 생성기", "Outbox DB", "선택된 발송 경로", "회원 DB", "Template renderer", "Provider"],
    steps: [
      [0, 1, "예약 version + slot으로 리마인더 의도 upsert"],
      [2, 1, "lease + fencing token으로 준비된 행 선점"],
      [1, 2, "논리 알림 한 건 반환"],
      [2, 3, "최신 연락처·언어·동의 조회"],
      [3, 2, "현재 프로필 반환, 메모리에서만 사용"],
      [2, 4, "승인된 typed template 렌더링"],
      [4, 2, "provider 요청 반환, 메모리에서만 사용"],
      [2, 5, "결정적인 멱등성 키로 발송"],
      [5, 2, "안정적인 발송 결과 반환"],
      [2, 1, "fencing 종료 갱신 + 식별자 제거"],
      [2, 1, "상태별 제한된 보존 처리"],
    ],
    rollout: "SHADOW: event bridge · CANARY: 허용 worker · ACTIVE: worker · PAUSED: backlog 보존",
    missed: "보정 시간창 경과: SUPPRESSED(REMINDER_WINDOW_MISSED), 늦은 발송 금지",
  },
};

const appointmentFlowCopy = {
  en: {
    title: "Appointment creation and notification intent",
    subtitle: "The appointment and minimal outbox record commit atomically; delivery continues asynchronously",
    cards: [
      ["Patient and frontend", "choose an available slot", "POST /api/appointments"],
      ["Appointment API", "validate JWT and slot", "open one transaction"],
      ["Appointment + outbox DB", "appointment row", "minimal notification intent"],
      ["Domain event", "publish only after commit", "logging and transition signal"],
      ["Selected route", "event bridge or worker", "conditionally claim exact row"],
      ["Privacy-safe delivery", "resolve current member profile", "render and send in memory"],
    ],
    labels: ["request", "same transaction", "after commit", "one selected route", "claim then deliver"],
    note: "No recipient, phone number, rendered body, or raw provider error is stored in the outbox.",
  },
  ko: {
    title: "예약 생성과 알림 의도 기록",
    subtitle: "예약과 최소 outbox 행을 원자적으로 커밋하고 실제 발송은 비동기로 이어간다",
    cards: [
      ["환자와 Frontend", "가용 슬롯 선택", "POST /api/appointments"],
      ["예약 API", "JWT와 슬롯 검증", "하나의 transaction 시작"],
      ["예약 + outbox DB", "예약 행", "최소 알림 의도"],
      ["도메인 이벤트", "커밋 후에만 발행", "로그와 전환기 신호"],
      ["선택된 알림 경로", "event bridge 또는 worker", "정확한 행을 조건부 선점"],
      ["개인정보 안전 발송", "최신 회원 프로필 조회", "메모리에서 렌더링·발송"],
    ],
    labels: ["요청", "같은 transaction", "커밋 후", "선택된 경로 하나", "선점 후 발송"],
    note: "outbox에는 수신자·전화번호·완성 본문·provider 원문 오류를 저장하지 않는다.",
  },
};

const bookingSequenceCopy = {
  en: {
    title: "Patient appointment creation sequence",
    subtitle: "Availability is read first; appointment and notification intent commit together",
    participants: ["Patient", "Frontend", "API", "Core", "PostgreSQL", "Event bus", "Notification route"],
    steps: [
      [0, 1, "choose doctor and date"], [1, 2, "GET available slots"], [2, 3, "calculate slots"],
      [3, 4, "read schedules and conflicts"], [4, 3, "availability data"], [3, 2, "available slots"],
      [2, 1, "slot response"], [0, 1, "select slot and confirm"], [1, 2, "POST appointment with JWT"],
      [2, 3, "create appointment"], [3, 4, "insert appointment + minimal outbox atomically"],
      [4, 3, "commit succeeds"], [3, 2, "appointment record"], [2, 5, "publish Created after commit"],
      [5, 6, "transitional SHADOW signal"], [6, 4, "conditionally claim exact outbox row"],
      [2, 1, "201 Created"], [1, 0, "show confirmation"],
    ],
    rollout: "The event bridge never creates a second notification; it claims the already committed outbox row.",
    missed: "Contact details and rendered content enter memory only after a successful claim.",
  },
  ko: {
    title: "환자 예약 생성 시퀀스",
    subtitle: "가용성을 먼저 조회하고 예약과 알림 의도를 같은 트랜잭션으로 커밋한다",
    participants: ["환자", "Frontend", "API", "Core", "PostgreSQL", "Event bus", "알림 경로"],
    steps: [
      [0, 1, "의사와 날짜 선택"], [1, 2, "가용 슬롯 조회"], [2, 3, "슬롯 계산"],
      [3, 4, "스케줄과 충돌 조회"], [4, 3, "가용성 데이터 반환"], [3, 2, "가용 슬롯 반환"],
      [2, 1, "슬롯 응답"], [0, 1, "슬롯 선택 후 확인"], [1, 2, "JWT로 예약 생성 요청"],
      [2, 3, "예약 생성"], [3, 4, "예약 + 최소 outbox 원자적 INSERT"],
      [4, 3, "커밋 성공"], [3, 2, "예약 record 반환"], [2, 5, "커밋 후 Created 발행"],
      [5, 6, "SHADOW 전환기 신호"], [6, 4, "정확한 outbox 행 조건부 선점"],
      [2, 1, "201 Created"], [1, 0, "예약 완료 표시"],
    ],
    rollout: "event bridge는 알림을 새로 만들지 않고 이미 커밋된 outbox 행을 선점한다.",
    missed: "연락처와 완성 본문은 선점에 성공한 뒤에만 메모리에 들어온다.",
  },
};

function esc(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function card(id, x, y, w, h, lines, accent, palette) {
  const titleY = y + 35;
  const details = lines.slice(1).map((line, index) =>
    `<text class="detail" x="${x + 24}" y="${titleY + 31 + index * 24}">${esc(line)}</text>`
  ).join("\n");
  return `<g id="${id}">
    <rect class="card" x="${x}" y="${y}" width="${w}" height="${h}" rx="18" fill="${palette.card}" stroke="${accent}"/>
    <rect x="${x}" y="${y}" width="8" height="${h}" rx="4" fill="${accent}"/>
    <text class="card-title" x="${x + 24}" y="${titleY}">${esc(lines[0])}</text>
    ${details}
  </g>`;
}

function edge(id, d, color, label, lx, ly, marker = "arrow") {
  const labelSvg = label
    ? `<rect class="edge-label-bg" x="${lx - 8}" y="${ly - 18}" width="${Math.max(105, label.length * 8.2)}" height="25" rx="7"/><text class="edge-label" x="${lx}" y="${ly}">${esc(label)}</text>`
    : "";
  return `<g id="${id}"><path class="edge" d="${d}" stroke="${color}" marker-end="url(#${marker})"/>${labelSvg}</g>`;
}

function render(locale, theme) {
  const t = copy[locale];
  const p = themes[theme];
  const font = locale === "ko" ? '"goorm Sans","Noto Sans KR",ui-sans-serif,system-ui,sans-serif' : 'Inter,ui-sans-serif,system-ui,sans-serif';
  const mono = locale === "ko" ? '"goorm Sans Code","Noto Sans Mono",ui-monospace,monospace' : '"JetBrains Mono",ui-monospace,monospace';
  const modeRows = t.modes.map((row, index) => {
    const y = 580 + index * 48;
    return `<g id="mode-${row[0].toLowerCase()}">
      <rect x="465" y="${y}" width="450" height="42" rx="9" fill="${index % 2 === 0 ? p.rowA : p.rowB}"/>
      <text class="mode-key" x="485" y="${y + 27}" fill="${index === 3 ? p.red : p.text}">${row[0]}</text>
      <text class="mode-value" x="580" y="${y + 27}">${esc(row[1])}</text>
    </g>`;
  }).join("\n");

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title description">
  <title id="title">${esc(t.title)}</title>
  <desc id="description">${esc(t.subtitle)}</desc>
  <defs>
    <filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="7" stdDeviation="7" flood-color="${p.shadow}" flood-opacity="0.18"/></filter>
    <marker id="arrow" markerWidth="13" markerHeight="13" refX="11.5" refY="6.5" orient="auto" markerUnits="userSpaceOnUse"><path d="M0 0 L13 6.5 L0 13 Z" fill="${p.text}"/></marker>
    <style>
      svg{font-family:${font}} .canvas{fill:${p.bg}} .frame{fill:${p.frame};stroke:${p.frameStroke};stroke-width:3}
      .title{font-size:38px;font-weight:750;fill:${p.text}} .subtitle{font-size:17px;fill:${p.muted}}
      .lane{fill:${p.lane};stroke:${p.laneStroke};stroke-width:2}.lane-title{font-size:22px;font-weight:700;fill:${p.text}}
      .card{stroke-width:2.5;filter:url(#shadow)}.card-title{font-family:${mono};font-size:18px;font-weight:700;fill:${p.text}}
      .detail{font-size:14px;fill:${p.muted}}.edge{fill:none;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round}
      .edge-label-bg{fill:${p.frame};stroke:${p.frameStroke};stroke-width:1}.edge-label{font-family:${mono};font-size:12px;fill:${p.muted}}
      .mode-key{font-family:${mono};font-size:14px;font-weight:700}.mode-value{font-size:13px;fill:${p.muted}}
      .note{fill:${p.note};stroke:${p.noteStroke};stroke-width:2}.question{font-size:16px;font-weight:650;fill:${p.text}}
    </style>
  </defs>
  <rect class="canvas" width="${width}" height="${height}"/>
  <rect class="frame" x="38" y="34" width="1724" height="1110" rx="32"/>
  <text class="title" x="900" y="92" text-anchor="middle">${esc(t.title)}</text>
  <text class="subtitle" x="900" y="126" text-anchor="middle">${esc(t.subtitle)}</text>

  <g id="lane-transaction"><rect class="lane" x="70" y="175" width="350" height="650" rx="24"/><text class="lane-title" x="95" y="215">${esc(t.transaction)}</text></g>
  <g id="lane-rollout"><rect class="lane" x="440" y="175" width="500" height="650" rx="24"/><text class="lane-title" x="465" y="215">${esc(t.rollout)}</text></g>
  <g id="lane-delivery"><rect class="lane" x="970" y="175" width="760" height="650" rx="24"/><text class="lane-title" x="995" y="215">${esc(t.delivery)}</text></g>
  <g id="lane-retention"><rect class="lane" x="970" y="845" width="760" height="190" rx="24"/><text class="lane-title" x="995" y="885">${esc(t.retention)}</text></g>

  ${card("appointment-command", 95, 255, 300, 125, t.command, p.blue, p)}
  ${card("minimal-outbox", 95, 430, 300, 160, t.outbox, p.teal, p)}
  ${card("route-gate", 465, 255, 450, 105, t.gate, p.purple, p)}
  ${card("event-route", 465, 405, 220, 125, t.event, p.amber, p)}
  ${card("worker-route", 705, 405, 210, 125, t.worker, p.blue, p)}
  ${modeRows}

  ${card("conditional-claim", 1000, 275, 320, 120, t.claim, p.purple, p)}
  ${card("member-profile", 1390, 245, 310, 135, t.profile, p.teal, p)}
  ${card("template-render", 1390, 440, 310, 135, t.template, p.blue, p)}
  ${card("provider-call", 1390, 635, 310, 135, t.provider, p.amber, p)}
  ${card("terminal-update", 1000, 620, 320, 155, t.terminal, p.red, p)}
  ${card("retained-data", 1000, 905, 700, 110, t.retained, p.teal, p)}

  ${edge("command-to-outbox", "M245 380 V430", p.blue, t.atomic, 260, 415)}
  ${edge("outbox-to-gate", "M395 510 H413 Q425 510 425 498 V319 Q425 307 437 307 H465", p.teal, "", 0, 0)}
  ${edge("gate-to-event", "M670 360 V376 Q670 388 658 388 H587 Q575 388 575 400 V405", p.amber, "", 0, 0)}
  ${edge("gate-to-worker", "M710 360 V376 Q710 388 722 388 H798 Q810 388 810 400 V405", p.blue, "", 0, 0)}
  ${edge("event-to-claim", "M575 530 V558 Q575 570 587 570 H1148 Q1160 570 1160 558 V395", p.amber, t.route, 700, 563)}
  ${edge("worker-to-claim", "M915 468 H953 Q965 468 965 456 V362 Q965 350 977 350 H1000", p.blue, "", 0, 0)}
  ${edge("claim-to-profile", "M1320 335 H1343 Q1355 335 1355 323 Q1355 313 1367 313 H1390", p.purple, t.resolve, 1288, 230)}
  ${edge("profile-to-template", "M1545 380 V440", p.teal, "", 0, 0)}
  ${edge("template-to-provider", "M1545 575 V635", p.blue, "", 0, 0)}
  ${edge("provider-to-terminal", "M1390 703 H1320", p.amber, "", 0, 0)}
  ${edge("terminal-to-retention", "M1160 775 V905", p.red, t.redact, 1175, 850)}

  <g id="reader-question"><rect class="note" x="180" y="1050" width="1440" height="58" rx="16"/><text class="question" x="900" y="1086" text-anchor="middle">${esc(t.question)}</text></g>
  </svg>`;
}

function renderAppointmentFlow(locale, theme) {
  const t = appointmentFlowCopy[locale];
  const p = themes[theme];
  const W = 1800;
  const H = 900;
  const font = locale === "ko" ? '"goorm Sans","Noto Sans KR",ui-sans-serif,system-ui,sans-serif' : 'Inter,ui-sans-serif,system-ui,sans-serif';
  const mono = locale === "ko" ? '"goorm Sans Code","Noto Sans Mono",ui-monospace,monospace' : '"JetBrains Mono",ui-monospace,monospace';
  const positions = [[95, 280], [390, 280], [685, 280], [980, 280], [1275, 280], [1275, 570]];
  const cards = t.cards.map((lines, index) => card(`appointment-flow-card-${index}`, positions[index][0], positions[index][1], 250, 145, lines, [p.blue, p.purple, p.teal, p.amber, p.blue, p.teal][index], p)).join("\n");
  const paths = [
    ["M345 352 H390", p.blue],
    ["M640 352 H685", p.purple],
    ["M935 352 H980", p.teal],
    ["M1230 352 H1275", p.amber],
    ["M1400 425 V570", p.blue],
  ].map((item, index) => edge(`appointment-flow-edge-${index}`, item[0], item[1], "", 0, 0)).join("\n");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-labelledby="appointment-title appointment-description">
  <title id="appointment-title">${esc(t.title)}</title><desc id="appointment-description">${esc(t.subtitle)}</desc>
  <defs><filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="7" stdDeviation="7" flood-color="${p.shadow}" flood-opacity="0.18"/></filter>
    <marker id="arrow" markerWidth="13" markerHeight="13" refX="11.5" refY="6.5" orient="auto" markerUnits="userSpaceOnUse"><path d="M0 0 L13 6.5 L0 13 Z" fill="${p.text}"/></marker>
    <style>svg{font-family:${font}}.canvas{fill:${p.bg}}.frame{fill:${p.frame};stroke:${p.frameStroke};stroke-width:3}.title{font-size:38px;font-weight:750;fill:${p.text}}.subtitle{font-size:17px;fill:${p.muted}}.card{stroke-width:2.5;filter:url(#shadow)}.card-title{font-family:${mono};font-size:17px;font-weight:700;fill:${p.text}}.detail{font-size:13px;fill:${p.muted}}.edge{fill:none;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round}.edge-label-bg{fill:${p.frame};stroke:${p.frameStroke};stroke-width:1}.edge-label{font-family:${mono};font-size:12px;fill:${p.muted}}.note{fill:${p.note};stroke:${p.noteStroke};stroke-width:2}.note-text{font-size:17px;font-weight:650;fill:${p.text}}</style>
  </defs><rect class="canvas" width="${W}" height="${H}"/><rect class="frame" x="38" y="34" width="1724" height="832" rx="32"/>
  <text class="title" x="900" y="100" text-anchor="middle">${esc(t.title)}</text><text class="subtitle" x="900" y="136" text-anchor="middle">${esc(t.subtitle)}</text>
  ${cards}${paths}<g><rect class="note" x="240" y="750" width="1320" height="58" rx="16"/><text class="note-text" x="900" y="786" text-anchor="middle">${esc(t.note)}</text></g></svg>`;
}

function renderSequence(locale, theme, source = sequenceCopy, options = {}) {
  const t = source[locale];
  const p = themes[theme];
  const W = options.width ?? 1900;
  const H = options.height ?? 1120;
  const margin = options.margin ?? 170;
  const xs = t.participants.map((_, index) => margin + index * ((W - margin * 2) / (t.participants.length - 1)));
  const headerWidth = Math.min(options.headerWidth ?? 250, (W - margin * 2) / (t.participants.length - 1) - 24);
  const startY = options.startY ?? 320;
  const stepY = options.stepY ?? 58;
  const lifelineEnd = options.lifelineEnd ?? 955;
  const font = locale === "ko" ? '"goorm Sans","Noto Sans KR",ui-sans-serif,system-ui,sans-serif' : 'Inter,ui-sans-serif,system-ui,sans-serif';
  const mono = locale === "ko" ? '"goorm Sans Code","Noto Sans Mono",ui-monospace,monospace' : '"JetBrains Mono",ui-monospace,monospace';
  const participants = t.participants.map((label, index) => `<g id="participant-${index}">
    <rect class="header" x="${xs[index] - headerWidth / 2}" y="180" width="${headerWidth}" height="68" rx="12"/>
    <text class="participant-title" x="${xs[index]}" y="221" text-anchor="middle">${esc(label)}</text>
    <line class="lifeline" x1="${xs[index]}" y1="248" x2="${xs[index]}" y2="${lifelineEnd}"/>
  </g>`).join("\n");
  const messages = t.steps.map((step, index) => {
    const [from, to, label] = step;
    const y = startY + index * stepY;
    const direction = xs[to] > xs[from] ? 1 : -1;
    const start = xs[from] + direction * 10;
    const end = xs[to] - direction * 14;
    const mid = (start + end) / 2;
    const labelWidth = Math.min(Math.max(label.length * (locale === "ko" ? 8.5 : 7.1) + 28, 150), Math.abs(end - start) - 24);
    return `<g id="message-${index + 1}">
      <rect class="label" x="${mid - labelWidth / 2}" y="${y - 28}" width="${labelWidth}" height="24" rx="6"/>
      <circle class="step" cx="${mid - labelWidth / 2 + 15}" cy="${y - 16}" r="11"/>
      <text class="badgeText" x="${mid - labelWidth / 2 + 15}" y="${y - 12}" text-anchor="middle">${index + 1}</text>
      <text class="msg" x="${mid - labelWidth / 2 + 32}" y="${y - 12}">${esc(label)}</text>
      <path class="message" d="M${start} ${y} H${end}" marker-end="url(#sequence-arrow)"/>
    </g>`;
  }).join("\n");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-labelledby="sequence-title sequence-description">
  <title id="sequence-title">${esc(t.title)}</title><desc id="sequence-description">${esc(t.subtitle)}</desc>
  <defs>
    <filter id="sequence-shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="6" stdDeviation="6" flood-color="${p.shadow}" flood-opacity="0.18"/></filter>
    <marker id="sequence-arrow" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse" viewBox="0 0 10 10"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${p.text}"/></marker>
    <style>
      svg{font-family:${font}}.canvas{fill:${p.bg}}.frame{fill:${p.frame};stroke:${p.frameStroke};stroke-width:3}
      .title{font-size:38px;font-weight:750;fill:${p.text}}.subtitle{font-size:17px;fill:${p.muted}}
      .header{fill:${p.card};stroke:${p.blue};stroke-width:2.4;filter:url(#sequence-shadow)}
      .participant-title{font-family:${mono};font-size:16px;font-weight:700;fill:${p.text}}
      .lifeline{stroke:${p.laneStroke};stroke-width:2;stroke-dasharray:8 8}.message{stroke:${p.purple};stroke-width:2.8;fill:none}
      .activation{fill:${p.rowA};stroke:${p.purple};stroke-width:1.5}.label{fill:${p.frame};stroke:${p.frameStroke};stroke-width:1}.msg{font-size:12px;fill:${p.muted}}
      .step{fill:${p.purple}}.badgeText{font-family:${mono};font-size:10px;font-weight:700;fill:${p.frame}}
      .note{fill:${p.note};stroke:${p.noteStroke};stroke-width:2}.note-text{font-size:15px;font-weight:650;fill:${p.text}}
    </style>
  </defs>
  <rect class="canvas" width="${W}" height="${H}"/><rect class="frame" x="36" y="32" width="1828" height="1052" rx="32"/>
  <text class="title" x="950" y="88" text-anchor="middle">${esc(t.title)}</text>
  <text class="subtitle" x="950" y="122" text-anchor="middle">${esc(t.subtitle)}</text>
  <g id="rollout-note"><rect class="note" x="250" y="145" width="1400" height="42" rx="12"/><text class="note-text" x="950" y="172" text-anchor="middle">${esc(t.rollout)}</text></g>
  ${participants}
  <rect class="activation" x="${xs[options.activeParticipant ?? 2] - 8}" y="${startY - 28}" width="16" height="${lifelineEnd - startY + 28}" rx="5"/>
  ${messages}
  <g id="missed-reminder-note"><rect class="note" x="250" y="${H - 130}" width="${W - 500}" height="48" rx="12"/><text class="note-text" x="${W / 2}" y="${H - 99}" text-anchor="middle">${esc(t.missed)}</text></g>
  </svg>`;
}

fs.mkdirSync(output, { recursive: true });
for (const locale of Object.keys(copy)) {
  for (const theme of Object.keys(themes)) {
    const themeSuffix = theme === "light" ? "" : "-dark";
    const base = `data-flow-05-notification-events-${locale}${themeSuffix}`;
    const svgPath = path.join(output, `${base}.svg`);
    const pngPath = path.join(output, `${base}.png`);
    fs.writeFileSync(svgPath, render(locale, theme), "utf8");
    const cairo = process.env.CAIROSVG ?? path.join(process.env.HOME ?? "", ".local/bin/cairosvg");
    const result = spawnSync(cairo, [svgPath, "-o", pngPath, "-s", "2"], { stdio: "inherit" });
    if (result.status !== 0) process.exit(result.status ?? 1);
    console.log(`generated ${path.relative(root, svgPath)} and ${path.relative(root, pngPath)}`);

    const sequenceBase = `user-scenarios-05-ha-reminder-${locale}${themeSuffix}`;
    const sequenceSvg = path.join(output, `${sequenceBase}.svg`);
    const sequencePng = path.join(output, `${sequenceBase}.png`);
    fs.writeFileSync(sequenceSvg, renderSequence(locale, theme), "utf8");
    const sequenceResult = spawnSync(cairo, [sequenceSvg, "-o", sequencePng, "-s", "2"], { stdio: "inherit" });
    if (sequenceResult.status !== 0) process.exit(sequenceResult.status ?? 1);
    console.log(`generated ${path.relative(root, sequenceSvg)} and ${path.relative(root, sequencePng)}`);

    const appointmentBase = `data-flow-01-appointment-create-${locale}${themeSuffix}`;
    const appointmentSvg = path.join(output, `${appointmentBase}.svg`);
    const appointmentPng = path.join(output, `${appointmentBase}.png`);
    fs.writeFileSync(appointmentSvg, renderAppointmentFlow(locale, theme), "utf8");
    const appointmentResult = spawnSync(cairo, [appointmentSvg, "-o", appointmentPng, "-s", "2"], { stdio: "inherit" });
    if (appointmentResult.status !== 0) process.exit(appointmentResult.status ?? 1);
    console.log(`generated ${path.relative(root, appointmentSvg)} and ${path.relative(root, appointmentPng)}`);

    const bookingBase = `user-scenarios-01-patient-booking-${locale}${themeSuffix}`;
    const bookingSvg = path.join(output, `${bookingBase}.svg`);
    const bookingPng = path.join(output, `${bookingBase}.png`);
    fs.writeFileSync(bookingSvg, renderSequence(locale, theme, bookingSequenceCopy, { width: 2100, height: 1320, margin: 150, headerWidth: 230, startY: 310, stepY: 48, lifelineEnd: 1200 }), "utf8");
    const bookingResult = spawnSync(cairo, [bookingSvg, "-o", bookingPng, "-s", "2"], { stdio: "inherit" });
    if (bookingResult.status !== 0) process.exit(bookingResult.status ?? 1);
    console.log(`generated ${path.relative(root, bookingSvg)} and ${path.relative(root, bookingPng)}`);
  }
}
