# Issue #309 Composite repository·transaction 구현 계획
> **For agentic workers:** 이 계획은 승인된 설계 문서에 따라 단계별로 실행하고, 각 단계의 테스트가 통과한 뒤 다음 단계로 진행한다.

**Goal:** Composite/custom DSL 경계를 보존하면서 세 개의 단순 record repository를 `bluetape4k-exposed` `LongJdbcRepository` 계약으로 재사용하고, legacy appointment application service의 Spring transaction 경계·caller-owned Exposed transaction·rollback/read-only 계약을 회귀 테스트로 고정한다.

**Architecture:** `LONG-JDBC` 공통 mapper + 명시적 특수 메서드, `COMPOSITE-DSL`/`APPEND-DSL` caller transaction, `AppointmentService`의 Spring proxy annotation, bootstrap/multi-step Database 경계의 보존 및 분류.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Spring transaction AOP, JetBrains Exposed v1 JDBC, bluetape4k-exposed `LongJdbcRepository`, JUnit 5, Kluent/bluetape assertions, H2 wiring fixture, bluetape4k PostgreSQL singleton launcher.

---

## 실행 규칙

- 작업 디렉터리는 `.worktrees/issue-309-composite-repository-transaction`이며 root `develop`와 다른 worktree의 변경은 건드리지 않는다.
- 모든 소스·KDoc·계획·lesson은 저장소 규칙에 따라 한국어로 작성한다. 코드 식별자·명령·URL·오류 문자열은 그대로 둔다.
- RED 테스트를 먼저 추가하고 실패를 확인한 뒤 최소 구현을 한다. 한 단계의 targeted test가 통과하기 전 다음 단계로 넘어가지 않는다.
- Exposed DSL은 `transaction {}` 또는 Spring proxy가 소유한 현재 transaction 안에서만 실행한다. repository가 `Database`를 받아 새 transaction을 열지 않는다.
- PostgreSQL container suite는 bluetape4k singleton launcher를 사용하고 실행을 직렬화한다. `@Testcontainers`는 추가하지 않는다. H2는 test-only schema/wiring/basic CRUD 검증으로 유지한다.
- 각 단계의 rollback 지점은 이전 커밋 또는 해당 단계의 파일 diff다. 실패 시 다음 단계에 진행하지 않고 실패 원인과 fresh output을 기록한다.

## Task 1 — 기준선·파일 계약 고정

**Files:**

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentIdempotencyRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentStateHistoryRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentSpaceRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentItemRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentOperationalExceptionRepository.kt`
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`

**Actions:**

1. 현재 파일과 public method 호출부를 `rg`로 다시 확인하고 `git diff --check` 기준선을 저장한다.
2. `LongJdbcRepository`의 실제 generic 계약(`table`, `extractId`, `ResultRow.toEntity`)과 현재 record의 nullable ID 규칙을 확인한다.
3. `AppointmentOperationalExceptionRepository`가 생성 ID를 별도 record로 노출하지 않는 `APPEND-DSL` 포트임을 구현 목록에 고정한다. 이 클래스는 억지로 generic CRUD로 바꾸지 않는다.

**Verification:**

```bash
git diff --check
rg -n "class (AppointmentIdempotencyRepository|AppointmentStateHistoryRepository|TreatmentSpaceRepository)|interface LongJdbcRepository" appointment-core/src/main/kotlin /Users/debop/work/bluetape4k/bluetape4k-exposed/exposed/jdbc/src/main/kotlin
```

**Rollback:** 문서·기준선만 변경되므로 소스 diff가 생기면 해당 파일만 되돌리고 다음 task를 시작하지 않는다.

## Task 2 — RED: repository 공통 계약과 caller transaction guard 테스트

**Files:**

- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentIdempotencyRepositoryTest.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentStateHistoryRepositoryTest.kt` (신규 또는 기존 테스트가 없으면 신규)
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentSpaceRepositoryTest.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentItemRepositoryTest.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentOperationalExceptionRepositoryTest.kt` (기존 테스트가 없으면 신규)

**Actions:**

1. 세 repository가 `LongJdbcRepository`를 구현한다는 compile/reflection 계약 테스트를 추가한다.
2. 기존 save/find/scope 정렬·만료 삭제·capability 검증의 결과를 그대로 assertion한다.
3. `AppointmentItemRepository`와 `AppointmentOperationalExceptionRepository`의 public DSL 메서드를 transaction 밖에서 호출하면 명확한 `IllegalStateException`으로 실패해야 한다는 RED 테스트를 추가한다.
4. caller transaction 안에서는 기존 fixture와 같은 결과를 반환하고 다른 table row를 남기지 않는지 확인한다.

