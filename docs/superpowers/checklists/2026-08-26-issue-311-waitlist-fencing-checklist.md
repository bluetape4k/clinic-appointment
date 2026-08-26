# Issue #311 Type A 실행 체크리스트

이 체크리스트는 `bluetape-workflow` 공통 계약과 `bluetape-full-feature` Type A
실행 순서를 한 문서에 고정한다. 각 항목은 현재 실행에서 근거를 읽은 뒤에만
체크한다. `N/A`는 적용 범위 근거가 있을 때만 사용한다.

## 공통 체크리스트 계약

- [x] **CL-01 — Create before mutation**
  - **Action:** 라우터·공통·Type A 항목을 코드 변경 전에 이 문서로 인스턴스화한다.
  - **Evidence:** 이 문서에 CL-01..CL-08 및 A-01..A-12 항목을 기록하고, 잘못된 12-component 시도는 topology limit 초과로 취소한 뒤 실행 run `20260826T131830Z-ad3bb476`를 8-component topology로 초기화했다.
  - **Failure:** 추가 변경을 중지하고 누락된 항목을 먼저 복구한다.
- [x] **CL-02 — Classify every item**
  - **Action:** 모든 항목을 required·conditional·N/A 중 하나로 분류한다.
  - **Evidence:** A-01..A-12는 required 또는 조건부로 명시했고, 동시성·Redis·DB fence 작업으로 A-05를 required로 분류했다.
  - **Failure:** 미분류 항목은 required·unchecked로 유지한다.
- [x] **CL-03 — Respect dependency order**
  - **Action:** 문서 순서대로 선행 근거를 확보한 뒤 다음 단계로 이동한다.
  - **Evidence:** run `20260826T131830Z-ad3bb476`의 receipt sequence 7, `mutation-check` checksum `49188a9a0e75fb80c6dde8179789f9baa8f227a408190a55a23ff09a8ca61c79`, worktree→requirements→research→design 순서를 대조했고 설계 review 뒤 plan으로 진행한다.
  - **Failure:** 순서를 위반하면 영향을 받은 하위 근거를 복구·재검증한다.
- [x] **CL-04 — Record evidence immediately**
  - **Action:** 항목을 체크하는 순간 명령·파일·URL·결과를 기록한다.
  - **Evidence:** A-01/A-02 근거와 spec/review 경로를 체크 시점에 기록했고, mutation 전 `mutation-check`를 fresh 실행했다.
  - **Failure:** 근거 없는 항목은 unchecked로 되돌린다.
- [x] **CL-05 — Fail closed**
  - **Action:** PENDING·FAIL 항목은 종속 단계를 차단한다.
  - **Evidence:** 첫 workflow 시도 `20260826T131447Z-0bcb125d`는 12-component topology limit에서 코드 mutation 전에 취소했고, 구현은 corrected run `20260826T131830Z-ad3bb476`에서만 재개했다. 잘못된 시도의 산출물은 `.bluetape/cancel-topology-evidence.json`에 남겼다.
  - **Failure:** 종속 작업을 실행하지 않고 repair 또는 사용자 승인 게이트로 남긴다.
- [x] **CL-06 — Repair skipped or reordered work**
  - **Action:** 누락·순서 오류를 복구하고 영향을 받은 하위 근거를 재실행한다.
  - **Evidence:** topology를 8-component corrected run으로 복구한 뒤 receipt sequence 7, mutation-check checksum `49188a9a0e75fb80c6dde8179789fbaa8f227a408190a55a23ff09a8ca61c79`를 재확인하고 Task 1~9의 RED/GREEN·module test를 다시 실행했다.
  - **Failure:** 복구할 수 없으면 최종 상태를 BLOCKED로 유지한다.
- [ ] **CL-07 — Refresh irreversible holds**
  - **Action:** PR·CI·merge 같은 외부/비가역 작업 직전에 hold를 다시 읽는다.
  - **Evidence:** 최신 target·authority·head와 명시적 승인 상태를 기록한다.
  - **Failure:** hold가 없으면 외부 side effect를 실행하지 않는다.
- [ ] **CL-08 — Count before completion**
  - **Action:** 완료 시 `Required checks: X/Y; N/A: N; Blocked: N`을 계산한다.
  - **Evidence:** 이 문서의 체크 상태와 최종 보고서의 총계가 일치한다.
  - **Failure:** 총계가 맞지 않으면 완료를 주장하지 않는다.

## Type A 단계

- [x] **A-01 — Isolate and confirm requirements**
  - **Action:** Issue #311 요구사항·경계·호환성·부작용·중단 조건과 격리 worktree를 확인한다.
  - **Evidence:** `feat/issue-311-waitlist-fencing`, base `1859b5cb3ae68c25e918236b0923d74d845e6726`, worktree `.worktrees/issue-311-waitlist-fencing`, live Issue #311 및 기존 PR #378 기록.
  - **Failure:** 요구사항·권한·경계가 불명확하면 연구와 산출물을 중지한다.
