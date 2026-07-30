#!/usr/bin/env node
/**
 * clinic-appointment README diagram generator
 * Uses Graphviz dot for automatic layout with correct edge routing.
 *
 * Usage: node scripts/generate-diagrams.mjs [--module <name>]
 *   modules: root, core, event, solver, notification, api
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname ?? process.cwd(), "..");
const outDir = path.join(root, "docs/images/readme-diagrams");
const locales = ["en", "ko"];

const fonts = {
  en: { title: "Architects Daughter", body: "Comic Mono" },
  ko: { title: "goorm Sans", body: "goorm Sans" },
};

// ─── Palette ──────────────────────────────────────────────────────────────────
const C = {
  blue:   ["#E8F3FF", "#5B8DEF", "#1A3A5C"],
  green:  ["#EAF7EF", "#58A978", "#1A3A26"],
  teal:   ["#E9F7F6", "#45A7A1", "#0E3533"],
  amber:  ["#FFF3D9", "#D6A441", "#4A3000"],
  pink:   ["#FDECEF", "#DC6B82", "#4A0E1C"],
  purple: ["#F1ECFF", "#8A72D6", "#2A1A5C"],
  olive:  ["#EEF6D9", "#8BA84D", "#2A3A0E"],
  gray:   ["#F2F5F9", "#9AA8B8", "#2A3340"],
};

/** Graphviz node fill/border/font attributes for a named color. */
function nc(color) {
  const [bg, stroke, text] = C[color] ?? C.gray;
  return `fillcolor="${bg}" color="${stroke}" fontcolor="${text}"`;
}
/** Graphviz cluster background/border for a named color. */
function cc(color) {
  const [bg, stroke] = C[color] ?? C.gray;
  return `style=filled fillcolor="${bg}88" color="${stroke}" penwidth=1.5 fontcolor="${stroke}"`;
}

const KO_TEXT = new Map([
  ["Clinic Appointment — System Architecture", "Clinic Appointment — 시스템 아키텍처"],
  ["Kotlin 2.3 · Spring Boot 4 · Timefold Solver · Redis", "Kotlin 2.3 · Spring Boot 4 · Timefold Solver · Redis"],
  ["Angular 18 SPA", "Angular 18 SPA"],
  ["REST Client", "REST 클라이언트"],
  ["HTTP / JWT Bearer", "HTTP / JWT Bearer"],
  ["Swagger UI", "Swagger UI"],
  ["Gatling Tests", "Gatling 테스트"],
  ["load simulation", "부하 시뮬레이션"],
  ["Spring Boot 4 MVC · JWT · Flyway", "Spring Boot 4 MVC · JWT · Flyway"],
  ["16 entities · Exposed ORM · State Machine · Slot Calc", "16개 엔티티 · Exposed ORM · 상태 머신 · 슬롯 계산"],
  ["Spring ApplicationEvent · EventLog", "Spring ApplicationEvent · EventLog"],
  ["Timefold Solver · 11 Hard + 2 Soft", "Timefold Solver · 11개 Hard + 2개 Soft"],
  ["bulk optimization · SolutionConverter", "일괄 최적화 · SolutionConverter"],
  ["HA Scheduler · Reminder", "HA 스케줄러 · 리마인더"],
  ["Redis Leader Election", "Redis 리더 선출"],
  ["CircuitBreaker · Retry · Bulkhead", "CircuitBreaker · Retry · Bulkhead"],
  ["Exposed JDBC · Flyway", "Exposed JDBC · Flyway"],
  ["Leader Election · Cache", "리더 선출 · 캐시"],
  ["Testcontainers", "Testcontainers"],
  ["Observability", "관측성"],
  ["Solver Engine", "Solver 엔진"],

  ["Module Overview — clinic-appointment · Gradle multi-module", "모듈 개요 — clinic-appointment · Gradle 멀티 모듈"],
  ["Domain model · Exposed ORM", "도메인 모델 · Exposed ORM"],
  ["16 entities · State machine", "16개 엔티티 · 상태 머신"],
  ["Domain event publishing", "도메인 이벤트 발행"],
  ["EventLog persistence", "EventLog 영속화"],
  ["Timefold Solver AI", "Timefold Solver AI"],
  ["Bulk optimization", "일괄 최적화"],
  ["HA notification scheduler", "HA 알림 스케줄러"],
  ["Resilience4j guards", "Resilience4j 보호 계층"],
  ["Spring Boot 4 REST API", "Spring Boot 4 REST API"],
  ["JWT auth · Flyway · Swagger", "JWT 인증 · Flyway · Swagger"],
  ["Gatling load tests", "Gatling 부하 테스트"],
  ["appointment management UI", "예약 관리 UI"],
  ["depends on", "의존"],

  ["Domain Entity Relationships", "도메인 엔티티 관계"],
  ["entities", "엔티티"],
  ["tables", "테이블"],
  ["Appointment State Machine", "예약 상태 머신"],
  ["states", "상태"],
  ["transitions", "전이"],
  ["unconfirmed", "미확정"],
  ["appointment requested", "예약 요청"],
  ["appointment confirmed", "예약 확정"],
  ["patient checked in", "내원 확인"],
  ["treatment in progress", "진료 중"],
  ["treatment completed", "진료 완료"],
  ["patient no-show", "미내원"],
  ["reschedule pending", "재배정 대기"],
  ["reschedule completed", "재배정 완료"],
  ["cancelled", "취소"],
  ["Reschedule back", "재배정 복귀"],

  ["Domain Event Flow", "도메인 이벤트 흐름"],
  ["Publishers", "발행자"],
  ["Events (Spring ApplicationEvent)", "이벤트 (Spring ApplicationEvent)"],
  ["Subscribers", "구독자"],
  ["persists EventLog", "EventLog 영속화"],
  ["calls NotificationChannel", "NotificationChannel 호출"],
  ["event_type, payload_json", "event_type, payload_json"],

  ["Solver Data Flow", "Solver 데이터 흐름"],
  ["Input — Load from DB", "입력 — DB 로드"],
  ["Planning Domain", "계획 도메인"],
  ["Constraint Evaluation", "제약 평가"],
  ["Timefold Solver Engine", "Timefold Solver 엔진"],
  ["Output — Write Results", "출력 — 결과 저장"],
  ["clinicId, appointmentIds", "clinicId, appointmentIds"],
  ["dateRange", "dateRange"],
  ["load REQUESTED &\\nPENDING_RESCHEDULE", "REQUESTED 및\\nPENDING_RESCHEDULE 로드"],
  ["Problem Facts", "문제 Fact"],
  ["Doctors, Clinics", "Doctors, Clinics"],
  ["Closures, Holidays", "Closures, Holidays"],
  ["DB records →", "DB 레코드 →"],
  ["doctorId, date, startTime = planning vars", "doctorId, date, startTime = 계획 변수"],
  ["Pinned if CONFIRMED+", "CONFIRMED 이상이면 고정"],
  ["AppointmentPlanning list", "AppointmentPlanning 목록"],
  ["Hard Constraints", "Hard 제약"],
  ["business hours, doctor schedule", "영업 시간, 의사 일정"],
  ["absence, closure, holiday", "부재, 휴무, 공휴일"],
  ["capacity, equipment, provider match", "수용량, 장비, 제공자 일치"],
  ["Soft Constraints", "Soft 제약"],
  ["doctor load balance", "의사 부하 균형"],
  ["schedule gap minimize", "일정 간격 최소화"],
  ["Timefold ConstraintProvider", "Timefold ConstraintProvider"],
  ["termination config", "종료 설정"],
  ["move filters", "move 필터"],
  ["local search · tabu search", "local search · tabu search"],
  ["score evaluation", "점수 평가"],
  ["optimized assignments", "최적화된 배정"],
  ["assignments: Map<Long, Assignment>", "assignments: Map<Long, Assignment>"],
  ["appointmentId → (doctorId, date, time)", "appointmentId → (doctorId, date, time)"],
  ["receives SolverResult", "SolverResult 수신"],
  ["calls AppointmentRepository.save()", "AppointmentRepository.save() 호출"],
  ["updated appointments", "갱신된 appointments"],
  ["Exposed JDBC transaction", "Exposed JDBC transaction"],

  ["HA Notification Flow", "HA 알림 흐름"],
  ["Event Sources", "이벤트 소스"],
  ["Notification Module", "알림 모듈"],
  ["Leader Election (HA)", "리더 선출 (HA)"],
  ["Resilience4j Guards", "Resilience4j 보호 계층"],
  ["Notification Channels", "알림 채널"],
  ["Persistence", "영속화"],
  ["publishes domain events", "도메인 이벤트 발행"],
  ["via Spring ApplicationEvent", "Spring ApplicationEvent 사용"],
  ["tomorrow + same-day CONFIRMED", "내일 및 당일 CONFIRMED"],
  ["reschedule completion events", "재배정 완료 이벤트"],
  ["closure-triggered", "휴무 트리거"],
  ["Created/StatusChanged/Cancelled/Rescheduled", "Created/StatusChanged/Cancelled/Rescheduled"],
  ["Spring @Configuration", "Spring @Configuration"],
  ["registers notification beans", "알림 Bean 등록"],
  ["Redis SETNX", "Redis SETNX"],
  ["prevents duplicate sends", "중복 전송 방지"],
  ["if (!leaderElection.isLeader()) return", "if (!leaderElection.isLeader()) return"],
  ["single node runs scheduler", "단일 노드만 스케줄러 실행"],
  ["SETNX key, TTL=60s", "SETNX key, TTL=60s"],
  ["periodic heartbeat", "주기적 heartbeat"],
  ["Redis Server", "Redis 서버"],
  ["Leader key storage", "리더 키 저장소"],
  ["cluster-safe", "클러스터 안전"],
  ["failure rate threshold 50%", "실패율 임계값 50%"],
  ["30s open state wait", "30초 open 상태 대기"],
  ["max 3 attempts", "최대 3회 시도"],
  ["1s wait between", "1초 간격 대기"],
  ["max 10 concurrent calls", "최대 10개 동시 호출"],
  ["prevents cascade", "연쇄 장애 방지"],
  ["wraps channel", "채널 래핑"],
  ["with all 3 guards", "3개 보호 계층 적용"],
  ["logs + stores history", "로그 및 이력 저장"],
  ["always returns SUCCESS", "항상 SUCCESS 반환"],
  ["Future: KakaoTalk / Email / SMS", "향후: KakaoTalk / Email / SMS"],
  ["implement NotificationChannel interface", "NotificationChannel 인터페이스 구현"],
  ["stores send history", "전송 이력 저장"],
]);

