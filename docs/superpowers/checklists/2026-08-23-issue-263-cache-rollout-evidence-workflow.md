# Issue #263 Type-E Workflow Checklist

상태 기준: `[ ]` 미증명, `[x]` 최신 증거로 PASS, `N/A`는 범위 근거를 함께 기록합니다.

## Router

- [x] **WF-00 — AGENTS.md 계층 읽기**
  - **Action:** 사용자·workspace·repository 기준 정보를 순서대로 읽는다.
  - **Evidence:** `/Users/debop/.codex/AGENTS.md`, `/Users/debop/work/bluetape4k/.github/docs/workspace/AGENTS.md`, `/Users/debop/work/bluetape4k/clinic-appointment/AGENTS.md`를 승인 전 읽었다.
  - **Failure:** 누락된 기준 정보가 있으면 분류와 변경을 중지한다.
- [x] **WF-01 — 작업 유형 분류**
  - **Action:** Issue #263과 현재 repository evidence를 기준으로 작업 유형을 확정한다.
  - **Evidence:** Type-E; production behavior 없이 container-backed test/evidence harness와 Korean 운영 문서를 추가한다.
  - **Failure:** 유형이 모호하면 실행을 중지한다.
- [x] **WF-02 — 첫 실행 계획 작성**
  - **Action:** 파일·명령·Expected DoD가 있는 ordered plan을 사용자에게 제시한다.
  - **Evidence:** Issue #263, `develop` base, `chore/issue-263-cache-rollout-evidence` head, 테스트·validator·문서·검증 계획을 제시했다.
  - **Failure:** 계획 승인 전에는 mutation하지 않는다.
- [x] **WF-03 — 첫 계획 승인**
  - **Action:** 사용자의 명시 승인을 기록한다.
  - **Evidence:** 현재 thread의 `승인` 응답.
  - **Failure:** 승인 전 durable artifact와 code mutation을 중지한다.
- [x] **WF-04 — 실행 계약 로드**
  - **Action:** Type-E leaf, common gates, Kotlin/Testcontainers/Spring/writer 계약을 읽는다.
  - **Evidence:** `bluetape-maintenance`, `bluetape-kotlin-patterns`, `testing.md`, `spring-boot.md`, Kotlin checklist, `bluetape-writer`, `common-gates.md`, `repository-hazards.md`를 읽었다.
  - **Failure:** 필요한 계약이 없거나 unreadable이면 변경을 중지한다.
- [x] **WF-04A — machine-readable run 초기화**
  - **Action:** `bluetape-flow.py`로 현재 session에 바인딩된 run을 초기화한다.
  - **Evidence:** run `20260823T142917Z-c1b4ca15`, state root `.bluetape`, components `appointment-api`, `scripts`, `docs`를 생성했다.
  - **Failure:** helper가 없으면 문서 checklist 경로로 남기고 runtime gap을 보고한다.
- [x] **WF-05 — 의존 순서 게이트 실행**
  - **Action:** checklist physical order대로 증거를 수집하고 dependent gate를 순차 실행한다.
  - **Evidence:** `compileTestKotlin`, Colima/Docker preflight, targeted Testcontainers test `1 passing`, report validator 2회 positive, strict discriminator/sequence/lifecycle/lagMetric negative를 순서대로 실행했고 API 전체 회귀도 `837 passing`, `3 pending`, `0 failing`으로 통과했다.
  - **Failure:** 실패·PENDING row의 dependent gate를 실행하지 않는다.
- [x] **WF-06 — 누락·약한 gate 복구**
  - **Action:** 누락된 증거를 재구성하고 affected proof를 재실행한다.
  - **Evidence:** Flyway V29 lock timeout에 PostgreSQL transactional-lock 설정을 추가했고, Kafka launcher superclass runtime 누락에 `testcontainers-kafka:2.0.5`와 API lockfile을 추가한 뒤 fresh rerun이 통과했다.
  - **Failure:** 안전한 복구가 없으면 BLOCKED로 남긴다.

## Common Gates

- [x] **CL-01 — mutation 전 checklist 생성**
  - **Action:** router/common/leaf rows를 mutation 전에 인스턴스화한다.
  - **Evidence:** 이 파일을 worktree source mutation 전에 생성했다.
  - **Failure:** checklist가 없으면 STOP하고 복구한다.
- [x] **CL-02 — 모든 row 분류**
  - **Action:** required, conditional, N/A를 각 row에 결정한다.
  - **Evidence:** live production, frontend, production dependency/module, HTTP/HC5, visual/chart는 scope N/A로 기록하고, broker launcher classpath에 필요한 기존 `testcontainers-kafka:2.0.5` test-only dependency는 required로 분류했다.
  - **Failure:** 미분류 row는 required unchecked로 취급한다.