- [x] **A-02 — Ground the design in current evidence**
  - **Action:** 현재 코드·테스트·Gradle catalog·bluetape4k Lettuce API·기존 문서를 조사한다.
  - **Evidence:** `WaitlistDeliveryScheduling.kt`, `WaitlistDeliveryRepository.kt`, `WaitlistVacancyJobs.kt`, `CacheConfig.kt`, `build.gradle.kts`, local `bluetape4k-lettuce` 1.12.1 jar/source, 기존 Issue #311 문서.
  - **Failure:** 근거가 부족하면 설계를 확정하지 않는다.
- [x] **A-03 — Approve and review the design spec**
  - **Action:** `using-superpowers`·`brainstorming`·`bluetape-writer`로 설계를 확정하고 여섯 관점과 통합 리뷰를 수행한다.
  - **Evidence:** 승인된 설계 대화와 `docs/superpowers/specs/2026-08-26-issue-311-waitlist-fenced-production-design.md`, `docs/superpowers/reviews/2026-08-26-issue-311-waitlist-fenced-design-review.md`; 두 문서의 SPW-01..SPW-05 PASS, 여섯 관점+통합 review, 최신 P0=0/P1=0.
  - **Failure:** P0/P1 또는 승인되지 않은 설계 변경은 spec을 되돌려 재승인한다.
- [x] **A-04 — Approve and review the implementation plan**
  - **Action:** `writing-plans`·`bluetape-writer`로 acceptance traceability·파일·테스트·rollback을 계획하고 리뷰한다.
  - **Evidence:** `docs/superpowers/plans/2026-08-26-issue-311-waitlist-fenced-production-plan.md`, `docs/superpowers/reviews/2026-08-26-issue-311-waitlist-fenced-plan-review.md`, commit `e3d5112e`; SPW-01..SPW-05 audit PASS, 여섯 관점 통합 review P0=0/P1=0, AC-01..AC-08 traceability와 exact file/test/rollback 단계 read-back.
  - **Failure:** 누락된 의존성·hazard·검증을 보완한 뒤에만 코드를 시작한다.
- [x] **A-05 — Predict triggered risks**
  - **Action:** Redis lease expiry/failover, cancellation, ambiguous completion, DB strict-greater, metrics redaction 위험을 기록한다.
  - **Evidence:** `docs/superpowers/risk/2026-08-26-issue-311-waitlist-fenced-risk.md`에 R-01..R-10 위험, 완화 신호, rollback/rerun 지점과 Task/checklist traceability를 기록했으며 plan Task 4/6/7/9와 연결했다.
  - **Failure:** 위험 항목이 없으면 구현을 시작하지 않는다.
- [x] **A-06 — Implement with test-first proof**
  - **Action:** 테스트 RED→최소 구현→GREEN 순서로 typed fenced adapter, production wiring, DB fence를 구현한다.
  - **Evidence:** Task 1 RED를 먼저 실행해 `:appointment-core:compileTestKotlin`에서 `fenceEpoch`, `fenceSequence`, `WaitlistFencingToken` unresolved reference를 확인했다. 최소 구현 후 같은 명령이 `BUILD SUCCESSFUL`, `SUCCESS: Executed 12 tests in 7s`로 통과했다. Task 2에서는 `claimFenced` 부재 RED 후 strict-greater/exact terminal 구현과 PostgreSQL expiry takeover 회귀를 추가했고, `./gradlew :appointment-core:test --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryRepositoryTest' --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryPostgreSqlContentionTest' --no-build-cache --no-daemon --console=plain`이 `BUILD SUCCESSFUL`, `SUCCESS: Executed 17 tests in 6.6s`로 통과했다. Task 3에서는 V31 contract RED에서 세 dialect의 `fence_epoch`/`fence_sequence` 누락을 확인한 뒤 H2·PostgreSQL·MySQL migration script와 PostgreSQL transactional lock 설정을 추가했고, `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.migration.WaitlistFencingMigrationContractTest' --no-daemon`이 `BUILD SUCCESSFUL`, `SUCCESS: Executed 1 tests in 15.2s`로 통과했다. Task 4에서는 `FencedWaitlistLeaderLease`·`WaitlistFencedLockOperations` 부재 RED 후 Base58 opaque owner, typed outcome, 동일 owner/request reconcile, release idempotency, close gate를 구현했고, `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedLeaderLeaseTest' --no-build-cache --no-daemon --console=plain`이 `BUILD SUCCESSFUL`, `SUCCESS: Executed 6 tests in 1.6s`로 통과했다. Task 5에서는 typed dispatcher/runner와 `fenceEpoch` 부재 RED 후 acquire·reconcile·close gate, safety 작업 순서, token 전달, allowlisted metrics를 구현했고, `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedDeliverySchedulingTest' --no-build-cache --no-daemon --console=plain`이 `BUILD SUCCESSFUL`, `SUCCESS: Executed 5 tests in 1.7s`로 통과했다. Task 6에서는 missing V31 column이 `WaitlistFencingReadinessException` cause chain을 포함하고, complete ports가 readiness 선행·자동 metrics bean·scheduler를 조립하는 `WaitlistFencedSchedulingConfigurationTest` 4개가 `BUILD SUCCESSFUL`로 통과했다. Task 7에서는 Redis 8.8 singleton에서 fixed lease expiry takeover, strict-greater token, stale release, 실제 handle ambiguous reconcile, metric redaction을 검증하는 `WaitlistFencedRedisIntegrationTest` 3개가 `BUILD SUCCESSFUL`로 통과했다.
  - **Failure:** 실패 동작으로 되돌아가고 부분 구현을 진행하지 않는다.