**Verification (RED expected):**

```bash
./gradlew :appointment-core:test --tests '*AppointmentIdempotencyRepositoryTest' --tests '*AppointmentStateHistoryRepositoryTest' --tests '*TreatmentSpaceRepositoryTest' --tests '*AppointmentItemRepositoryTest' --tests '*AppointmentOperationalExceptionRepositoryTest' --no-daemon
```

RED 결과는 구현 전 계약 실패로 기록한다. 기존 테스트가 이미 통과하면 새 assertion만 실패하는지 확인한다.

**Rollback:** 새 테스트가 fixture를 오염시키면 테스트 파일만 되돌리고 schema/helper를 추가하지 않는다.

## Task 3 — GREEN: `LongJdbcRepository` 적용과 current transaction guard

**Files:**

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentIdempotencyRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentStateHistoryRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentSpaceRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ExposedTransactionGuard.kt` (신규)
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentItemRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentOperationalExceptionRepository.kt`

**Actions:**

1. 각 `LongIdTable` repository에 `LongJdbcRepository<Record>`를 상속하고 `table`, nullable ID를 검증하는 `extractId`, `ResultRow.toEntity`를 구현한다.
2. 기존 특수 메서드의 이름·입력 검증·정렬·scope 조건·반환 타입은 유지한다. 공통 `saveAll`은 사용하지 않으며, insert/update의 이유를 KDoc에 남긴다.
3. `ExposedTransactionGuard`는 `TransactionManager.currentOrNull()`을 검사하고 없으면 repository 이름을 포함한 `IllegalStateException`을 던진다.
4. `AppointmentItemRepository`와 `AppointmentOperationalExceptionRepository`의 caller-owned DSL 진입점에 guard를 적용한다. 기존 custom join/batch/status SQL은 변경하지 않는다.
5. `AppointmentOperationalExceptionRepository`는 `APPEND-DSL`로 유지하고 ID 없는 `AppointmentOperationalException` API를 generic record로 변환하지 않는다.

**Verification (GREEN):**

```bash
./gradlew :appointment-core:test --tests '*AppointmentIdempotencyRepositoryTest' --tests '*AppointmentStateHistoryRepositoryTest' --tests '*TreatmentSpaceRepositoryTest' --tests '*AppointmentItemRepositoryTest' --tests '*AppointmentOperationalExceptionRepositoryTest' --no-daemon
```

H2 parameterized dialect가 모두 실행되는지 확인한다. 실패하면 가장 작은 repository부터 수정하고 전체 core test를 재실행한다.

**Rollback:** mapper 또는 guard가 기존 SQL 결과를 바꾸면 해당 repository 파일만 이전 단계로 복구한다. Composite query를 공통 CRUD로 대체하지 않는다.

## Task 4 — RED/GREEN: Spring transaction annotation과 service 계약

**Files:**

- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentServiceTransactionContractTest.kt` (신규)
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentDddEventTransactionBoundaryTest.kt` (기존 fixture 재사용이 필요할 때만 최소 변경)

**Actions:**

1. RED 단계에서 `AppointmentService`의 create/status/cancel write entry point에 Spring `@Transactional`이 없고 read query entry point에 `readOnly`가 없음을 metadata assertion으로 고정한다.
2. GREEN 단계에서 proxy 호출 대상인 public `create` write와 `getByDateRange` read에 `@Transactional`/`@Transactional(readOnly = true)`를 선언한다. status/cancel의 public scope entry point에도 `@Transactional`을 선언한다.
3. 기존 `transaction {}` 호출은 같은 Spring/Exposed connection 참여를 확인한 뒤에만 유지한다. 이 변경에서 외부 event signal, outbox, coroutine `withContext`, idempotency retry 순서는 바꾸지 않는다.
4. `AnnotationTransactionAttributeSource` 또는 `AnnotatedElementUtils`로 propagation `REQUIRED`, rollback 기본값, read-only를 검증한다.
5. H2 Spring proxy fixture에서 성공 write는 commit되고 writer 예외는 appointment·idempotency·outbox를 함께 rollback하는지 확인한다. 기존 `AppointmentNotificationAtomicityTest`의 direct fixture는 보존한다.

**Verification:**

```bash
./gradlew :appointment-api:test --tests '*AppointmentServiceTransactionContractTest' --tests '*AppointmentNotificationAtomicityTest' --tests '*AppointmentDddEventTransactionBoundaryTest' --no-daemon
```

**Rollback:** suspend proxy가 기존 API 호출을 깨거나 connection identity가 달라지면 annotation을 write entry point 중 non-suspend `create`부터 축소하고, 실패한 메서드와 근거를 설계 lesson에 기록한다.