- [x] **CL-03 — 의존 순서 준수**
  - **Action:** 선행 증거가 PASS한 뒤에만 dependent row를 실행한다.
  - **Evidence:** contract/readiness → RED → implementation → container validation → docs → pre-PR 순서로 진행한다.
  - **Failure:** 순서가 어긋나면 affected proof를 재실행한다.
- [x] **CL-04 — 증거 즉시 기록**
  - **Action:** gate 확인 즉시 command/file/result를 기록한다.
  - **Evidence:** `2026-08-23T16:50:39.718651Z` 실행 report와 thresholds를 `docs/benchmarks/issue-263-cache-rollout-evidence/2026-08-23/`에 보존하고 validator·test 결과를 이 checklist에 갱신했다.
  - **Failure:** 뒤늦게 재구성하지 않고 unchecked로 둔다.
- [x] **CL-05 — fail closed**
  - **Action:** PENDING/FAIL row의 dependent branch를 중지한다.
  - **Evidence:** Flyway timeout과 Kafka classpath failure를 각각 실패로 기록하고 원인 수정 후에만 dependent validator/docs gate를 진행했다.
  - **Failure:** dependent 작업을 실행했다면 affected proof를 재검증한다.
- [x] **CL-06 — skip/reorder 복구**
  - **Action:** 누락 row를 복구하고 이후 증거를 새로 수집한다.
  - **Evidence:** dependency lock 보강과 Flyway configuration repair 뒤 `--rerun-tasks` 테스트가 `1 passing`으로 새로 통과했다.
  - **Failure:** 최종 상태를 BLOCKED로 유지한다.
- [ ] **CL-07 — irreversible hold refresh**
  - **Action:** PR 생성·merge 직전에 authority와 target을 다시 읽는다.
  - **Evidence:** 최신 Issue/branch/head/CI/merge authority read-back.
  - **Failure:** hold가 stale이면 side effect를 실행하지 않는다.
- [x] **CL-08 — 완료 count 산출**
  - **Action:** required checks count와 unchecked IDs를 계산한다.
  - **Evidence:** checklist `30`개 중 `26`개 checked이며 unchecked ID는 `CL-07`, `E-07`, `E-08`이다. `completion-check`는 workflow receipt 기준 `complete=true`, missing components/verification이 없다.
  - **Failure:** count가 맞지 않으면 완료를 주장하지 않는다.

## Type-E Steps

- [x] **E-01 — Route support skills**
  - **Action:** Kotlin, Testcontainers, writer support surface를 적용하고 visual/chart 필요성을 판정한다.
  - **Evidence:** `bluetape-kotlin-patterns`, `bluetape-maintenance`, `bluetape-writer` 및 관련 references를 적용했고 JSON evidence 작업이라 visual/chart는 N/A다.
  - **Failure:** missing route는 편집을 중지한다.
- [x] **E-02 — 현재 guidance와 source 조사**
  - **Action:** validator, runbook, launcher, cache config/test, GitHub Issue evidence를 확인한다.
  - **Evidence:** `scripts/verify-cache-rollout-evidence.sh`, `docs/runbooks/dependency-1.4.0-cache-migration.md`, API `Containers`/`CacheConfig`/`CacheIntegrationTest`, Issue #263을 읽었다.
  - **Failure:** authority가 불명확하면 read-only로 남긴다.
- [x] **E-03 — production behavior와 ownership 보존**
  - **Action:** test/evidence/docs 범위만 변경하고 root dirty state와 managed surfaces를 제외한다.
  - **Evidence:** 변경은 API test/resource/build lock, validator, runbook/README/lesson/report/checklist에 한정되며 production `src/main`과 root dirty state는 건드리지 않았다.
  - **Failure:** production behavior가 바뀌면 Type A/B로 재분류한다.
- [x] **E-04 — 적용·parity 증명**
  - **Action:** repository-managed docs/config 변경의 source/target parity를 확인한다.
  - **Evidence:** 모든 artifact가 repository-local 파일이며 chezmoi 관리 대상이 아니므로 source/live parity는 N/A다. 생성 report와 durable report를 validator로 각각 read-back했다.
  - **Failure:** live-only success는 FAIL이다.
- [x] **E-05 — maintenance verification**
  - **Action:** diff check, reference scan, validator, Gradle/Testcontainers 검증을 수행한다.
  - **Evidence:** fresh Gradle targeted test, `bash -n`, validator positive/negative, Colima/Docker preflight, Korean terminology audit를 실행했다.
  - **Failure:** unchecked 상태로 commit하지 않는다.
