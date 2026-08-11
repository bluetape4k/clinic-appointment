# appointment HTTP bounded-wait 멱등성 conformance 구현 계획

> **에이전트 작업자 필수 사항:** 이 계획은 현재 세션의 단일 owner가 순서대로 실행한다. 단계 추적에는 checkbox(`- [ ]`) 문법을 사용한다.

**목표:** Spring MVC test adapter로 bluetape4k bounded-wait HTTP fixture를 실행하고 실제 appointment DB replay 테스트와 증명 경계를 분리한다.

**아키텍처:** test-only controller/application이 deterministic idempotency state machine을 소유하고, adapter는 `MockMvc`/bounded-body filter/interruptible dispatcher를 연결한다. production appointment endpoint의 Exposed durable 테스트는 기존 `AppointmentControllerTest`를 재사용한다.

**기술 스택:** Kotlin 2.3, Spring Boot 4 MVC, MockMvc, JUnit 5, kotlinx-coroutines, `bluetape4k-junit5:1.12.1` fixture, bluetape4k assertions.

---

## 파일 구조

| 경로 | 책임 |
|---|---|
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/idempotency/AppointmentHttpIdempotencyConformanceTest.kt` | Spring adapter, test controller/application, body filter와 lifecycle 검증 |
| `docs/superpowers/specs/2026-08-10-appointment-http-idempotency-conformance-design.md` | 설계·증명 경계·대안 |
| `docs/review/2026-08-10-appointment-http-idempotency-conformance-review.md` | 7-tier 및 Kotlin checklist 통합 review |
| `docs/lessons/2026-08-10-appointment-http-idempotency-conformance.md` | durable lesson |

### Task 1: conformance test의 RED 고정

**Files:**
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/idempotency/AppointmentHttpIdempotencyConformanceTest.kt`

- [ ] **Step 1: 최소 Spring adapter와 fixture 호출을 작성한다.**

  `BoundedWaitHttpIdempotencyAdapter`의 10개 메서드와 `MockMvc` exchange를 선언하고,
  `runSuspendIO { assertBoundedWaitHttpIdempotencyConformance(adapter, config) }`를
  호출한다. owner/waiter control은 아직 `UnsupportedOperationException`으로 두어
  missing deterministic application이 명확히 드러나게 한다.

- [ ] **Step 2: RED를 실행한다.**

  Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.idempotency.AppointmentHttpIdempotencyConformanceTest" --rerun-tasks --no-daemon --console=plain`

  Expected: test compile은 되고 첫 conformance scenario가 control 미구현으로 실패한다.

### Task 2: deterministic appointment test application을 GREEN으로 만든다

**Files:**
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/idempotency/AppointmentHttpIdempotencyConformanceTest.kt`

- [ ] **Step 1: ingress/auth/fingerprint 경계를 구현한다.**

  `tenant-a-principal`, `tenant-b-principal`, `tenant-a-read-only`를 고정하고 인증 뒤에
  scope를 계산한다. strict JSON canonicalization, duplicate/malformed key rejection,
  configured byte bound, side-effect counter를 구현하며 key/body를 로그에 넣지 않는다.

- [ ] **Step 2: owner/waiter state machine을 구현한다.**

  `ReentrantLock` 아래 `InFlight`/`Terminal` record, `CompletableFuture` completion,
  waiter sequence/deadline, max waiter overflow, terminal retention과 virtual clock을
  관리한다. suspend 호출은 `runInterruptible`/cancellable future bridge를 사용하고
  `CancellationException`은 broad exception으로 바꾸지 않는다.

- [ ] **Step 3: completion/abandonment/cleanup을 구현한다.**

  owner terminal completion, transient abandon, pre/post commit disconnect, response
  delivery gate, timeout slot reclaim, `resetScenario`, `quiescence`를 exactly-once로
  정리한다. replay snapshot header denylist/aggregate bound를 fixture config와 맞춘다.

- [ ] **Step 4: GREEN을 실행한다.**

  위 단일 test 명령을 다시 실행해 모든 공통 scenario가 PASS하고 scenario cleanup의
  waiter/gate/task가 0인지 확인한다.

### Task 3: 실제 appointment API 증명 경계를 회귀 고정

**Files:**
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentControllerTest.kt` (필요한 경우에만 기존 test 보강)
- Modify: `docs/superpowers/specs/2026-07-24-appointment-idempotency-design.md` (증명 경계 링크가 필요한 경우에만)

- [ ] **Step 1: 기존 terminal/concurrent/expiry 테스트를 fresh 실행한다.**

  `AppointmentControllerTest`의 replay·concurrent·expiry method를 모듈 단위로 실행하고
  실제 H2 row/event count가 한 개임을 읽는다. 현재 assertion이 이미 조건을 모두
  증명하면 소스 변경 없이 N/A 근거를 기록한다.

- [ ] **Step 2: fixture와 durable proof의 차이를 문서화한다.**

  conformance test KDoc와 review에서 fixture가 process restart, DB atomic commit,
  authorization/rate limit, 외부 side effect exactly-once를 증명하지 않음을 명시한다.

### Task 4: review/lesson/검증

**Files:**
- Create: `docs/review/2026-08-10-appointment-http-idempotency-conformance-review.md`
- Create: `docs/lessons/2026-08-10-appointment-http-idempotency-conformance.md`

- [ ] **Step 1: 7-tier 및 Kotlin checklist review를 작성한다.**

  성능·안정성·보안·운영·개발자/API·사용자/호출자·통합/테스트 관점과 KT-FIN-01~11을
  파일/라인 근거로 확인하고 P0/P1=0, N/A 근거, remaining risk를 기록한다.

- [ ] **Step 2: targeted → module 검증을 순차 실행한다.**

  ```bash
  ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.idempotency.AppointmentHttpIdempotencyConformanceTest" --rerun-tasks --no-daemon --console=plain
  ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentControllerTest" --rerun-tasks --no-daemon --console=plain
  ./gradlew :appointment-api:test --rerun-tasks --no-daemon --console=plain
  ./gradlew :appointment-api:build --rerun-tasks --no-daemon --console=plain
  git diff --check
  ```

- [ ] **Step 3: Lore commit과 이슈 증거를 준비한다.**

  Korean lesson을 포함해 의도·제약·기각 대안·검증·미검증 범위를 Lore trailer로
  commit한다. 이번 권한에는 PR/원격 전달이 포함되지 않으므로 live issue comment는
  로컬 head와 fresh test 결과를 확인한 뒤에만 갱신한다.