- [x] **A-07 — Verify tests, spec, plan, and repository hazards**
  - **Action:** targeted·proportional broader 검증과 spec/plan·hazard 대조를 수행한다.
  - **Evidence:** `:appointment-core:test` 579 tests와 `:appointment-api:test` 900 tests(3 skipped)가 모두 `BUILD SUCCESSFUL`이고, `:appointment-core:build :appointment-api:build`도 `BUILD SUCCESSFUL`이다. targeted Redis 8.8, PostgreSQL concurrent claim, V31 3-dialect migration, readiness, redaction 회귀와 spec/plan/risk/Issue AC-01..AC-08를 대조했으며 `git diff --check`와 Korean terminology audit도 통과했다.
  - **Failure:** verifier gap은 구현 또는 승인 산출물로 되돌린다.
- [x] **A-08 — Converge the final pre-PR review**
  - **Action:** 최종 checklist와 여섯 code-review 관점·통합 리뷰를 수행하고 P0/P1을 제거한다.
  - **Evidence:** `docs/superpowers/reviews/2026-08-27-issue-311-waitlist-fenced-final-review.md`에 7-Tier(신뢰성·성능·보안·운영·개발자/API·사용자/호출자·유지보수/아키텍처) 결과와 SPW-01..SPW-05를 기록했다. 독립 성능·보안 검토의 P1/P2를 모두 반영했고 최종 open finding은 P0=0, P1=0, P2=0, P3=0이다.
  - **Failure:** blocker가 남으면 PR 생성을 보류한다.
- [x] **A-09 — Commit durable learning**
  - **Action:** Korean Lore lesson을 writer gate 후 PR 전에 추적 commit으로 남긴다.
  - **Evidence:** `docs/lessons/2026-08-26-issue-311-waitlist-fenced-production.md`에 context·decision·outcome·proof·miss·future guard와 2026-08-27 최종 검증 보강을 기록하고 Korean terminology audit 및 `git diff --check`를 통과했다. 추적 commit은 `194ca323` 이후 최종 검증 commit에 포함한다.
  - **Failure:** untracked 또는 근거 없는 lesson은 통과하지 못한다.
- [ ] **A-10 — Complete authorized PR delivery through live CI and review**
  - **Action:** CG-11..CG-14에 따라 PR 권한·head·metadata·본문·review·CI를 최신 상태로 검증한다.
  - **Evidence:** live PR read-back, final `## DoD Status`, exact head CI, review/thread 상태.
  - **Failure:** stale/missing CI·review는 PENDING 또는 FAIL로 남긴다.
- [ ] **A-11 — Capture knowledge and report merge readiness**
  - **Action:** knowledge capture 후 exact PR/head에 묶인 merge-ready DoD를 보고한다.
  - **Evidence:** knowledge/index 결과, X/Y·N/A·Blocked 총계, unchecked CG-16..CG-18.
  - **Failure:** fresh merge approval 전에는 DONE을 주장하지 않는다.
- [ ] **A-12 — Close out only after fresh merge approval**
  - **Action:** 최신 head에 대한 새 `승인` 이후에만 merge·검증·integration sync·worktree cleanup을 수행한다.
  - **Evidence:** fresh approval, merge SHA, clean synced develop, cleanup 결과.
  - **Failure:** 승인 전에는 CG-16 PENDING으로 유지한다.

## 공통 외부 게이트 상태

| 게이트 | 상태 | 근거/다음 행동 |
|---|---|---|
| CG-01..CG-10 | 완료 | 요구사항·설계·계획·구현·검증 순서와 corrected workflow receipt를 fresh evidence로 read-back |
| CG-11..CG-15 | 대기 | 구현·리뷰·CI 완료 후 live PR metadata/read-back |
| CG-16 | PENDING | exact PR/head에 대한 별도 merge 승인 필요 |
| CG-17..CG-18 | PENDING | CG-16 이후 merge·sync·cleanup |