function l(locale, en, ko) {
  return locale === "ko" ? ko : en;
}

function applyKoreanText(dot) {
  let localized = dot;
  for (const [en, ko] of KO_TEXT.entries()) {
    localized = localized.split(en).join(ko);
  }
  return localized;
}

function applyLocaleFonts(dot, locale) {
  const font = fonts[locale] ?? fonts.en;
  return dot
    .replace(/fontname="Helvetica Neue" fontsize=12/g, `fontname="${font.title}" fontsize=12`)
    .replace(/fontname="Helvetica Neue"/g, `fontname="${font.body}"`);
}

function rendererSafeSeparators(text) {
  return text
    .replace(/ · /g, " | ")
    .replace(/→/g, "->");
}

function dotForLocale(dot, locale) {
  const localized = locale === "ko" ? applyKoreanText(dot) : dot;
  return applyLocaleFonts(rendererSafeSeparators(localized), locale);
}

// ─── Runtime helpers ──────────────────────────────────────────────────────────
function run(cmd, args) {
  const r = spawnSync(cmd, args, {
    cwd: root,
    stdio: ["ignore", "pipe", "pipe"],
    env: process.env,
  });
  return { ok: r.status === 0, stderr: r.stderr?.toString() ?? "" };
}

function dotRun(dotContent, fmt, outPath) {
  const tmp = `/tmp/clin_${Date.now()}_${Math.random().toString(36).slice(2)}.dot`;
  fs.writeFileSync(tmp, dotContent);
  const r = run("/opt/homebrew/bin/dot", [`-T${fmt}`, tmp, "-o", outPath]);
  try { fs.unlinkSync(tmp); } catch {}
  return r;
}

function renderPng(svgPath, pngPath) {
  let r = run("cairosvg", [svgPath, "-o", pngPath, "-s", "2"]);
  if (!r.ok) r = run(path.join(process.env.HOME ?? "", ".local/bin/cairosvg"), [svgPath, "-o", pngPath, "-s", "2"]);
  return r.ok;
}