- [x] **E-06 — pre-PR proof**
  - **Action:** final diff, language, redaction, pruning, lesson, P0/P1 review를 수렴한다.
  - **Evidence:** exact local diff에서 production source·credential·raw payload가 없고, strict validator/forbidden scan/redaction scan/lesson read-back을 통과했다. 독립 code·architecture review 재검토는 P0=0/P1=0 CLEAR이며, lagMetric 명시·zero-backlog strict 불변식·중첩 cleanup primary 보존·`Containers.Redis`/`Redis88Launcher.redis`를 반영한 runbook·Fory pool generated serializer warm-up을 수렴했다.
  - **Failure:** PR publication을 중지하고 repair한다.
- [ ] **E-07 — common PR gates**
  - **Action:** PR authority가 확인되면 CG-11~15를 수행한다.
  - **Evidence:** exact PR/head/metadata/CI/review read-back.
  - **Failure:** parent gate PENDING/FAIL을 유지한다.
- [ ] **E-08 — merge 후 closeout**
  - **Action:** fresh merge approval 후 CG-16~18을 수행한다.
  - **Evidence:** merge SHA, local/upstream parity, 보존·정리 목록.
  - **Failure:** parent gate PENDING/FAIL을 유지한다.

## Kotlin / Testcontainers

- [x] **KT-01 — triggered guidance**
  - **Action:** testing, Spring, Testcontainers, cache/Exposed 경계를 적용한다.
  - **Evidence:** Kotlin testing/Spring/Testcontainers references를 읽고 singleton launcher, same-thread/resource-lock, Flyway/Redis/Kafka lifecycle에 적용했다.
  - **Failure:** unclassified trigger는 구현을 막는다.
- [x] **KT-02 — impact/reuse 확인**
  - **Action:** current source/test/helper와 singleton launcher를 재사용한다.
  - **Evidence:** raw `GenericContainer`와 `@Testcontainers`는 없고 `Containers`/`CacheConfig`/`KafkaServer.Launcher`를 재사용했다. Kafka superclass runtime을 위해 기존 플랫폼의 `testcontainers-kafka:2.0.5`만 test scope로 추가했다.
  - **Failure:** memory 기반 구현을 중지한다.
- [x] **KT-03 — Kotlin 계약**
  - **Action:** assertions, lifecycle, cleanup, blocking IO, resource ownership를 점검한다.
  - **Evidence:** bluetape assertions, bounded JDBC/Kafka waits, exact-key cleanup, 생성 직후 nullable ownership, primary exception 보존 cleanup, Fory pool 전체 async serializer drain, `@Isolated`와 same-thread/resource locks를 적용했고 현재 P0/P1 finding은 없다.
  - **Failure:** P0/P1은 progression을 막는다.
- [x] **KT-04 — Kotlin validation**
  - **Action:** targeted compile/test, container run, diff check를 수행한다.
  - **Evidence:** `compileTestKotlin` 통과, Colima `default`/Docker `28.4.0` preflight 통과, targeted test `1 passing`, Fory interaction pair `5 passing`, 전체 API test `840 tests / 837 passing / 3 skipped / 0 failing`, Docker 실패 없음.
  - **Failure:** stale/partial evidence로 PASS하지 않는다.
- [x] **KT-05 — final checklist**
  - **Action:** Kotlin checklist와 testing/Spring checklist를 완료한다.
  - **Evidence:** required rows와 N/A 근거, P0=0/P1=0, full API regression `840 tests / 837 passed / 3 skipped / 0 failed`.
  - **Failure:** unchecked row와 repair action을 보고한다.

## Writer / Lesson

- [x] **SPW-01..05 — Korean technical artifact gate**
  - **Action:** lesson/runbook/README 변경 시 audience·source·구조·자연스러움·traceability·read-back을 검증한다.
  - **Evidence:** lesson/runbook/README에 실제 report·command·운영 경계를 반영했고 validator read-back과 terminology audit를 실행했다. audit의 기존 README `스냅숏`/`예약서비스` finding은 이번 diff 밖의 기존 문장으로 기록하고 수정하지 않았다.
  - **Failure:** source drift 또는 미완성 artifact는 PR을 막는다.

## Scope N/A

- live production credential/traffic/`--require-live`: Issue #263이 production-like simulation으로 명시적으로 제한한다.
- frontend/API production behavior/production dependency or module: 이번 변경은 test/evidence/docs에 한정한다. Kafka test-only dependency는 KT-02에 기록한다.
- HTTP/HC5 adapter: 해당 surface를 수정하지 않는다.
- chart/diagram/visual asset: benchmark가 아니며 JSON evidence와 validator가 완료 계약이다.