## Task 5 — composite/split 계약 문서화와 후속 inventory

**Files:**

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentItemRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentOperationalExceptionRepository.kt`
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistApplicationService.kt`
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationService.kt`
- `docs/superpowers/specs/2026-08-19-issue-309-composite-repository-transaction-design.md`

**Actions:**

1. KDoc에 caller-owned current transaction, `APPEND-DSL`, `SPLIT-TRANSACTION` 사유를 실제 메서드 단위로 남긴다.
2. Waitlist reservation/business/finalization 세 단계와 authentication tenant/read/password/issue 분리를 유지한다. 이번 task에서는 이 흐름을 하나의 `@Transactional`로 합치지 않는다.
3. `rg -n "transaction\(database\)|private val database: Database"` inventory를 다시 실행하여 bootstrap/readiness/split/multi-database 허용 사유와 후속 후보를 문서 표에 반영한다.
4. `AppointmentRepository`, waitlist claim/delivery, outbox, resource allocation의 custom lock/query를 변경하지 않았는지 diff로 확인한다.

**Verification:**

```bash
rg -n "transaction\(database\)|private val database: Database|SKIP LOCKED|FOR UPDATE" appointment-*/src/main/kotlin
git diff -- appointment-core/src/main/kotlin appointment-api/src/main/kotlin
```

**Rollback:** inventory가 scope를 넓히거나 external IO를 transaction 안으로 넣게 만들면 문서만 남기고 해당 production 변경을 취소한다.

## Task 6 — 모듈 검증·정적 검사·lesson

**Files:**

- `docs/superpowers/reviews/2026-08-19-issue-309-composite-repository-transaction-implementation-review.md`
- `docs/lessons/2026-08-19-issue-309-composite-repository-transaction.md`
- 변경된 source/test/KDoc 파일

**Actions:**

1. 영향 모듈을 순서대로 compile/test한다. core가 통과한 뒤 api를 실행한다.
2. H2 wiring/basic CRUD와 PostgreSQL singleton lock/composite suite를 별도 순서로 실행한다. PostgreSQL fixture의 정확한 helper/launcher 이름과 실행 output을 review에 기록한다.
3. `git diff --check`, Kotlin compile warnings, dependency insight, `@Testcontainers` 금지 패턴, production raw `transaction(database)` inventory를 점검한다.
4. 구현 review에 Kotlin final checklist(KT-FIN-01..11), testing checklist(KT-TEST-01..05), common gates, Type-A A-01..A-12를 결과와 함께 기록한다.
5. lesson에는 `LongJdbcRepository`가 record/Table DSL에 맞고 DAO `ExposedJdbcRepository`를 무리하게 사용하지 않은 이유, operational append port를 유지한 이유, Spring proxy와 Exposed current transaction 연결을 기록한다.

**Verification:**

```bash
./gradlew :appointment-core:compileKotlin :appointment-core:test --no-daemon
./gradlew :appointment-api:compileKotlin :appointment-api:test --no-daemon
git diff --check
rg -n "@Testcontainers|transaction\(database\)" appointment-*/src/main/kotlin appointment-*/src/test/kotlin
```

**Rollback:** module test failure는 마지막 production change부터 하나씩 되돌려 원인 범위를 좁힌다. 테스트 skip을 성공으로 보고하지 않는다.

## Task 7 — 전달 준비와 stop condition

**Files:** 변경된 전체 파일, Issue #309 PR metadata.

**Actions:**

1. Lore trailer를 갖춘 한국어 commit을 만든다.
2. exact branch를 push하고 `gh pr create --base develop --head refactor/issue-309-composite-repository-transaction`으로 PR을 만든 뒤 body/assignee/labels/milestone/closing reference를 live read-back한다.
3. CI와 review가 fresh green인 경우에만 merge-ready report를 작성한다. merge는 별도 fresh explicit approval 뒤 `gh pr merge --rebase --delete-branch`로 수행한다.
4. merge 확인 후 root `develop`을 `git fetch` + `git merge --ff-only origin/develop`으로 동기화하고, Issue #309 worktree와 local/remote branch만 proven merged 상태에서 정리한다. 다른 worktree와 root의 `.superpowers/`, `.workflow-inputs/`는 보존한다.

**Stop condition:** 구현·검증·문서·PR/CI metadata가 모두 fresh evidence를 가지며, merge approval 전에는 delivery를 멈춘다. CI 실패, review P1, branch drift, fixture 실패가 있으면 해당 원인을 수정·재검증하기 전 merge하지 않는다.