function saveDot(slug, dotContent, dir = outDir) {
  fs.mkdirSync(dir, { recursive: true });
  let allOk = true;
  for (const locale of locales) {
    const localizedSlug = `${slug}-${locale}`;
    const svgPath = path.join(dir, `${localizedSlug}.svg`);
    const pngPath = path.join(dir, `${localizedSlug}.png`);
    const r = dotRun(dotForLocale(dotContent, locale), "svg", svgPath);
    if (!r.ok) {
      console.error(`  ✗ ${localizedSlug}: dot error\n    ${r.stderr.slice(0, 200)}`);
      allOk = false;
      continue;
    }
    const ok = renderPng(svgPath, pngPath);
    console.log(`  ${ok ? "✓" : "⚠ PNG fail"}  ${localizedSlug}.svg + .png`);
    allOk = allOk && ok;
  }
  return allOk;
}

// ─── Common DOT preamble ──────────────────────────────────────────────────────
const GRAPH = `bgcolor="#F7F9FC" pad=0.8 nodesep=0.85 ranksep=1.1 fontname="Helvetica Neue" fontsize=12`;
const NODES = `node [style="filled,rounded" shape=box fontname="Helvetica Neue" fontsize=11 margin="0.22,0.12" penwidth=2]`;
const EDGES = `edge [fontname="Helvetica Neue" fontsize=9 arrowsize=0.75 penwidth=1.8 color="#758297"]`;

// ─── 1. Root System Architecture ──────────────────────────────────────────────
function genArchitecture() {
  const dot = `digraph {
  graph [${GRAPH} rankdir=TB splines=ortho
    label="Clinic Appointment — System Architecture\\nKotlin 2.3 · Spring Boot 4 · Timefold Solver · Redis"
    labelloc=t labeljust=l]
  ${NODES}
  ${EDGES}

  { rank=same; fe; rest_cli; swagger; gatling; }
  { rank=same; api; sec; exh; }
  { rank=same; core; evt; }
  { rank=same; solver; solver_svc; }
  { rank=same; notif; redis_leader; r4j; }
  { rank=same; pg; redis; docker; micrometer; timefold_ai; }

  fe           [label="Angular 18 SPA\\nappointment-frontend"                          ${nc("blue")}]
  rest_cli     [label="REST Client\\nHTTP / JWT Bearer"                                ${nc("blue")}]
  swagger      [label="Swagger UI\\nspringdoc-openapi"                                 ${nc("blue")}]
  gatling      [label="Gatling Tests\\nload simulation"                                ${nc("blue")}]

  api          [label="appointment-api\\nSpring Boot 4 MVC · JWT · Flyway"            ${nc("green")}]
  sec          [label="SecurityConfig\\nJwtAuthenticationFilter"                       ${nc("green")}]
  exh          [label="GlobalExceptionHandler\\nApiResponse envelope"                  ${nc("green")}]

  core         [label="appointment-core\\n16 entities · Exposed ORM · State Machine · Slot Calc" ${nc("teal")}]
  evt          [label="appointment-event\\nSpring ApplicationEvent · EventLog"         ${nc("teal")}]

  solver       [label="appointment-solver\\nTimefold Solver · 11 Hard + 2 Soft"       ${nc("amber")}]
  solver_svc   [label="SolverService\\nbulk optimization · SolutionConverter"          ${nc("amber")}]

  notif        [label="appointment-notification\\nHA Scheduler · Reminder"             ${nc("pink")}]
  redis_leader [label="Redis Leader Election\\nbluetape4k-leader"                      ${nc("pink")}]
  r4j          [label="Resilience4j\\nCircuitBreaker · Retry · Bulkhead"               ${nc("pink")}]

  pg           [label="PostgreSQL\\nExposed JDBC · Flyway"  ${nc("gray")}]
  redis        [label="Redis\\nLeader Election · Cache"      ${nc("gray")}]
  docker       [label="Docker\\nTestcontainers"              ${nc("gray")}]
  micrometer   [label="Micrometer\\nObservability"           ${nc("gray")}]
  timefold_ai  [label="Timefold AI\\nSolver Engine"          ${nc("gray")}]

  fe           -> api          [color="#58A978"]
  api          -> core         [color="#45A7A1"]
  api          -> evt          [color="#45A7A1" style=dashed]
  core         -> solver       [color="#D6A441"]
  core         -> notif        [color="#DC6B82"]
  notif        -> redis_leader [color="#DC6B82"]
  notif        -> r4j          [color="#DC6B82"]
  api          -> pg           [style=dashed color="#9AA8B8"]
  api          -> redis        [style=dashed color="#9AA8B8"]
  notif        -> pg           [style=dashed color="#9AA8B8"]
  solver       -> timefold_ai  [style=dashed color="#9AA8B8"]
}`;
  saveDot("clinic-appointment-architecture-01", dot);
}

// ─── 2. Module Overview ───────────────────────────────────────────────────────
function genModuleOverview() {
  const dot = `digraph {
  graph [${GRAPH} rankdir=TB splines=ortho
    label="Module Overview — clinic-appointment · Gradle multi-module"
    labelloc=t labeljust=l]
  ${NODES}
  ${EDGES}

  core   [label="appointment-core\\nDomain model · Exposed ORM\\n16 entities · State machine\\nSlotCalculationService"  ${nc("teal")}]
  evt    [label="appointment-event\\nSpring ApplicationEvent\\nDomain event publishing\\nEventLog persistence"            ${nc("blue")}]
  solver [label="appointment-solver\\nTimefold Solver AI\\n11 hard + 2 soft constraints\\nBulk optimization"             ${nc("amber")}]
  notif  [label="appointment-notification\\nHA notification scheduler\\nRedis Leader Election\\nResilience4j guards"      ${nc("pink")}]
  api    [label="appointment-api\\nSpring Boot 4 REST API\\nJWT auth · Flyway · Swagger\\nGatling load tests"            ${nc("green")}]
  fe     [label="frontend\\nappointment-frontend\\nAngular 18 SPA\\nappointment management UI"                           ${nc("purple")}]

  evt    -> core   [label="depends on" color="#45A7A1"]
  solver -> core   [color="#45A7A1"]
  notif  -> core   [color="#45A7A1"]
  api    -> core   [color="#45A7A1"]
  api    -> evt    [color="#5B8DEF"]
  api    -> solver [color="#D6A441"]
  api    -> notif  [color="#DC6B82"]
  fe     -> api    [label="HTTP REST" style=dashed color="#8A72D6"]
}`;
  saveDot("root-readme-overview-01", dot);
}

