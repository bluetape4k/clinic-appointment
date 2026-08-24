# Issue #204 Type-E Workflow Checklist

상태 기준: `[ ]` 미증명, `[x]` 최신 증거로 PASS, `N/A`는 범위 근거를
함께 기록한다. 이 작업은 production-like container-backed simulation과
검증 문서만 다루며 production notification 동작은 변경하지 않는다.

## 승인된 범위

- Issue: [#204](https://github.com/bluetape4k/clinic-appointment/issues/204)
- 유형: Type-E maintenance
- 기준 ref: `develop` / `b2af9b4f20782c9f5e5773a73c9f3217a6eaf1fa`
- 작업 branch: `chore/issue-204-notification-canary`
- worktree: `.worktrees/issue-204-notification-canary`
- 목표: PostgreSQL·Redis·Kafka singleton launcher와 deterministic provider stub으로
  bounded fixed-window 1,000건 outbox simulation, rollback, queue 보존, redacted evidence를 고정한다.
- 제외: live provider/credential/traffic, 실제 24시간 운영, frontend, production route 전환,
  legacy table drop, 새로운 production dependency/module

## Router

- [x] **WF-00 — AGENTS.md 계층 읽기**
  - **Action:** 사용자·workspace·repository 기준 정보를 순서대로 읽는다.
  - **Evidence:** `/Users/debop/.codex/AGENTS.md`, `/Users/debop/work/bluetape4k/.github/docs/workspace/AGENTS.md`, repository `AGENTS.md`를 승인 전 읽었다.
  - **Failure:** 기준 정보 누락 시 변경을 중지한다.
- [x] **WF-01 — 작업 유형 분류**
  - **Action:** Issue #204와 현재 repository evidence를 기준으로 Type-E를 확정한다.
  - **Evidence:** production behavior 없이 test/evidence/docs를 추가하는 maintenance 범위다.
  - **Failure:** 유형이 바뀌면 해당 leaf workflow로 재분류한다.
- [x] **WF-02 — 첫 실행 계획 작성**
  - **Action:** 파일·명령·Expected DoD가 있는 ordered plan을 제시한다.
  - **Evidence:** 2026-08-24 현재 thread에서 issue 후보·범위·검증·PR/merge hold를 제시했다.
  - **Failure:** 계획 없이 durable mutation하지 않는다.
- [x] **WF-03 — 첫 계획 승인**
  - **Action:** 사용자의 명시 승인을 기록한다.
  - **Evidence:** 현재 thread의 `승인` 응답.
  - **Failure:** 승인 전 source mutation하지 않는다.
- [x] **WF-04 — 실행 계약 로드**
  - **Action:** Type-E leaf와 triggered Kotlin/testing/writer/common/hazard references를 읽는다.
  - **Evidence:** `bluetape-maintenance`, `bluetape-kotlin-patterns`, `bluetape-writer`, Kotlin `testing.md`/`spring-boot.md`/`checklist.md`, `common-gates.md`, `repository-hazards.md`, `topology-contract.md`, `liveness-contract.md`, `workflow-manifest.json`을 읽었다.
  - **Failure:** 필요한 계약이 없으면 구현을 중지한다.
- [x] **WF-04A — machine-readable run 초기화**
  - **Action:** `bluetape-flow.py`로 현재 session에 바인딩된 Type-E run을 초기화하고 topology를 등록한다.
- **Evidence:** run `20260824T015907Z-1c7da0a3`, state root `.bluetape`, workflow type `E`, components `outbox-simulation`, `verification-report`, `documentation`, topology receipt sequence `11`.
  - **Failure:** helper 실패 시 문서 checklist 경로와 runtime gap을 기록한다.
- [x] **WF-05 — 의존 순서 게이트 실행**
  - **Action:** checklist physical order대로 선행 증거 후 dependent gate를 실행한다.
- **Evidence:** RED fixture 누락 실패 → validator GREEN 2 passing → container-backed simulation 1 passing → final SHADOW assertion 보강 → exact-class combined 3 passing → redacted report/docs 순서로 실행했다.
  - **Failure:** FAIL/PENDING row의 dependent 작업을 실행하지 않는다.
- [x] **WF-06 — 누락·약한 gate 복구**
  - **Action:** 누락·실패 증거를 원인 수정 후 재실행한다.
- **Evidence:** validator `fieldNames()` compile 오류와 integration test compile 오류를 각각 수정하고, rollout 마지막 SHADOW assertion을 보강한 뒤 `compileTestKotlin` 및 exact-class combined test를 fresh rerun했다.
  - **Failure:** 안전한 복구가 없으면 BLOCKED로 남긴다.

## Common Gates

- [x] **CL-01 — mutation 전 checklist 생성**
  - **Action:** router/common/leaf rows를 source mutation 전에 인스턴스화한다.
  - **Evidence:** 이 파일을 worktree source mutation 전에 생성했다.
  - **Failure:** checklist가 없으면 구현을 중지한다.
- [x] **CL-02 — 모든 row 분류**
  - **Action:** required, conditional, N/A를 각 row에 결정한다.
  - **Evidence:** live rollout/frontend/HTTP·HC5/visual/chart는 범위 밖이며 아래 N/A 근거에 기록한다.
  - **Failure:** 미분류 row는 required unchecked로 취급한다.
- [x] **CL-03 — 의존 순서 준수**
  - **Action:** contract/readiness → RED → implementation → container validation → docs → pre-PR 순서로 실행한다.
  - **Evidence:** checklist → RED → implementation → PostgreSQL/Redis/Kafka validation → docs → pre-PR scan 순서를 지켰다.
  - **Failure:** 순서가 어긋나면 affected proof를 재실행한다.
- [x] **CL-04 — 증거 즉시 기록**
  - **Action:** gate 확인 직후 command/file/result를 checklist와 report에 기록한다.
  - **Evidence:** `docs/benchmarks/issue-204-notification-canary/2026-08-24/production-like-report.json`, review/lesson, 아래 command ledger에 실행 시각·결과·redaction을 기록했다.
  - **Failure:** 뒤늦게 재구성하지 말고 unchecked로 남긴다.
- [x] **CL-05 — fail closed**
  - **Action:** PENDING/FAIL row의 dependent branch를 차단한다.
  - **Evidence:** fixture 누락 RED와 compile 오류를 dependent gate 전에 차단하고, 수정 후에만 다음 gate를 진행했다.
  - **Failure:** dependent가 실행됐다면 affected proof를 새로 수집한다.
- [x] **CL-06 — skip/reorder 복구**
  - **Action:** 누락 row를 복구하고 이후 증거를 새로 수집한다.
- **Evidence:** workflow receipt run `20260824T015907Z-1c7da0a3`, exact-class compile/test rerun 결과 `3 passing`, `BUILD SUCCESSFUL`.
  - **Failure:** 최종 상태를 BLOCKED로 유지한다.
- [ ] **CL-07 — irreversible hold refresh**
  - **Action:** PR 생성·merge 직전에 authority와 exact target/head를 재확인한다.
  - **Evidence:** live issue/PR metadata, CI, review, head SHA.
  - **Failure:** hold가 stale이면 side effect를 실행하지 않는다.
- [x] **CL-08 — 완료 count 산출**
  - **Action:** required checks, N/A, Blocked, unchecked IDs를 산출한다.
  - **Evidence:** workflow `complete` sequence `22`, run state `completed`, `completion-check`에서 failed/missing lane·component 없음; 남은 unchecked는 pre-PR/PR/merge 권한 hold다.
  - **Failure:** count 불일치 시 완료를 주장하지 않는다.

## Type-E

- [x] **E-01 — Route support skills**
  - **Action:** Kotlin/testing/writer support surface를 적용하고 visual 필요성을 판정한다.
  - **Evidence:** 관련 skill과 references를 로드했다. 차트/diagram은 측정 시각화가 아닌 redacted report 계약이므로 N/A다.
  - **Failure:** missing route는 편집을 중지한다.
- [x] **E-02 — 현재 guidance와 source 조사**
  - **Action:** Issue #204, 기존 readiness/lesson, outbox worker/store/metrics, launcher와 테스트를 확인한다.
  - **Evidence:** `docs/review/2026-08-08-issue-204-notification-outbox-readiness.md`, `docs/lessons/2026-08-08-issue-204-readiness-boundary.md`, `appointment-notification/src/main`, `src/test` 및 live GitHub Issue #204.
  - **Failure:** authority가 불명확하면 read-only로 남긴다.
- [x] **E-03 — production behavior와 ownership 보존**
  - **Action:** test/evidence/docs 범위만 변경하고 root dirty state와 관리 대상 surfaces를 제외한다.
  - **Evidence:** 변경 파일은 `appointment-api/src/test/**`, test resource, `docs/**`뿐이며 production `src/main`, migration, root dirty files를 수정하지 않았다.
  - **Failure:** production behavior 변경이면 Type A/B로 재분류한다.
- [x] **E-04 — 적용·parity 증명**
  - **Action:** repository-local artifact의 source/live parity를 판정한다.
  - **Evidence:** 변경 예정 문서·테스트는 chezmoi 관리 대상이 아니므로 source/live parity는 N/A이며, report는 생성본과 durable 문서를 read-back한다.
  - **Failure:** live-only success는 FAIL이다.
- [x] **E-05 — maintenance verification**
  - **Action:** diff check, redaction/reference scan, validator, Gradle/Testcontainers 검증을 수행한다.
  - **Evidence:** exact-class combined Gradle `3 passing` (`BUILD SUCCESSFUL`), fixture refresh 후 validator `2 passing`, generated/durable report JSON·redaction scan, writer terminology audit `findings=0`, `git diff --check`를 통과했다.
  - **Failure:** unchecked 상태로 commit하지 않는다.
- [x] **E-06 — durable pre-PR proof**
  - **Action:** final diff, language, redaction, lesson, pruning, P0/P1 review를 수렴한다.
  - **Evidence:** final report/checklist, inline review P0=0/P1=0/P3=0, P2 acceptance gaps 5건을 PENDING으로 명시, exact local head.
  - **Failure:** PR 생성 전에 repair한다.
- [ ] **E-07 — common PR gates**
  - **Action:** PR 권한과 target이 승인 계획에 포함된 경우 CG-11~15를 수행한다.
  - **Evidence:** Korean PR body, `## DoD Status` final section, issue metadata, CI/review/head.
  - **Failure:** parent gate PENDING/FAIL을 유지한다.
- [ ] **E-08 — merge 후 closeout**
  - **Action:** fresh merge approval 후 CG-16~18을 수행한다.
  - **Evidence:** merge SHA, local/upstream parity, 보존·정리 목록.
  - **Failure:** 승인 전 merge하지 않는다.

## Kotlin / Testcontainers

- [x] **KT-01 — triggered guidance**
  - **Action:** touched Kotlin test/fixture/Testcontainers 경계를 적용한다.
  - **Evidence:** Kotlin testing, Spring Boot, final checklist references를 읽었다.
  - **Failure:** unclassified trigger는 구현을 막는다.
- [x] **KT-02 — impact/reuse 확인**
  - **Action:** existing worker/store/metrics와 singleton launcher를 우선 재사용한다.
  - **Evidence:** `Redis88Launcher`, existing PostgreSQL/Kafka launcher, `NotificationOutboxEndToEndTest`, lifecycle/Redis integration tests를 확인했다.
  - **Failure:** 새 abstraction/dependency를 근거 없이 추가하지 않는다.
- [x] **KT-03 — Kotlin 계약**
  - **Action:** bluetape assertions, cancellation, blocking IO, cleanup ownership, redaction, Testcontainers lifecycle를 점검한다.
  - **Evidence:** singleton launcher, Exposed transaction, bounded dispatcher, provider stub, exact Redis/Kafka cleanup, report redaction, final SHADOW route assertion을 test source에서 확인했다.
  - **Failure:** P0/P1은 progression을 막는다.
- [x] **KT-04 — Kotlin validation**
  - **Action:** targeted compile/test, sequential container run, diff check를 수행한다.
  - **Evidence:** exact-class `./gradlew :appointment-api:test --tests '...NotificationOutboxCanaryEvidenceValidatorTest' --tests '...NotificationOutboxCanarySimulationIntegrationTest' --no-daemon --no-build-cache --rerun-tasks --max-workers=1`, XML `2+1` tests all failures/errors `0`, `BUILD SUCCESSFUL`, PostgreSQL `18-alpine`, Redis `8.8`, Kafka round-trip lag `0`.
  - **Failure:** stale/partial evidence로 PASS하지 않는다.
- [x] **KT-05 — final checklist**
  - **Action:** Kotlin final/testing/Spring checklist를 완료한다.
  - **Evidence:** Kotlin/test rows와 N/A 근거를 갱신했고 report/review의 inline finding count `P0=0 / P1=0 / P2=5 / P3=0`을 확인했다.
  - **Failure:** unchecked row와 repair action을 보고한다.

## Writer / Lesson

- [x] **SPW-01..05 — Korean technical artifact gate**
  - **Action:** simulation report, review/lesson 문서의 audience·source·구조·자연스러움·traceability·read-back을 검증한다.
  - **Evidence:** review/lesson/checklist/report를 source·audience·구조·traceability 기준으로 read-back했고 `audit-korean-terms.mjs`가 `findings=0`을 반환했다.
  - **Failure:** source drift 또는 미완성 artifact는 PR을 막는다.

## Scope N/A

- live production credential/traffic/실제 provider: Issue #204가 production-like simulation으로 명시하며, 실제 rollout은 별도 승인 gate다.
- frontend/API production behavior/production dependency/module: 이번 변경은 notification test/evidence/docs에 한정한다.
- HTTP/HC5 adapter: 해당 API surface를 수정하지 않는다.
- chart/diagram/visual asset: benchmark chart가 아니라 고정 workload의 redacted evidence report가 완료 계약이다.
- chezmoi source/live parity: repository-local docs/tests만 변경하며 user-scope Codex/Claude/managed config는 건드리지 않는다.

## Fresh Evidence Ledger

| 시점 | 명령/산출물 | 결과 |
|---|---|---|
| 2026-08-24 UTC | `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.config.NotificationOutboxCanaryEvidenceValidatorTest' --no-build-cache` | `2 passing`, 첫 fixture RED 후 GREEN |
| 2026-08-24 UTC | `./gradlew :appointment-api:compileTestKotlin --no-build-cache` | `BUILD SUCCESSFUL` |
| 2026-08-24 UTC | `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.config.NotificationOutboxCanarySimulationIntegrationTest' --no-build-cache` | `1 passing`, simulation 29.6초 |
| 2026-08-24 UTC | wildcard rerun `...NotificationOutboxCanary* --no-daemon --no-build-cache --rerun-tasks` | 테스트 자체는 `3 passing`까지 도달했지만 Gradle `EOFException`으로 task가 실패하여 최종 증거로 채택하지 않음 |
| 2026-08-24 UTC | exact-class `...EvidenceValidatorTest` + `...SimulationIntegrationTest --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain` | `2+1` XML tests, failures/errors `0`, `BUILD SUCCESSFUL` (Gradle 1분 13초, simulation 29.6초) |
| 2026-08-24 UTC | fixture refresh 후 `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.config.NotificationOutboxCanaryEvidenceValidatorTest' --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain` | validator `2 passing`, `BUILD SUCCESSFUL`; durable/fixture report identical, JSON/redaction scan PASS |
| 2026-08-24 UTC | `node .../audit-korean-terms.mjs` | 3개 문서, `findings=0` |
| 2026-08-24 UTC | `docs/benchmarks/issue-204-notification-canary/2026-08-24/production-like-report.json` | redacted report, zero-failure thresholds `0`, throughput `33.74730021598272/s`, assertions `27/27`, worker stop/restart·health·final `SHADOW` route assertion 포함 |

## 현재 DoD 집계

- PASS: WF-00..WF-06, CL-01..CL-06, E-01..E-05, KT-01..KT-05, SPW-01..05
- N/A: live rollout/credential/traffic, frontend/production route, HTTP/HC5, chart/diagram, chezmoi parity
- PENDING: CL-07, E-07, E-08 (PR/merge 승인과 closeout 전용; P2 외부 증거는 Issue #204 rollout hold로 별도 추적)
- Review note: 사용자 지시에 따라 독립 `code-reviewer` lane 대신 현재 diff inline review를 수행했다. P0=0/P1=0/P3=0이며, production 외부 증거 경계의 P2=5건은 PENDING으로 기록했다. 상수 evidence, replay 누락, cleanup masking, drain off-by-one, validator mutation false-positive를 수정하고 fresh test/report에 반영했다.
- BLOCKED: 없음
- 최종 상태: `PENDING — P2 외부 증거와 PR/merge 전 fresh review·명시 승인 필요`
