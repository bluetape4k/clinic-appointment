# Issue #184 Step 4 구현 실행 기록

## 실행 기준

- Work type: Type A Full Feature
- Repository: `bluetape4k/clinic-appointment`
- Worktree:
  `/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/issue-184-visit-commitment`
- Branch: `feature/184-visit-commitment`
- Base: `develop`
- 승인 근거: Step 3-R `P0=0/P1=0` 보고 이후 사용자가 Step 4 구현을 승인했다.
- 구현 계획:
  `docs/superpowers/plans/2026-07-29-issue-184-visit-commitment-plan.md`
- 적용 skill: `bluetape-workflow`, `bluetape-full-feature`,
  `executing-plans`, `test-driven-development`, `bluetape-kotlin-patterns`,
  `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `bluetape-writer`
- 외부 side effect: 구현·검증·commit·PR 생성까지 계획에 포함한다. merge는 별도
  최신 head 승인 전까지 금지한다.

## 공통 gate

- [x] **CG-01 — 권한과 현재 상태 재확인**
  - **Action:** AGENTS, workflow, language skill, 승인 계획, branch와 status를 다시 읽었다.
  - **Evidence:** HEAD `645ff7a`, feature worktree, 승인된 Task 1~10 계획.
  - **Failure:** 없음.
- [x] **CG-02 — 과거·현재 근거 확인**
  - **Action:** context-mode와 live GitHub issue를 조회했다.
  - **Evidence:** issue #184 OPEN, assignee `debop`, label `enhancement`;
    현재 source의 Plan/Event/ActorContext/Flyway V9 재사용 anchor 확인.
  - **Failure:** 없음.
- [x] **CG-03 — 사용자 작업과 변경 경계 보호**
  - **Action:** feature worktree와 untracked 시각 검토 산출물을 분리했다.
  - **Evidence:** `.superpowers/`의 HTML 초안은 tracked 최종 spec으로 대체됐고,
    `.playwright-cli/` 상태와 `output/playwright/issue-184-*` 화면 캡처는 어떤
    문서에서도 참조하지 않는 중간 검토 산출물임을 확인해 Task 5 완료 뒤 정리했다.
  - **Failure:** 없음.
- [x] **CG-04 — 정책과 언어 경계 적용**
  - **Action:** 한국어 KDoc/업무 문서, English public GitHub artifact,
    merge 별도 승인 경계를 고정했다.
  - **Evidence:** repo AGENTS와 갱신된 skill을 별도 Codex 프로세스에서도 로드 확인.
  - **Failure:** 없음.
- [x] **CG-05 — 생태계 패턴 재사용**
  - **Action:** 기존 `AppointmentPlanFactory`, purchase ingress/inbox/quarantine,
    `ActorContextResolver`, Exposed repository, Flyway 3-dialect pattern을 선택했다.
  - **Evidence:** 현재 source의 실제 class/table/test 경로.
  - **Failure:** 새 dependency 또는 module이 필요하면 구현을 멈추고 재승인한다.
- [ ] **CG-06 — public·문서 계약 완성**
  - **Action:** KDoc, OpenAPI, API 문서, README locale parity를 구현과 동기화한다.
  - **Evidence:** Task 7·9 완료 결과.
  - **Failure:** 문서 drift가 남으면 PR 전 차단한다.
- [ ] **CG-07 — RED/GREEN과 targeted proof**
  - **Action:** Task마다 기대한 RED를 관찰한 뒤 최소 구현과 GREEN을 기록한다.
  - **Evidence:** 아래 Task 실행 기록과 fresh Gradle 결과.
  - **Failure:** 실패 원인을 고치고 같은 Task의 RED/GREEN부터 다시 실행한다.
- [ ] **CG-08 — heavyweight 검증 직렬화**
  - **Action:** H2→PostgreSQL→MySQL, Gatling을 병렬 실행하지 않는다.
  - **Evidence:** Task 3·10 명령 순서와 결과.
  - **Failure:** 병렬 또는 불명확한 증거는 폐기하고 순차 재실행한다.
- [ ] **CG-09 — lesson gate**
  - **Action:** 구현 diff와 실패·복구·운영 교훈을 평가한다.
  - **Evidence:** Step 7 lesson 또는 네 가지 부재를 입증한 N/A.
  - **Failure:** PR 전 lesson 근거를 보완한다.
- [ ] **CG-10 — pre-PR proof 수렴**
  - **Action:** Step 5/6/6-R과 Kotlin 최종 checklist를 완료한다.
  - **Evidence:** P0=0/P1=0, clean diff, commit SHA.
  - **Failure:** PR 생성을 차단하고 구현/검증 단계로 돌아간다.

## Type A gate

- [x] **A-01~A-05 — 요구·설계·계획·위험 예측**
  - **Action:** 이전 단계의 승인과 독립 검토를 재확인했다.
  - **Evidence:** spec, Step 2-R, plan, Step 3-R, Step 3-P와 commit `645ff7a`.
  - **Failure:** 없음.
- [ ] **A-06 — TDD 구현**
  - **Action:** Task 1~10을 dependency 순서로 구현한다.
  - **Evidence:** 아래 RED/GREEN 표와 scoped diff.
  - **Failure:** 불완전한 Task에서 다음 Task로 진행하지 않는다.
- [ ] **A-07 — spec/plan/hazard 검증**
  - **Action:** 29개 인수 기준과 세 DB, 성능, 문서 hazard를 검증한다.
  - **Evidence:** verifier PASS와 fresh command.
  - **Failure:** 구현 또는 승인 artifact를 다시 연다.
- [ ] **A-08 — 6-R 수렴**
  - **Action:** 6개 관점과 본 세션 통합 검토를 수행한다.
  - **Evidence:** 최신 P0=0/P1=0.
  - **Failure:** P0/P1 보정 후 영향 관점을 재검토한다.
- [ ] **A-09 — lesson commit**
  - **Action:** 재사용 가능한 교훈을 commit한다.
  - **Evidence:** tracked lesson commit.
  - **Failure:** PR 생성 전 차단한다.
- [ ] **A-10~A-11 — PR·CI·merge-ready**
  - **Action:** exact head PR, metadata, CI, 7-R과 merge-ready 보고를 완료한다.
  - **Evidence:** live PR와 head SHA.
  - **Failure:** merge 승인을 요청하지 않는다.
- [ ] **A-12 — 승인된 merge closeout**
  - **Action:** 최신 merge-ready 보고 후 별도 승인 때만 수행한다.
  - **Evidence:** 현재는 PENDING.
  - **Failure:** auto-merge 또는 선행 merge 금지.

## Kotlin checklist

- [x] **KT-01 — trigger별 지침 로드**
  - **Action:** Kotlin core/test, Exposed, Spring Boot trigger를 분류했다.
  - **Evidence:** `bluetape-kotlin-patterns`, testing, spring-boot,
    `ecc-kotlin-exposed`, `ecc-springboot-kotlin`.
  - **Failure:** coroutine/diagram 등 새 trigger가 생기면 해당 지침을 먼저 읽는다.
- [x] **KT-02 — 영향과 재사용 확인**
  - **Action:** 현재 source·tests·KDoc·repository/event/security/migration pattern을 확인했다.
  - **Evidence:** Task별 기존 anchor를 계획에 연결했다.
  - **Failure:** raw helper를 추가하기 전에 repo/sibling 재검색한다.
- [ ] **KT-03 — Kotlin 계약 준수**
  - **Action:** validation, logging, concurrency, Serializable, KDoc, Exposed 경계를 점검한다.
  - **Evidence:** Task별 diff와 테스트.
  - **Failure:** 위반을 P0~P3로 분류하고 P0/P1을 즉시 보정한다.
- [ ] **KT-04 — Kotlin 검증**
  - **Action:** targeted tests, diagnostics, compile, module tests, diff check를 실행한다.
  - **Evidence:** Task와 최종 검증 명령.
  - **Failure:** stale/partial 결과로 PASS하지 않는다.
- [ ] **KT-05 — 최종 checklist 수렴**
  - **Action:** `references/checklist.md`와 triggered checklist를 전부 판정한다.
  - **Evidence:** X=Y, Blocked=0, P0=0/P1=0.
  - **Failure:** 미확인 row를 공개하고 완료를 차단한다.

## Task 실행 기록

| Task | RED | GREEN | Refactor/검증 | 상태 |
|---|---|---|---|---|
| 1. 핵심 계약·순수 계산 | 누락 타입 compile 실패 및 상한 API 실패 확인 | 대상 19개 테스트 통과 | `:appointment-core:build`: 425 tests, 실패 0; `git diff --check`; `!!`/deprecated import 없음 | DONE |
| 2. Exposed transaction primitive | 저장소·table 부재 compile 실패와 v2 projection 계약 실패 확인 | 대상 12개 테스트 통과 | `:appointment-core:build`: 437 tests, 실패 0; caller transaction·KDoc·금지 패턴 점검 | DONE |
| 3. Flyway V10 | 세 dialect에서 V10 schema 계약 실패 확인 | H2→PostgreSQL→MySQL migration 통과 | `:appointment-api:build`: 281 tests, 실패 0; checksum·Exposed drift 점검 | DONE |
| 4. 실행 BOM event ingest | 신뢰·상한·replay·gap 계약 실패 확인 | 대상 17개 테스트 통과 | `:appointment-event:build`: 77 tests, 실패 0; core/Flyway 회귀 통과 | DONE |
| 5. bounded proposal | 희망일·선행 완료·부분 이행·scope·상한 계약 실패 확인 | 대상 13개 테스트 통과 | `:appointment-api:build`: 294 passing, 2 pending; 실제 Gatling 240/240; ktlint·detekt·Kover 통과 | DONE |
| 6. commitment command | command/service/repository 부재 compile 실패와 업무 오류 RED 확인 | 대상 command 22개·core 저장소 13개·PostgreSQL 동시성 5개 통과 | 세 dialect migration, 전체 API build, ktlint, 7-Tier 재검토 통과 | DONE |
| 7. actor 기반 API | PENDING | PENDING | PENDING | PENDING |
| 8. version 전환·외부 사실 | PENDING | PENDING | PENDING | PENDING |
| 9. 운영·문서·KDoc | PENDING | PENDING | PENDING | PENDING |
| 10. 세 DB·성능·회귀 | PENDING | PENDING | PENDING | PENDING |

현재 집계: Required checks 15/22; N/A: 0; Blocked: 0.

### Task 1 상세 증거

- **RED 1:** production 계약을 만들기 전에 5개 테스트 파일을 작성했고,
  `compileTestKotlin`이 `ExecutionTreatment`, `MigrationMapping`,
  `VisitGroupingConstraint` 등 신규 계약 부재로 실패했다.
- **GREEN 1:** commitment lifecycle, exact proposal consent, 반복형 5회권,
  N개 중 M개 선택, provenance, 100/500/4,000 상한, cycle, 방문 묶음·분리와
  항목별 준비·진료·회복 시간, 상품 전환 6종, 완료 항목 보호,
  `BLOCKING` dirty-set 테스트가 통과했다.
- **RED 2:** 계획과 대조해 누락된 candidate slot 2,000개와 proposal 20개
  상한 테스트를 추가했고 `validateSearchBounds` 부재 compile 실패를 확인했다.
- **GREEN 2:** bluetape4k `requireInRange` 기반 상한 검증과 proposal canonical
  hash 테스트를 추가한 뒤 5개 대상 test class가 통과했다.
- **회귀:** `./gradlew :appointment-core:build` 성공. test report 집계는
  `tests=425`, `failures=0`, `errors=0`, `skipped=0`이다.
- **Kotlin 점검:** 신규 data class는 `Serializable`과 `serialVersionUID`를
  제공하고, public 및 복잡한 internal 계약은 속성 불변식·실패 조건을 포함한
  한국어 KDoc을 제공한다. 신규 production `!!`, suspend `runCatching`,
  deprecated Exposed `SqlExpressionBuilder.eq`는 없다.
- **Trigger N/A:** Task 1은 순수 함수와 불변 모델만 추가해 Exposed transaction,
  Spring wiring, coroutine cancellation, Testcontainers, 자원 수명주기를 변경하지
  않았다.

### Task 2 상세 증거

- **RED 1:** 다섯 repository test를 production table·record·repository보다 먼저
  작성했고 `AppointmentCommitments`, `ResourceAllocations`,
  `AppointmentPlanRevisionRepository` 등 계약 부재로 `compileTestKotlin`이 실패했다.
- **GREEN 1:** commitment/proposal/consent append와 version CAS, 실제
  `TreatmentSpace`, actor-scope idempotency, Plan revision append/activate,
  전담 자원 overlap 및 capacity bucket 상한, allocation 원자 교체를 구현했다.
- **격리 복구:** `Appointments`가 참조하는 `Equipments`와
  `ConsultationTopics`를 전용 H2 schema 정리 목록에 명시해 FK 잔존과 default
  tenant 중복을 제거했다. 저장소 대상 테스트 11개가 통과했다.
- **RED 2:** `COMMITMENT_V2` 미확정 row 생성과 legacy 조회 제외 테스트를 추가해
  `AppointmentModelVersion`·`modelVersion` 부재 compile 실패를 확인했다.
- **GREEN 2:** 기존 `Appointments` identity에 default `LEGACY` model version과
  nullable 확정 projection을 추가했다. legacy mapper 앞에는 완성 projection
  조건을 두고 mapper 자체도 bluetape4k `requireNotNull`로 불변조건을 검증한다.
  대상 테스트는 최종 12개가 통과했다.
- **transaction 경계:** 신규 repository는 내부에서 `transaction {}`를 열지 않는다.
  transaction 밖 호출 실패, capacity/overlap 사전 검증 후 insert, 기존 allocation을
  제외한 교체 집계와 성공 후 release를 테스트로 고정했다.
- **회귀:** `./gradlew :appointment-core:build` 성공. H2, PostgreSQL, MySQL을
  사용하는 기존 suite를 포함한 XML 집계는 `tests=437`, `failures=0`,
  `errors=0`, `skipped=0`이다.
- **Kotlin 점검:** 신규 record는 `Serializable`과 `serialVersionUID`를 제공하고,
  table/record/public repository 및 핵심 속성은 한국어 KDoc을 제공한다.
  신규 production `!!`, `println`, broad suspend `runCatching`,
  deprecated `SqlExpressionBuilder.eq`는 없다.

### Task 3·4 상세 증거

- **Task 3:** H2→PostgreSQL→MySQL 순서로 V1→V10과 clean migration을
  검증했다. V9 row 보존, `LEGACY` backfill, nullable v2 projection,
  FK·unique·조회별 index, Flyway checksum, Exposed 추가 DDL drift 없음이
  통과했다. `:appointment-api:build`의 281개 테스트도 통과했으며 commit은
  `dd60a56`이다.
- **Task 4 RED:** 반복형 5회권, 복합형, N-of-M, provenance 불일치,
  payload 1 MiB와 depth 32 초과, unknown schema/type, replay, version gap,
  same-version different hash를 production handler 전에 고정했다.
- **Task 4 GREEN:** 신뢰된 실행 BOM을 단일·패키지 공통 snapshot으로 정규화하고,
  inbox·최초 Plan Revision·treatment/dependency·outbox·idempotency를 한
  transaction에 저장했다. 대상 17개, `:appointment-event:build` 77개,
  core planner/repository 회귀 8개, H2 Flyway 회귀 1개가 통과했다.
  commits는 `8a2b272`, `216d579`이다.

### Task 5 상세 증거

- **RED:** nullable 상품 최초 예약 규칙의 compile mismatch, clinic scope가 다른
  후보, 전개 항목 500개·관계 4,000개 초과, 같은 방문의 양수 간격 의존,
  겹치는 capability의 exclusive 자원 중복, 선행 완료 기준 후속 회차,
  부분 이행 dirty-set 보호를 실패 테스트로 확인했다.
- **GREEN:** `AppointmentProposalService`가 고객 희망일을 우선하고 없을 때만
  상품 규칙을 사용한다. 둘 다 없으면 자동 가예약을 만들지 않는다. 완료·확정·
  영향 없는 미래 항목은 보존하고, clinic scope와 plan 상한을 계산 전에
  검증하며, 제안마다 현재 policy snapshot id와 canonical hash를 고정한다.
- **표적 검증:** `--no-build-cache --rerun-tasks`로
  `AppointmentProposalServiceTest`와 `AppointmentProposalServicePerformanceTest`
  13개가 통과했다.
- **성능:** 고정 seed normal 50 item/200 edge/90일과 maximum
  500 item/4,000 edge/365일을 각각 warm-up 20회 뒤 100회 측정했다. 완료된
  선행 항목에서 미래 방문으로 향하는 `BLOCKING` 간격 검사와 `BLOCKING`
  dirty-set closure를 모두 포함한다. 순수 계산은 normal p95 0.876 ms/
  p99 0.910 ms, maximum p95 5.631 ms였다.
- **실제 Gatling:** loopback HTTP가 동일한 production service를 호출하는
  시뮬레이션에서 240/240 요청이 성공하고 KO는 0이었다. assertion actual은
  normal p95 2 ms, p99 3 ms, maximum p95 6 ms다. raw `simulation.log`,
  400개 sample의 `unit-timing.tsv`, `percentiles.md`를
  `appointment-api/build/reports/gatling/visit-commitment/`에 남겼다.
- **전체 회귀:** `./gradlew :appointment-api:build --no-build-cache
  --rerun-tasks`는 294 passing, 기존 2 pending으로 성공했고 Kover verify도
  통과했다. Task 5 production class coverage는 covered/missed 기준
  instruction 967/108, branch 76/34, line 192/10, method 14/0이다.
- **정적 점검:** 네 Kotlin 파일 모두 ktlint와 detekt를 통과했다. public 및
  복잡한 internal 계약은 업무 불변식, 범위, 실패 의미를 포함한 한국어 KDoc을
  제공한다.
- **7-Tier 재검토:** 개발자·API 사용자, 성능·안정성, 보안·운영의 독립 관점과
  본 세션 통합 검토에서 P0=0/P1=0을 확인했다. 이전 P1/P2인 synthetic Gatling,
  clinic scope 누락, plan 상한 누락, 같은 방문 양수 간격, exclusive 자원 중복은
  모두 닫혔다.
- **후속 경계:** 자원별 tenant/clinic provenance와 capacity bucket의 DB-backed
  검증은 Task 6, 장기 회귀용 추가 성능 matrix는 Task 10에서 검증한다. Task 5의
  순수 proposal 계산이 repository/API 계약을 선점하지 않는다.

### Task 6 상세 증거

- **RED:** 고객 가예약·관리자 승인·직접 확정·변경 제안·수락·거부·만료,
  idempotency replay, revision gap, 서로 충돌하는 proposal accept, allocation
  실패 rollback, Plan item 위조와 자원 item 참조 위조를 production service보다
  먼저 또는 보정 회귀 테스트로 고정했다.
- **GREEN:** `AppointmentCommitmentCommandService`가 하나의 Exposed transaction에서
  actor-scope idempotency 선점, exact proposal·동의 재검증, 저장된 Plan item 검증,
  정렬된 자원 잠금, 새 allocation 생성, commitment version CAS, 기존 allocation
  해제, legacy projection, 감사·outbox, immutable idempotency 결과를 순서대로
  수행한다.
- **기존 예약 보호:** 변경 proposal 생성·거부·만료는 현재 확정 포인터와 자원 점유를
  바꾸지 않는다. 수락·거부·만료는 같은 proposal row lock과 commitment version을
  공유해 정확히 한 종결 결정만 성공한다. 새 자원 충돌이나 CAS 실패는 transaction
  rollback으로 새 row와 부수 효과를 제거한다.
- **Kotlin 계약 보정:** 생성자 검증을 가진 값은 helper 반환값을 실제 속성에 저장하고,
  `data class.copy()`로 불변조건을 우회할 수 있는 command/domain 값은 일반 불변
  class로 전환했다. production `!!`, `println`, broad `runCatching`,
  `synchronized` 추가는 없고 Task 6 변경 Kotlin 전체가 `ktlint`를 통과했다.
- **감사·패키지 정합성:** 직접확정에서 검증한 `evidenceType`과 정확한 `termsHash`를
  세 dialect V10 schema와 repository read model에 보존한다. proposal 안에서
  `treatmentKey`를 유일하게 만들고 모든 non-null `appointmentItemKey`가 같은
  proposal의 영속 item을 가리키는지 allocation 전 재검증한다.
- **표적 검증:** command service 22개, core commitment/item/allocation repository
  13개, PostgreSQL 동시성 5개가 실패·오류 없이 통과했다.
- **세 DB 검증:** H2→PostgreSQL→MySQL 순서로 migration test를 실행해 각 1개가
  통과했다. nullable 동의 감사 필드와 Exposed table 정의의 drift는 없다.
- **전체 회귀:** `./gradlew :appointment-api:build --no-build-cache
  --rerun-tasks`가 323개 테스트, 실패 0, 오류 0, 기존 skipped 2개로 성공했다.
- **6-R:** 독립 성능·안정성·보안·운영·개발자/API·사용자/호출자 관점과 본 세션
  통합 검토는
  `docs/review/2026-07-29-issue-184-task6-step-6r-code-review.md`에 보존한다.
  P0/P1은 모두 닫았고 public Gateway adapter 신뢰 경계와 package-scale 성능은
  Task 7·10의 명시적 후속 gate로 유지한다.