// ─── 3. Core ERD ──────────────────────────────────────────────────────────────
function genCoreErd() {
  const dot = `digraph {
  graph [${GRAPH} rankdir=TB splines=ortho
    label="Domain Entity Relationships\\nappointment-core · 16 entities · scheduling_* tables · Exposed ORM"
    labelloc=t labeljust=l]
  node [style=filled shape=record fontname="Helvetica Neue" fontsize=10 margin="0.18,0.08" penwidth=2]
  ${EDGES}

  clinics [label="{scheduling_clinics|id: Long (PK)\\l| tenant_group_id (FK)\\lname, timezone, locale\\lslot_duration_minutes\\lmax_concurrent_patients, open_on_holidays\\l}" ${nc("teal")}]
  doctors [label="{scheduling_doctors|id: Long (PK)\\l| clinic_id (FK)\\lname, specialty, provider_type\\lmax_concurrent_patients\\l}" ${nc("blue")}]
  treatment_types [label="{scheduling_treatment_types|id: Long (PK)\\l| clinic_id (FK)\\lname, category\\ldefault_duration_minutes\\lrequired_provider_type, requires_equipment\\l}" ${nc("green")}]
  equipments [label="{scheduling_equipments|id: Long (PK)\\l| clinic_id (FK)\\lname\\lusage_duration_minutes, quantity\\l}" ${nc("olive")}]
  appointments [label="{scheduling_appointments|id: Long (PK)\\l| clinic_id, doctor_id (FK)\\l| treatment_type_id, equipment_id (FK)\\lpatient_name, patient_phone\\lappointment_date, start_time, end_time\\lstatus: AppointmentState\\lreschedule_from_id\\l}" ${nc("pink")}]
  operating_hours [label="{scheduling_operating_hours|id: Long (PK)\\l| clinic_id (FK)\\lday_of_week\\lopen_time, close_time, is_active\\l}" ${nc("amber")}]
  doctor_schedules [label="{scheduling_doctor_schedules|id: Long (PK)\\l| doctor_id (FK)\\lday_of_week, start_time, end_time\\l}" ${nc("blue")}]
  doctor_absences [label="{scheduling_doctor_absences|id: Long (PK)\\l| doctor_id (FK)\\labsence_date\\lstart_time, end_time (opt)\\l}" ${nc("blue")}]
  clinic_closures [label="{scheduling_clinic_closures|id: Long (PK)\\l| clinic_id (FK)\\lclosure_date, is_full_day\\lstart_time, end_time (opt)\\l}" ${nc("amber")}]
  holidays [label="{scheduling_holidays|id: Long (PK)\\lholiday_date, name, is_recurring\\l}" ${nc("amber")}]
  equip_unavail [label="{scheduling_equipment_unavailabilities|id: Long (PK)\\l| equipment_id (FK)\\lstart_datetime, end_datetime\\lrecurrence_rule\\l}" ${nc("olive")}]
  resched_cand [label="{scheduling_reschedule_candidates|id: Long (PK)\\l| appointment_id, clinic_id (FK)\\lproposed_date, proposed_start_time\\lstatus, score\\l}" ${nc("purple")}]
  state_history [label="{scheduling_appointment_state_history|id: Long (PK)\\l| appointment_id (FK)\\lfrom_state, to_state, event\\loccurred_at\\l}" ${nc("gray")}]

  doctors         -> clinics           [label="clinic_id"         color="#45A7A1"]
  treatment_types -> clinics           [label="clinic_id"         color="#45A7A1"]
  equipments      -> clinics           [label="clinic_id"         color="#45A7A1"]
  operating_hours -> clinics           [label="clinic_id"         color="#45A7A1"]
  clinic_closures -> clinics           [label="clinic_id"         color="#45A7A1"]
  appointments    -> clinics           [label="clinic_id"         color="#45A7A1"]
  appointments    -> doctors           [label="doctor_id"         color="#5B8DEF"]
  appointments    -> treatment_types   [label="treatment_type_id" color="#58A978"]
  appointments    -> equipments        [label="equipment_id"      color="#8BA84D"]
  doctor_schedules -> doctors          [label="doctor_id"         color="#5B8DEF"]
  doctor_absences  -> doctors          [label="doctor_id"         color="#5B8DEF"]
  equip_unavail    -> equipments       [label="equipment_id"      color="#8BA84D"]
  resched_cand     -> appointments     [label="appointment_id"    color="#DC6B82"]
  state_history    -> appointments     [label="appointment_id"    color="#9AA8B8"]
}`;
  saveDot("appointment-core-erd-01", dot);
}

// ─── 4. State Machine ─────────────────────────────────────────────────────────
function genStateMachine() {
  const dot = `digraph {
  graph [${GRAPH} rankdir=TB splines=ortho
    label="Appointment State Machine\\n10 states · 11 transitions · AppointmentStateMachine.kt"
    labelloc=t labeljust=l]
  ${NODES}
  ${EDGES}

  PENDING            [label="PENDING\\nunconfirmed"              ${nc("gray")}]
  REQUESTED          [label="REQUESTED\\nappointment requested"  ${nc("blue")}]
  CONFIRMED          [label="CONFIRMED\\nappointment confirmed"  ${nc("green")}]
  CHECKED_IN         [label="CHECKED_IN\\npatient checked in"    ${nc("teal")}]
  IN_PROGRESS        [label="IN_PROGRESS\\ntreatment in progress" ${nc("amber")}]
  COMPLETED          [label="COMPLETED\\ntreatment completed"    ${nc("green")}]
  NO_SHOW            [label="NO_SHOW\\npatient no-show"          ${nc("pink")}]
  PENDING_RESCHEDULE [label="PENDING_RESCHEDULE\\nreschedule pending" ${nc("purple")}]
  RESCHEDULED        [label="RESCHEDULED\\nreschedule completed" ${nc("teal")}]
  CANCELLED          [label="CANCELLED\\ncancelled"              ${nc("pink")}]

  PENDING            -> REQUESTED          [label="Request"           color="#5B8DEF"]
  REQUESTED          -> CONFIRMED          [label="Confirm"           color="#58A978"]
  CONFIRMED          -> CHECKED_IN         [label="CheckIn"           color="#45A7A1"]
  CHECKED_IN         -> IN_PROGRESS        [label="StartTreatment"    color="#D6A441"]
  IN_PROGRESS        -> COMPLETED          [label="Complete"          color="#58A978"]
  CONFIRMED          -> NO_SHOW            [label="MarkNoShow"        color="#DC6B82"]
  CONFIRMED          -> PENDING_RESCHEDULE [label="RequestReschedule" color="#8A72D6"]
  REQUESTED          -> PENDING_RESCHEDULE [label="RequestReschedule" color="#8A72D6"]
  PENDING_RESCHEDULE -> RESCHEDULED        [label="ConfirmReschedule" color="#45A7A1"]
  CONFIRMED          -> PENDING            [label="Reschedule back"   color="#5B8DEF" style=dashed]

  REQUESTED          -> CANCELLED          [label="Cancel" color="#DC6B82" style=dashed]
  CONFIRMED          -> CANCELLED          [color="#DC6B82" style=dashed]
  CHECKED_IN         -> CANCELLED          [color="#DC6B82" style=dashed]
  PENDING_RESCHEDULE -> CANCELLED          [color="#DC6B82" style=dashed]
}`;
  saveDot("appointment-core-architecture-02", dot);
}

// ─── 5. Event Flow ────────────────────────────────────────────────────────────
function genEventFlow() {
  const dot = `digraph {
  graph [${GRAPH} rankdir=LR splines=ortho
    label="Domain Event Flow\\nappointment-event · Spring ApplicationEvent · EventLog persistence"
    labelloc=t labeljust=l]
  ${NODES}
  ${EDGES}

  subgraph cluster_pub {
    label="Publishers" ${cc("green")} fontname="Helvetica Neue" fontsize=11
    ctrl    [label="AppointmentController\\nPOST /appointments\\nPATCH .../status"  ${nc("green")}]
    resched [label="RescheduleController\\nPOST .../reschedule"                     ${nc("green")}]
    svc     [label="Domain Services\\nClosureRescheduleService"                     ${nc("teal")}]
  }

  subgraph cluster_events {
    label="Events (Spring ApplicationEvent)" ${cc("blue")} fontname="Helvetica Neue" fontsize=11
    ev_created   [label="Created\\nappointmentId, clinicId"     ${nc("blue")}]
    ev_status    [label="StatusChanged\\nfromState → toState"   ${nc("blue")}]
    ev_cancelled [label="Cancelled\\nappointmentId, reason"     ${nc("pink")}]
    ev_resched   [label="Rescheduled\\noriginalId, newId"       ${nc("teal")}]
  }

  subgraph cluster_sub {
    label="Subscribers" ${cc("teal")} fontname="Helvetica Neue" fontsize=11
    logger   [label="AppointmentEventLogger\\n@EventListener\\npersists EventLog"          ${nc("teal")}]
    notif_l  [label="NotificationEventListener\\n@EventListener\\ncalls NotificationChannel" ${nc("pink")}]
    ev_table [label="AppointmentEventLogs\\nExposed table\\nevent_type, payload_json"       ${nc("gray")}]
  }

  ctrl    -> ev_created   [color="#5B8DEF"]
  ctrl    -> ev_status    [color="#5B8DEF"]
  ctrl    -> ev_cancelled [color="#DC6B82"]
  resched -> ev_resched   [color="#45A7A1"]
  svc     -> ev_cancelled [color="#DC6B82" style=dashed]

  ev_created   -> logger  [color="#45A7A1"]
  ev_status    -> logger  [color="#45A7A1"]
  ev_created   -> notif_l [color="#DC6B82"]
  ev_status    -> notif_l [color="#DC6B82"]
  ev_cancelled -> notif_l [color="#DC6B82"]
  ev_resched   -> notif_l [color="#DC6B82"]

  logger -> ev_table [color="#9AA8B8"]
}`;
  saveDot("appointment-event-architecture-01", dot);
}

// ─── 6. Solver Data Flow ──────────────────────────────────────────────────────
function genSolverFlow() {
  const dot = `digraph {
  graph [${GRAPH} rankdir=LR splines=ortho
    label="Solver Data Flow\\nappointment-solver · Timefold AI · 11 Hard + 2 Soft constraints"
    labelloc=t labeljust=l]
  ${NODES}
  ${EDGES}

  subgraph cluster_input {
    label="Input — Load from DB" ${cc("blue")} fontname="Helvetica Neue" fontsize=11
    svc_call [label="SolverService.solve()\\nclinicId, appointmentIds\\ndateRange"             ${nc("blue")}]
    repo     [label="AppointmentRepository\\nload REQUESTED &\\nPENDING_RESCHEDULE"           ${nc("blue")}]
    facts    [label="Problem Facts\\nDoctors, Clinics\\nClosures, Holidays\\nEquipUnavail"    ${nc("blue")}]
    conv     [label="SolutionConverter\\nDB records →\\nAppointmentPlanning"                  ${nc("blue")}]
  }

  subgraph cluster_domain {
    label="Planning Domain" ${cc("teal")} fontname="Helvetica Neue" fontsize=11
    entity   [label="AppointmentPlanning\\n@PlanningEntity\\ndoctorId, date, startTime = planning vars\\nPinned if CONFIRMED+" ${nc("teal")}]
    solution [label="ScheduleSolution\\n@PlanningSolution\\nAppointmentPlanning list\\nProblemFacts, scoreHolder"               ${nc("teal")}]
  }

  subgraph cluster_constraints {
    label="Constraint Evaluation" ${cc("amber")} fontname="Helvetica Neue" fontsize=11
    hard     [label="Hard Constraints (11)\\nbusiness hours, doctor schedule\\nabsence, closure, holiday\\ncapacity, equipment, provider match" ${nc("amber")}]
    soft     [label="Soft Constraints (2)\\ndoctor load balance\\nschedule gap minimize"                                                        ${nc("amber")}]
    cprov    [label="AppointmentConstraintProvider\\nTimefold ConstraintProvider\\nH1–H11, S1–S2"                                               ${nc("amber")}]
  }

  subgraph cluster_engine {
    label="Timefold Solver Engine" ${cc("green")} fontname="Helvetica Neue" fontsize=11
    config   [label="SolverConfig\\ntermination config\\nmove filters"                           ${nc("green")}]
    engine   [label="Timefold Solver Engine\\nlocal search · tabu search\\nscore evaluation"    ${nc("green")}]
    best_sol [label="BestSolution\\noptimized assignments\\nHardSoftScore"                       ${nc("green")}]
  }

  subgraph cluster_output {
    label="Output — Write Results" ${cc("purple")} fontname="Helvetica Neue" fontsize=11
    result   [label="SolverResult\\nassignments: Map<Long, Assignment>\\nappointmentId → (doctorId, date, time)" ${nc("purple")}]
    caller   [label="SolverController\\nreceives SolverResult\\ncalls AppointmentRepository.save()"              ${nc("purple")}]
    db       [label="PostgreSQL\\nupdated appointments\\nExposed JDBC transaction"                                ${nc("gray")}]
  }

  svc_call -> repo -> facts -> conv
  conv     -> entity
  facts    -> solution
  entity   -> solution
  solution -> hard
  solution -> soft
  hard     -> cprov
  soft     -> cprov
  cprov    -> config -> engine -> best_sol
  best_sol -> result -> caller -> db
}`;
  saveDot("appointment-solver-architecture-01", dot);
}

// ─── 7. Notification HA Flow ──────────────────────────────────────────────────
function genNotificationFlow() {
  const dot = `digraph {
  graph [${GRAPH} rankdir=TB splines=ortho
    label="HA Notification Flow\\nappointment-notification · Redis Leader Election · Resilience4j guards"
    labelloc=t labeljust=l]
  ${NODES}
  ${EDGES}

  subgraph cluster_sources {
    label="Event Sources" ${cc("blue")} fontname="Helvetica Neue" fontsize=11
    ctrl      [label="AppointmentController\\npublishes domain events\\nvia Spring ApplicationEvent"           ${nc("blue")}]
    scheduler [label="AppointmentReminderScheduler\\n@Scheduled(fixedRate=1h)\\ntomorrow + same-day CONFIRMED" ${nc("blue")}]
    resched   [label="RescheduleController\\nreschedule completion events\\nclosure-triggered"                 ${nc("blue")}]
  }

  subgraph cluster_module {
    label="Notification Module" ${cc("pink")} fontname="Helvetica Neue" fontsize=11
    listener [label="NotificationEventListener\\n@EventListener\\nCreated/StatusChanged/Cancelled/Rescheduled" ${nc("pink")}]
    autoconf [label="NotificationAutoConfiguration\\nSpring @Configuration\\nregisters notification beans"      ${nc("pink")}]
    dedup    [label="DuplicateGuard\\nRedis SETNX\\nprevents duplicate sends"                                   ${nc("pink")}]
  }

  subgraph cluster_ha {
    label="Leader Election (HA)" ${cc("purple")} fontname="Helvetica Neue" fontsize=11
    leader  [label="bluetape4k-leader (Redis SETNX)\\nif (!leaderElection.isLeader()) return\\nsingle node runs scheduler" ${nc("purple")}]
    lettuce [label="Lettuce (Redis Client)\\nSETNX key, TTL=60s\\nperiodic heartbeat"                                      ${nc("purple")}]
    redis_s [label="Redis Server\\nLeader key storage\\ncluster-safe"                                                      ${nc("purple")}]
  }

  subgraph cluster_r4j {
    label="Resilience4j Guards" ${cc("amber")} fontname="Helvetica Neue" fontsize=11
    cb     [label="CircuitBreaker\\nfailure rate threshold 50%\\n30s open state wait" ${nc("amber")}]
    retry  [label="Retry\\nmax 3 attempts\\n1s wait between"                          ${nc("amber")}]
    bh     [label="Bulkhead\\nmax 10 concurrent calls\\nprevents cascade"             ${nc("amber")}]
    r_chan [label="ResilientNotificationChannel\\nwraps channel\\nwith all 3 guards"  ${nc("amber")}]
  }

  subgraph cluster_channels {
    label="Notification Channels" ${cc("teal")} fontname="Helvetica Neue" fontsize=11
    dummy   [label="DummyNotificationChannel\\nlogs + stores history\\nalways returns SUCCESS"                     ${nc("teal")}]
    future  [label="Future: KakaoTalk / Email / SMS\\nimplement NotificationChannel interface"                     ${nc("teal")}]
    history [label="NotificationHistoryRepository\\nExposed table\\nstores send history"                          ${nc("teal")}]
  }

  subgraph cluster_persist {
    label="Persistence" ${cc("gray")} fontname="Helvetica Neue" fontsize=11
    pg_hist [label="notification_history (PostgreSQL)" ${nc("gray")}]
    evt_log [label="event_logs (appointment-event)"    ${nc("gray")}]
  }

  ctrl      -> listener  [color="#DC6B82"]
  scheduler -> listener  [color="#DC6B82"]
  resched   -> listener  [color="#DC6B82"]

  listener -> leader  [color="#8A72D6"]
  leader   -> lettuce [color="#8A72D6"]
  lettuce  -> redis_s [color="#8A72D6"]

  listener -> cb -> retry -> bh -> r_chan [color="#D6A441"]

  r_chan  -> dummy   [color="#45A7A1"]
  r_chan  -> future  [color="#45A7A1" style=dashed]
  dummy   -> history [color="#45A7A1"]
  history -> pg_hist [style=dashed color="#9AA8B8"]
}`;
  saveDot("appointment-notification-architecture-01", dot);
}

// ─── 8. API Sequence (manual SVG — Graphviz is poor at sequences) ─────────────
function genApiSequence() {
  // Fonts
  const FONT_DIR = `${process.env.HOME}/Library/Fonts`;

  function fontconfigEnv(locale) {
    const tmpCfg = `/tmp/clinic_fc_${Date.now()}.conf`;
    const font = fonts[locale] ?? fonts.en;
    fs.writeFileSync(tmpCfg, `<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>${FONT_DIR}</dir>
  <alias><family>${font.title}</family><prefer><family>${font.title}</family></prefer></alias>
  <alias><family>${font.body}</family><prefer><family>${font.body}</family></prefer></alias>
</fontconfig>`);
    return { FONTCONFIG_FILE: tmpCfg };
  }

  function run2(cmd, args, locale) {
    const r = spawnSync(cmd, args, {
      cwd: root,
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ...fontconfigEnv(locale) },
    });
    return { ok: r.status === 0, stderr: r.stderr?.toString() ?? "" };
  }

  const P = {
    blue:   { bg: "#E8F3FF", stroke: "#5B8DEF", text: "#1A3A5C" },
    green:  { bg: "#EAF7EF", stroke: "#58A978", text: "#1A3A26" },
    teal:   { bg: "#E9F7F6", stroke: "#45A7A1", text: "#0E3533" },
    pink:   { bg: "#FDECEF", stroke: "#DC6B82", text: "#4A0E1C" },
    purple: { bg: "#F1ECFF", stroke: "#8A72D6", text: "#2A1A5C" },
    gray:   { bg: "#F2F5F9", stroke: "#9AA8B8", text: "#2A3340" },
    canvas: "#F7F9FC", frame: "#FFFFFF", frameStroke: "#D0D8E4",
  };

  function esc(t) {
    return String(t ?? "")
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  for (const locale of locales) {
    const font = fonts[locale] ?? fonts.en;
    const W = 1080, H = 900;
    const parts = [];

    parts.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
<defs>
  <filter id="sh"><feDropShadow dx="0" dy="3" stdDeviation="5" flood-color="#1f2937" flood-opacity="0.10"/></filter>
  <marker id="seqArrow" markerWidth="16" markerHeight="16" viewBox="0 0 10 10" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse">
    <path d="M 0 0 L 10 5 L 0 10 Z" fill="#758297"/>
  </marker>
  <marker id="seqArrowGreen" markerWidth="16" markerHeight="16" viewBox="0 0 10 10" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse">
    <path d="M 0 0 L 10 5 L 0 10 Z" fill="${P.green.stroke}"/>
  </marker>
  <marker id="seqArrowPurple" markerWidth="16" markerHeight="16" viewBox="0 0 10 10" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse">
    <path d="M 0 0 L 10 5 L 0 10 Z" fill="${P.purple.stroke}"/>
  </marker>
  <marker id="seqArrowRed" markerWidth="16" markerHeight="16" viewBox="0 0 10 10" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse">
    <path d="M 0 0 L 10 5 L 0 10 Z" fill="${P.pink.stroke}"/>
  </marker>
  <style>
    text { font-family: "${font.body}","Courier New",monospace; }
    .title { font-family: "${font.title}","Comic Sans MS",cursive; font-size:38px; fill:#102033; }
    .sub   { font-family: "${font.body}","Courier New",monospace; font-size:12px; fill:#536273; }
    .hdr   { font-family: "${font.body}","Courier New",monospace; font-size:11px; }
    .msg   { font-family: "${font.body}","Courier New",monospace; font-size:10px; fill:#536273; }
  </style>
</defs>
<rect width="${W}" height="${H}" fill="${P.canvas}"/>
<rect class="frame" x="24" y="20" width="${W-48}" height="${H-40}" rx="20" fill="${P.frame}" stroke="${P.frameStroke}" stroke-width="1.5"/>`);

    parts.push(`<text class="title" x="56" y="72">${l(locale, "Appointment Creation Flow", "예약 생성 흐름")}</text>`);
    parts.push(`<text class="sub subtitle" x="60" y="96">${l(locale, "POST /api/{tenantCode}/appointments | JWT auth | Exposed transaction | Event publish", "POST /api/{tenantCode}/appointments | JWT 인증 | Exposed transaction | 이벤트 발행")}</text>`);

  // Participants — spaced more evenly
    const ppts = [
      { x: 80,  label: "Frontend",               color: P.purple },
      { x: 216, label: "SecurityFilter",          color: P.green  },
      { x: 368, label: "AppointmentController",   color: P.green  },
      { x: 524, label: "AppointmentService",      color: P.teal   },
      { x: 680, label: "AppointmentRepository",   color: P.teal   },
      { x: 836, label: "EventPublisher",          color: P.blue   },
      { x: 990, label: "PostgreSQL",              color: P.gray   },
    ];

    const lifeY1 = 156, lifeY2 = H - 50;
    for (const p of ppts) {
      const c = p.color;
      const tw = p.label.length * 7.5 + 18;
      const lx = p.x - tw / 2;
      parts.push(`<rect class="header participant" x="${lx}" y="112" width="${tw}" height="40" rx="7" fill="${c.bg}" stroke="${c.stroke}" stroke-width="2" filter="url(#sh)"/>`);
      parts.push(`<text class="hdr participant" x="${p.x}" y="135" text-anchor="middle" dominant-baseline="middle" fill="${c.text}">${esc(p.label)}</text>`);
      parts.push(`<line class="lifeline" x1="${p.x}" y1="${lifeY1}" x2="${p.x}" y2="${lifeY2}" stroke="#C8D4E0" stroke-width="1.5" stroke-dasharray="6 4"/>`);
      parts.push(`<rect class="activation" x="${p.x - 5}" y="208" width="10" height="76" rx="4" fill="${c.bg}" stroke="${c.stroke}" stroke-width="1" opacity="0.72"/>`);
    }

    let y = 182;
    const S = 46; // step

    let msgNo = 1;
    const markerByRole = {
      arr: "seqArrow",
      "arr-g": "seqArrowGreen",
      "arr-p": "seqArrowPurple",
      "arr-k": "seqArrowRed",
    };
    const strokeByRole = {
      arr: "#758297",
      "arr-g": P.green.stroke,
      "arr-p": P.purple.stroke,
      "arr-k": P.pink.stroke,
    };

    function msg(fromX, toX, yy, label, arrowId = "arr", dash = false, retStyle = false) {
      const ax1 = fromX, ax2 = toX;
      const dashAttr = dash ? 'stroke-dasharray="6 4"' : "";
      const stroke = strokeByRole[arrowId] ?? "#758297";
      const markerId = markerByRole[arrowId] ?? "seqArrow";
      parts.push(`<path class="seq ${retStyle ? "ret" : "call"}" d="M${ax1} ${yy} L${ax2} ${yy}" stroke="${stroke}" stroke-width="${retStyle ? 1.6 : 1.8}" ${dashAttr} fill="none" marker-end="url(#${markerId})"/>`);
      const lx = Math.min(ax1, ax2) + Math.abs(ax2 - ax1) * 0.18;
      const tw = Math.min(Math.max(label.length * 6.2 + 10, 64), Math.abs(ax2 - ax1) * 0.72);
      parts.push(`<rect class="label pill" x="${lx - 20}" y="${yy - 21}" width="17" height="17" rx="8.5" fill="white" stroke="${stroke}" stroke-width="1.1" opacity="0.95"/>`);
      parts.push(`<text class="badge" x="${lx - 11.5}" y="${yy - 8.2}" text-anchor="middle">${msgNo++}</text>`);
      parts.push(`<rect x="${lx - 2}" y="${yy - 18}" width="${tw}" height="14" rx="3" fill="white" stroke="#D0D8E4" stroke-width="0.8" opacity="0.92"/>`);
      parts.push(`<text class="msg" x="${lx + 1}" y="${yy - 7}">${esc(label)}</text>`);
    }

    function altBox(ya, yb, label, color) {
      const c = color;
      parts.push(`<rect class="alt branch" x="50" y="${ya}" width="${W - 100}" height="${yb - ya}" rx="5" fill="${c.bg}" fill-opacity="0.12" stroke="${c.stroke}" stroke-width="1"/>`);
      parts.push(`<rect x="50" y="${ya}" width="40" height="16" rx="3" fill="${c.stroke}" opacity="0.65"/>`);
      parts.push(`<text class="msg" x="70" y="${ya + 10}" text-anchor="middle" dominant-baseline="middle" fill="white">${esc(label)}</text>`);
    }

    const [fe, sec, ctrl, svc, repo, evt, db] = ppts.map(p => p.x);

    msg(fe, sec, y, "POST /api/{tenantCode}/appointments", "arr-p"); y += S;

    altBox(y - 10, y + S * 2 - 6, "alt", P.green);
    msg(sec, fe,   y, l(locale, "401 Unauthorized (invalid JWT)", "401 Unauthorized (유효하지 않은 JWT)"), "arr-k", true, true); y += S;
    msg(sec, ctrl, y, l(locale, "SchedulingUserPrincipal (tenantCode validated)", "SchedulingUserPrincipal (tenantCode 검증 완료)"), "arr-g"); y += S;

    msg(ctrl, svc,  y, "validateAndCreate(CreateAppointmentRequest)", "arr"); y += S;
    msg(svc,  repo, y, "findSlotConflicts(clinicId, doctorId, date, time)", "arr"); y += S;
    msg(repo, db,   y, l(locale, "SELECT appointments WHERE doctor + date overlap", "SELECT appointments WHERE doctor + date overlap"), "arr"); y += S;
    msg(db,   repo, y, l(locale, "conflicting: List<AppointmentRecord>", "conflicting: List<AppointmentRecord>"), "arr", true, true); y += S;

    altBox(y - 10, y + S * 2 - 6, "alt", P.pink);
    msg(repo, svc, y, "ConflictDetectedException (409 Conflict)", "arr-k", true, true); y += S;
    msg(repo, svc, y, l(locale, "OK (no conflict)", "OK (충돌 없음)"), "arr-g", true, true); y += S;

    msg(svc,  repo, y, "save(AppointmentRecord)", "arr"); y += S;
    msg(repo, db,   y, "INSERT scheduling_appointments", "arr"); y += S;
    msg(db,   repo, y, l(locale, "id: Long (new appointmentId)", "id: Long (new appointmentId)"), "arr", true, true); y += S;

    msg(svc,  evt,  y, "publishEvent(AppointmentDomainEvent.Created)", "arr"); y += S;
    msg(evt,  ctrl, y, "AppointmentResponse(id, status=REQUESTED, timezone)", "arr-g", true, true); y += S;
    msg(ctrl, fe,   y, "201 Created {id, appointmentDate, startTime, timezone}", "arr-g", true, true);

    // Footer
    parts.push(`<text class="msg" x="${W/2}" y="${H - 25}" text-anchor="middle">appointment-api · github.com/bluetape4k/clinic-appointment</text>`);
    parts.push(`</svg>`);

    const svgContent = parts.join("\n");
    fs.mkdirSync(outDir, { recursive: true });
    const svgPath = path.join(outDir, `appointment-api-sequence-01-${locale}.svg`);
    const pngPath = path.join(outDir, `appointment-api-sequence-01-${locale}.png`);
    fs.writeFileSync(svgPath, svgContent, "utf8");

    let r = run2("cairosvg", [svgPath, "-o", pngPath, "-s", "2"], locale);
    if (!r.ok) r = run2(path.join(process.env.HOME ?? "", ".local/bin/cairosvg"), [svgPath, "-o", pngPath, "-s", "2"], locale);
    console.log(`  ${r.ok ? "✓" : "⚠ PNG fail"}  appointment-api-sequence-01-${locale}.svg + .png`);
  }
}

// ─── Main ─────────────────────────────────────────────────────────────────────
const args = process.argv.slice(2);
const modFilter = args.indexOf("--module") >= 0 ? args[args.indexOf("--module") + 1] : null;
const go = (name, fn) => { if (!modFilter || modFilter === name) fn(); };

fs.mkdirSync(outDir,    { recursive: true });

console.log("clinic-appointment diagram generator (Graphviz)");
console.log(`Output: ${outDir}`);
console.log("");

go("root",         () => { console.log("▶ Root Architecture + Module Overview"); genArchitecture(); genModuleOverview(); });
go("core",         () => { console.log("▶ appointment-core: ERD + State Machine"); genCoreErd(); genStateMachine(); });
go("event",        () => { console.log("▶ appointment-event: Event Flow"); genEventFlow(); });
go("solver",       () => { console.log("▶ appointment-solver: Data Flow"); genSolverFlow(); });
go("notification", () => { console.log("▶ appointment-notification: HA Flow"); genNotificationFlow(); });
go("api",          () => { console.log("▶ appointment-api: Sequence"); genApiSequence(); });
