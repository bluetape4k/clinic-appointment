# Booking Reliability Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to execute this plan task-by-task, preserving the order and verification gates.

**Goal:** Issue #176의 승인된 설계 기준을 실제 예약 서비스에 구현하여, 병원별 버전 정책과 고객 책임 사건을 바탕으로 새 예약의 자격을 설명 가능하고 멱등적으로 판단한다. 기존 `CONFIRMED` 예약은 자동 변경하지 않으며, 이름·전화번호·직원 자유입력 label은 새 판단 저장소로 유입하지 않는다.

**Architecture:** `appointment-core`가 typed attribution, 정책 스냅숏, evaluator, immutable decision 계약과 Exposed 저장소를 소유한다. `appointment-event`는 신뢰된 예약 결과 이벤트를 typed 사건으로 변환한다. `appointment-api`는 정책/직원 API와 feature mode(`OFF|SHADOW|ENFORCE`)를 제공하고, 예약 commitment 경계에서 `#170`이 사용할 read-only eligibility port를 호출한다. 외부 회원/알림/대기열 서비스는 동기 트랜잭션 안으로 끌어들이지 않고 commit 이후 outbox/job 경계에 둔다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed ORM, Jackson, Flyway, JUnit 5, MockK, `bluetape4k-assertions`, 기존 bluetape4k singleton DB launcher와 Gradle 모듈 테스트.

---

## 실행 전 계약

- 구현 대상은 현재 feature worktree `feat/issue-176-booking-reliability`이며 `develop`에는 직접 쓰지 않는다.
- 새 모듈·외부 dependency·회원 프로필 복제·영구 blacklist·결제/법적 판단은 추가하지 않는다.
- 기존 schemaVersion 1 정책 payload의 누락 threshold는 legacy compatibility 상태(`POLICY_DISABLED`)로 decode해 예약 동작을 바꾸지 않고, 새 schemaVersion 2 저장 payload에는 모든 threshold를 명시적으로 기록한다.
- 모든 Exposed read/write는 `transaction {}` 안에서 실행한다. 회원 API, 알림, outbox dispatch는 트랜잭션 밖에 둔다.
- 모든 새 public Kotlin API에는 한국어 KDoc을 작성한다. GitHub issue/PR/commit metadata는 영어로 작성한다.
- `booking.reliability.mode` 기본값은 `OFF`다. `OFF`에서는 기존 예약 동작과 DB decision 쓰기를 변경하지 않는다.
- 실패 시 migration은 additive 상태로 남겨도 애플리케이션을 `OFF` 또는 `SHADOW`로 내릴 수 있어야 하며, 기존 `CONFIRMED` row는 어떤 경로에서도 update/cancel하지 않는다.

## 작업 순서와 소유 범위

각 작업은 앞 작업이 생성한 타입·마이그레이션·테스트를 사용한다. 한 작업의 production write scope는 다른 작업과 겹치지 않게 유지하고, 통합 작업에서만 기존 commitment service를 수정한다.

### Task 1 — 정책 threshold 계약을 확장하고 하위 호환을 고정한다

**Complexity:** M · **Owner:** `appointment-core` policy · **Depends on:** 승인된 명세만

**Files:**

- 수정: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/CapacityAndReliabilityPolicies.kt`
- 수정: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyPayloadCodec.kt`
- 수정: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyValidator.kt`
- 수정: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyCompiler.kt`
- 수정: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyHasher.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyCompilerTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyHashTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyValidatorTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/EffectiveSchedulingPolicyServiceTest.kt`

**Steps:**

1. RED: tenant payload, clinic `INHERIT`/`SET`, 범위 오류, legacy JSON 누락 필드, 동일 semantic payload hash 동일성 테스트를 먼저 추가하고 각 테스트가 기대 실패하는지 확인한다.
2. `PriorityAndReliabilityPolicy`에 `lookbackDays`, `lateCancellationWindowMinutes`, `noShowThreshold`, `lateCancellationThreshold`, `coolingOffHours`를 추가한다. tenant에서 필수인 값과 clinic override 가능한 값을 명세의 `INHERIT` 규칙에 맞춰 구분한다.
3. wire DTO에 nullable/default decode 경계를 두어 기존 schemaVersion 1 payload의 누락 threshold를 `thresholdsPresent=false`인 legacy-compatibility 상태로 읽고 `POLICY_DISABLED`(예약 동작 변경 없음)로 컴파일한다. 새 schemaVersion 2 payload는 threshold를 명시해야 하며, 일부만 0인 값은 `DISABLE`로 오인하지 않도록 validator/compiler가 effective policy 완전성·임계값·기간·overflow를 거부한다.
4. compiler의 source path와 hasher 입력에 새 필드를 포함하고, reader는 schemaVersion 1/2를 재현 가능하게 읽으며 새 write는 schemaVersion 2로 고정한다. unknown field rejection·policy snapshot/version digest가 그대로 유지되는지 검증한다.
5. GREEN 후 policy factory 중복을 줄이고, KDoc/오류 메시지를 한국어로 정리한다.

**Proof:**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyCompilerTest" --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyHashTest" --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyValidatorTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.policy.EffectiveSchedulingPolicyServiceTest"
```

**Rollback/rerun:** codec/validator 실패 시 production API를 건드리지 않고 policy 파일과 테스트만 되돌린다. payload 필드명이나 기본값을 바꾸면 명세와 이 task의 RED/GREEN 테스트를 함께 다시 검토한다.

### Task 2 — typed attribution과 결정 evaluator를 구현한다

**Complexity:** L · **Owner:** `appointment-core` reliability domain · **Depends on:** Task 1

**Files:**

- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/reliability/BookingReliabilityModel.kt`
- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/reliability/BookingReliabilityRecords.kt`
- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/reliability/BookingReliabilityEvaluator.kt`
- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/reliability/BookingEligibilityPort.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/reliability/BookingReliabilityEvaluatorTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/reliability/BookingEligibilityPortTest.kt`

**Steps:**

1. RED: `NO_SHOW`, late `CANCELLED`, on-time cancellation, `CLINIC`, `SYSTEM`, `UNKNOWN`, cooling-off, policy disabled, stale snapshot, unavailable, and bounded trigger/cursor cases를 Given/When/Then으로 고정한다.
2. `Outcome`, `Responsibility`, `CancellationTiming`, `Source`, allowlist `BookingReliabilityReasonCode`, `BookingEligibilityDecision`, `BookingEligibilityQuery`를 opaque `MemberId` 기반으로 정의한다. 새 모델에는 이름·전화번호·자유입력 reason을 두지 않는다.
3. evaluator는 기간과 threshold를 policy snapshot으로 고정하고 member 책임 사건만 합산한다. clinic/system/unknown 사건은 제외하되 `UNATTRIBUTED_EVENT_EXCLUDED` 감사 reason을 유지한다.
4. response read cap 100, trigger ID 32, `hasAdditionalTriggers: Boolean`, `auditCursor: String?`를 stable decision/HTTP DTO contract에 포함한다. digest는 정렬된 입력·policy version·source version으로 계산하고 입력 순서가 달라도 동일하게 한다.
5. 포트는 `decisionId`, `policySnapshotId/version`, `digest`, `expiresAt`, bounded reason/count를 반환하며 외부 회원/알림 호출을 포함하지 않는다.
6. GREEN 후 불변 data class, `require*` 검증, 한국어 KDoc, 시간 계산 helper를 정리한다.

**Proof:**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.reliability.BookingReliabilityEvaluatorTest" --tests "io.bluetape4k.clinic.appointment.reliability.BookingEligibilityPortTest"
```

**Risk/rollback:** evaluator가 UNKNOWN을 고객 책임으로 계산하거나 response cap을 넘기면 P1이다. 실패 시 저장소/HTTP 계층을 진행하지 않고 domain 테스트를 먼저 복구한다.

### Task 3 — Exposed 원장·decision·override 저장소와 idempotency/CAS를 추가한다

**Complexity:** L · **Owner:** `appointment-core` persistence · **Depends on:** Task 2

**Files:**

- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/BookingReliabilityTables.kt`
- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityRepository.kt`
- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityJobRepository.kt`
- 신규: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityRepositorySupport.kt`
- 수정: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
- 신규: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityRepositoryTest.kt`
- 신규: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityJobRepositoryTest.kt`
- 신규: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/reliability/BookingReliabilityPrivacyTest.kt`

**Steps:**

1. RED: 동일 logical event의 dedupe key를 `(tenantId, clinicId, memberId, eventId, sourceVersion)`로 고정한다. 같은 eventId의 새 sourceVersion 정정은 허용하고, 같은 key 재수신, decision upsert retry, stale expected digest CAS 실패, keyset audit page, retention/pseudonymization 경계를 먼저 작성한다.
2. 네 테이블을 계약으로 정의한다: append-only `booking_reliability_events`, `booking_reliability_decisions`, `booking_reliability_overrides`와 durable worker 상태 `booking_reliability_reevaluation_jobs`. MemberId, clinic/tenant, policy version, source appointment IDs, bounded counts, reason codes, effective/expiry, actor/correlation, digest를 저장하고 PII column은 만들지 않는다.
3. job repository는 DB-time lease claim, owner-fenced checkpoint/defer/complete/fail, monotonic keyset cursor, max attempts/backoff, pause/resume, dead-letter/quarantine를 `transaction {}` 안에서 제공한다. 일반 repository는 unique digest/source-version conflict를 이미 처리된 결과로 반환하고, override/clear는 원 decision update가 아니라 새 audit row를 append한다.
4. decision 조회는 최신 policy snapshot과 bounded keyset cursor를 사용하고, 100건 row/32개 trigger 제한을 DB와 mapping 양쪽에서 지킨다.
5. H2 `SchemaUtils.createMissingTablesAndColumns`/`Table.deleteAll()` fixture로 RED/GREEN을 확인한다. MySQL/PostgreSQL 차이는 SQL migration task에서 별도로 검증한다.

**Proof:**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.BookingReliabilityRepositoryTest" --tests "io.bluetape4k.clinic.appointment.repository.BookingReliabilityJobRepositoryTest" --tests "io.bluetape4k.clinic.appointment.reliability.BookingReliabilityPrivacyTest"
```

**Risk/rollback:** Exposed receiver shadowing, nullable mapping, unique index divergence는 데이터 손상 위험이므로 compile 경고와 H2 repository test를 먼저 고친다. schema가 이미 적용된 환경에서는 destructive migration을 만들지 않고 `OFF`로 rollback한다.

### Task 4 — 신뢰된 appointment 결과 이벤트와 Flyway V17 migration을 연결한다

**Complexity:** L · **Owner:** `appointment-event` + `appointment-api` migrations · **Depends on:** Task 3

**Files:**

- 신규: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/reliability/AppointmentReliabilityEvent.kt`
- 신규: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/reliability/BookingReliabilityEventIngress.kt`
- 테스트: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/reliability/BookingReliabilityEventIngressTest.kt`
- 신규: `appointment-api/src/main/resources/db/migration/h2/V17__add_booking_reliability.sql`
- 신규: `appointment-api/src/main/resources/db/migration/mysql/V17__add_booking_reliability.sql`
- 신규: `appointment-api/src/main/resources/db/migration/postgresql/V17__add_booking_reliability.sql`
- 신규: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/BookingReliabilityMigrationTestSupport.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/BookingReliabilityQueryPlanTest.kt`

**Steps:**

1. RED: trusted source event만 수락하고 `(tenant, clinic, member, eventId, sourceVersion)` identity로 duplicate/reorder를 멱등 처리한다. 같은 eventId의 더 높은 sourceVersion 정정은 새 사건으로 저장하되 과거 snapshot은 삭제하지 않는다. clinic/system/unknown attribution은 계산에서 제외하고, invalid actor/source는 quarantine/reject, batch retry는 같은 digest를 반환하는 테스트를 작성한다.
2. 기존 `SchedulingEventTrustVerifier`, inbox/quarantine, source aggregate version 패턴을 재사용하여 typed event를 core repository command로 변환한다. 자유 입력 note/reason은 evaluator 입력으로 전달하지 않는다.
3. H2/MySQL/PostgreSQL V17을 additive로 작성한다. 각 backend의 UUID/text/timestamp/index 문법을 기존 V13–V16 스타일에 맞추고 `ux_booking_reliability_event_identity(tenant_id, clinic_id, member_id, event_id, source_version)`, `ux_booking_reliability_decision_digest(tenant_id, clinic_id, member_id, evaluation_digest)`, `ux_booking_reliability_override_idempotency(tenant_id, clinic_id, member_id, idempotency_key)`를 명시한다. override CAS는 expected decision version/digest 조건으로 실행한다. durable job table에는 lease owner/expiry, cursor, attempts, next retry, pause/dead-letter state와 clinic/member scope index를 둔다.
4. `BookingReliabilityMigrationTestSupport.verifyV17Migration(...)`를 기존 `FlywayMigrationTest`, `FlywayMySQLMigrationTest`, `FlywayPostgreSQLMigrationTest`에 연결해 세 backend script의 version/order/table/index 이름을 확인하고, 기존 V16 schema와 fresh migration 모두를 검증한다.
5. 기존 `ProfileReevaluationQueryPlanTest`와 `NotificationOutboxQueryPlanTest` 패턴을 재사용해 member lookback, latest decision/digest, active override, audit keyset 쿼리의 PostgreSQL/MySQL `EXPLAIN`을 확인한다. 예상 index 이름, `LIMIT 100/32`, full table scan 부재를 증명하지 못하면 다음 task로 진행하지 않는다.
6. PostgreSQL/MySQL 검증은 기존 singleton launcher만 사용하고 raw `GenericContainer`를 만들지 않는다. `@ResourceLock(API_INTEGRATION_RESOURCE, READ_WRITE)`와 `AfterAll` schema cleanup을 적용하며, container-backed Gradle 명령은 worktree/모듈 사이에서 순차 실행한다.

**Proof:**

```bash
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.reliability.BookingReliabilityEventIngressTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.BookingReliabilityQueryPlanTest"
```

**Risk/rollback:** 실제 DB/container 검증은 한 번에 하나씩 실행한다. migration checksum/SQL 오류가 나면 V17 파일만 수정하고 이미 적용된 버전을 재작성하지 않는다. partial rollout은 `mode=OFF`로 유지한다.

### Task 5 — feature mode, evaluator application service, 직원 API를 제공한다

**Complexity:** L · **Owner:** `appointment-api` reliability · **Depends on:** Task 3–4

**Files:**

- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityProperties.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityConfiguration.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityApplicationService.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/DefaultBookingReliabilityApplicationService.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityCommandPreconditions.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/BookingReliabilityController.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/BookingReliabilityDtos.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/BookingReliabilityErrorCode.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityApplicationServiceTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/BookingReliabilityControllerTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/BookingReliabilitySecurityIntegrationTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityConfigurationTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/BookingReliabilityOpenApiTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityApiDocumentationTest.kt`

**Steps:**

1. RED: `OFF|SHADOW|ENFORCE` default/invalid property, tenant·clinic scope mismatch, missing capability, stale/unavailable decision, override/clear permission, audit cursor pagination, no PII response cases를 고정한다.
2. immutable constructor-bound properties와 조건부 configuration을 만들고 optional #170 bean이 없어도 context가 뜨게 한다. `ApplicationContextRunner`로 positive/negative condition과 default mode를 검증한다.
3. 승인된 endpoint를 구현한다: decision `GET`, staff `POST override`, `POST clear`, `GET audit`를 `/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability/...`에 매핑한다.
4. generic tenant matcher보다 먼저 route-specific matcher를 등록한다. decision은 `booking-reliability:read`, audit은 별도 audit capability, override/clear는 `booking-reliability:write`와 exact clinic membership을 요구한다. `BookingReliabilitySecurityIntegrationTest`에서 generic tenant read, wrong clinic, missing scope, read-vs-audit/write, patient actor를 모두 거부한다.
5. `ActorContextResolver`와 clinic capability/tenant guard를 재사용한다. caller에는 allowlist error code만 노출하고 raw member profile, SQL, stack trace, free text를 노출하지 않는다. 모든 request body DTO는 `@JsonIgnoreProperties(ignoreUnknown = false)`와 명시적 unknown-field rejection을 사용하며 actor/clinic/member/memberContact/free-text spoofing을 4xx로 끝낸다.
6. override/clear는 `Idempotency-Key`와 strong `If-Match` 또는 `decisionId + evaluationDigest` precondition을 모두 요구한다. 동일 key/digest retry는 같은 audit 결과를 반환하고, stale digest·다른 body의 key 재사용은 `BOOKING_DECISION_STALE` 또는 payload conflict로 거부한다.
7. HTTP contract를 고정한다. decision/audit 성공은 `200`, override/clear 성공은 `200`, malformed body/cursor는 `400`, missing capability 또는 wrong clinic은 `403 BOOKING_RELIABILITY_FORBIDDEN`, stale precondition은 `409 BOOKING_DECISION_STALE`, restricted new booking은 `409 BOOKING_REVIEW_REQUIRED`, unavailable은 `503 BOOKING_DECISION_UNAVAILABLE`으로 caller-safe envelope을 반환한다.
8. `GET audit`는 opaque `cursor`, `limit`(default 32, max 100), allowlisted `kind`/`from`/`to` filter만 받고 `(createdAt DESC, auditId DESC)`로 정렬한다. `nextCursor`와 bounded items를 반환하고 cursor 내부에 raw MemberId/appointment ID를 넣지 않는다.
9. service는 policy snapshot resolve → evaluator/repository → bounded DTO 순서로 실행하고, 외부 member/notification lookup은 하지 않는다. SHADOW는 결과를 측정하되 commitment을 막지 않는다.

**Proof:**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApplicationServiceTest" --tests "io.bluetape4k.clinic.appointment.api.controller.BookingReliabilityControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.BookingReliabilitySecurityIntegrationTest" --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityConfigurationTest" --tests "io.bluetape4k.clinic.appointment.api.controller.BookingReliabilityOpenApiTest" --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiDocumentationTest"
```

**Risk/rollback:** 권한 우회·기본 mode 변경·error code drift는 P0/P1이다. 실패 시 controller를 비활성화하고 `OFF` 경로만 남긴 뒤 security negative test부터 복구한다.

### Task 6 — `#170` read-only port를 commitment 경계에 연결한다

**Complexity:** L · **Owner:** `appointment-api` commitment integration · **Depends on:** Task 5

**Files:**

- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/BookingEligibilityGate.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationService.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentProposalService.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/BookingEligibilityGateTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/BookingEligibilityGateQueryBudgetTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/BookingEligibilityTransactionBoundaryTest.kt`
- 수정/추가: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationServiceTest.kt`

**Steps:**

1. RED: 새 request/`PROPOSED`/`HELD`/신규 `CONFIRMED`는 `ENFORCE`에서 `RESTRICTED`면 거부 또는 `BOOKING_REVIEW_REQUIRED`, stale면 재평가, unavailable이면 `BOOKING_DECISION_UNAVAILABLE`, existing `CONFIRMED` update/cancel은 evaluator를 호출하지 않는 테스트를 먼저 만든다.
2. idempotency claim 이후 최종 eligibility decision 재검증을 기존 commitment command의 같은 Exposed transaction 안에서 allocation/commitment CAS 직전에 실행한다. policy/override가 동시에 바뀌어 stale이 되면 proposal/hold/allocation을 전부 rollback하고 새 decision을 만들며, 외부 member/notification/outbox dispatch는 commit 이후에만 실행한다.
3. `BookingEligibilityGate`는 `BookingEligibilityPort`만 의존하고 #170 waitlist/offer 수명주기를 구현하지 않는다. 현재 checkout에 #170 구현이 없다는 사실을 계약 테스트와 KDoc에 남긴다.
4. proposal 생성·hold·direct confirm의 신규 경계에만 gate를 삽입한다. 이미 저장된 `CONFIRMED` commitment, 만료 처리, 직원 clear/override는 기존 state machine 보호를 유지한다.
5. digest/policy version/expiry를 downstream claim/offer adapter가 읽을 수 있는 immutable contract로 전달하고, outbox/job retry에서도 동일 decision을 재사용한다.
6. GREEN 후 기존 commitment test fixture와 mock interaction을 정리하고, `confirmVerified`로 unexpected member/notification call을 차단한다.
7. query-count/transaction-boundary test로 gated command당 eligibility evaluation이 정확히 한 번인지, `OFF`/`SHADOW`가 불필요한 DB round trip을 추가하지 않는지, 이미 유효한 policy snapshot을 중복 조회하지 않는지, 외부 I/O가 transaction 안에 들어오지 않는지 증명한다. stale policy/override 경쟁 중 allocation·proposal row가 남지 않는 rollback/TOCTOU 테스트를 포함한다.

**Proof:**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.commitment.BookingEligibilityGateTest" --tests "io.bluetape4k.clinic.appointment.api.service.DefaultAppointmentCommitmentApplicationServiceTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.commitment.BookingEligibilityGateQueryBudgetTest" --tests "io.bluetape4k.clinic.appointment.api.commitment.BookingEligibilityTransactionBoundaryTest"
```

**Risk/rollback:** false blocking과 existing confirmed mutation이 가장 큰 위험이다. 둘 중 하나가 재현되면 Task 6 변경을 되돌리고 `SHADOW`/`OFF`로 재검증한 뒤 gate 위치를 다시 검토한다.

### Task 7 — 운영 진단, retention, rollout/rollback을 완성한다

**Complexity:** M · **Owner:** `appointment-api` operations · **Depends on:** Task 5–6

**Files:**

- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityProperties.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityHealthIndicator.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityMetrics.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityRetentionService.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityRetentionRunner.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityReevaluationWorker.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityRetryPolicy.kt`
- 신규: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilitySchemaReadiness.kt`
- 신규: `docs/runbooks/booking-reliability.ko.md`
- 신규: `docs/runbooks/booking-reliability.md`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityHealthIndicatorTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityMetricsTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityWorkerTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityRetentionTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/reliability/BookingReliabilityCanaryReadinessTest.kt`
- 신규: `docs/runbooks/booking-reliability-canary-evidence-template.md`

**Steps:**

1. RED: unavailable backlog, attribution-missing ratio, p95/p99 decision latency, duplicate suppression, stale retry rate, override/clear spike, retention deletion/pseudonymization, and mode transition을 health/metrics 계약으로 고정한다. Metric tag는 닫힌 enum만 사용하고 raw `MemberId`를 tag로 사용하지 않는다.
2. `BookingReliabilityReevaluationWorker`는 job repository의 durable keyset cursor, bounded batch/per-clinic limit, DB-time lease/fencing, exponential backoff, retry exhaustion dead-letter/quarantine, pause/resume, duplicate-free restart를 소유한다. `BookingReliabilityRetryPolicy`는 cancellation/deadline을 보존하고 lease loss를 즉시 중단한다. lease loss·expired lease reclamation·checkpoint monotonicity·retry exhaustion·restart 중복 없음 테스트를 포함한다.
3. runbook에 OFF→SHADOW→ENFORCE canary 순서(최소 24시간 또는 1,000 decisions 중 늦은 조건), metric 기반 abort threshold, rollback, operator clear/override, incident correlation 방법을 기록한다. metric/alert contract test가 canary 판단에 필요한 모든 수치를 고정한다.
4. `BookingReliabilityRetentionService`와 주기 runner의 raw MemberId 접근 권한·삭제 범위를 clinic/tenant로 제한하고, legal-hold/retention class, pseudonymization 후 audit cursor와 digest 재현 규칙을 명시한다. 기존 retention runner의 scheduler/clock 패턴을 재사용하며 partial failure와 idempotent rerun을 격리한다.
5. `BookingReliabilitySchemaReadiness`와 health/readiness는 `OFF`, `SHADOW`, `ENFORCE`별 fail-open/closed를 구분한다. V17 schema/table/index가 없거나 migration state가 stale이면 worker와 ENFORCE를 막고, 알림/외부 회원 API 장애가 동기 예약 transaction을 붙잡지 않도록 확인한다.
6. `BookingReliabilityCanaryReadinessTest`와 증거 템플릿은 최소 24시간 또는 1,000 decisions, p95/p99 latency, duplicate decision 0, unresolved unavailable backlog 0, attribution-missing <1%, raw PII 0, closed tag cardinality를 모두 충족하지 않으면 ENFORCE 승격을 거부한다.

**Proof:**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityHealthIndicatorTest" --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityMetricsTest" --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityWorkerTest" --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityRetentionTest" --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityCanaryReadinessTest"
```

**Risk/rollback:** 운영 지표 누락은 canary를 시작할 수 없는 P1이다. 지표가 없으면 ENFORCE로 승격하지 않고 SHADOW/OFF에 머문다.

### Task 8 — 문서·README·시각화 산출물을 동기화한다

**Complexity:** M · **Owner:** docs · **Depends on:** Task 1–7

**Files:**

- 신규: `docs/booking-reliability-policy.ko.md`
- 신규: `docs/booking-reliability-policy.md`
- 신규: `docs/api/booking-reliability.md`
- 수정: `README.ko.md`, `README.md`
- 수정: `appointment-core/README.ko.md`, `appointment-core/README.md`
- 수정: `appointment-event/README.ko.md`, `appointment-event/README.md`
- 수정: `appointment-api/README.ko.md`, `appointment-api/README.md`
- 신규: `docs/visual-companions/booking-reliability-workflow-ko-light.html`
- 신규: `docs/visual-companions/booking-reliability-workflow-ko-dark.html`
- 신규: `docs/visual-companions/booking-reliability-workflow-en-light.html`
- 신규: `docs/visual-companions/booking-reliability-workflow-en-dark.html`
- 신규: 대응하는 workflow PNG 4개, ERD/sequence/class SVG·PNG 산출물
- 수정: `docs/visual-companions/README.md`
- 수정: `docs/visual-companions/manifest.json`

**Steps:**

1. 한국어 기준 문서를 source of truth로 작성하고 영어판은 API/식별자/오류 코드가 동일한 source-equivalent 문서로 유지한다. 승인된 명세와 구현의 차이는 결정·마이그레이션 섹션에 반영한다.
2. README 각 locale에 설치/실행 명령, `OFF|SHADOW|ENFORCE`, endpoint 예시, 개인정보 경계, `CONFIRMED` 보호를 추가하고 locale parity를 비교한다.
3. `$bluetape-writer` 규칙에 맞춰 KDoc/README 문장을 검토한다. `$bluetape-diagram` 규칙에 맞춰 업무 흐름은 HTML+PNG 4변형, ERD/sequence/class는 정적 SVG+PNG 변형으로 생성한다.
4. `docs/api/booking-reliability.md`의 copy-paste JSON 예제를 production DTO/codec으로 decode하고, OpenAPI test로 route/method/status/error/header/precondition/cursor와 금지된 actor/contact/body-scope 필드를 고정한다.
5. HTML의 dark/light CSS, ko/en text, responsive layout을 브라우저에서 확인하고 각 PNG를 전체 해상도로 확인한다. Markdown/코드/API 불일치는 문서가 아니라 source와 계약을 우선해 수정한다.

**Proof:**

```bash
python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-endpoint-audit.py docs/visual-companions
python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-sequence-style-audit.py docs/visual-companions
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.BookingReliabilityOpenApiTest" --tests "io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiDocumentationTest"
for base in README appointment-core/README appointment-event/README appointment-api/README; do test -f "${base}.md" && test -f "${base}.ko.md"; done
test -f docs/booking-reliability-policy.md && test -f docs/booking-reliability-policy.ko.md && test -f docs/api/booking-reliability.md
git diff --check
```

**Rollback/rerun:** 시각화 검증이 실패하면 코드 계약을 바꾸지 않고 해당 변형을 다시 생성한다. 문서와 source가 어긋나면 기준 문서보다 실제 API/test 증거를 우선하여 문서만 수정한다.

### Task 9 — 통합 검증, 성능·안정성 점검, lesson을 완료한다

**Complexity:** L · **Owner:** main integration · **Depends on:** Task 1–8

**Files:**

- 신규: `docs/lessons/2026-08-01-issue-176-booking-reliability.md`
- 수정 대상 없음(검증 중 발견된 결함은 해당 task 파일로 되돌아간다)

**Steps:**

1. RED/GREEN 증적, spec-to-plan traceability, migration V17, API security, README locale, diagram manifest를 순서대로 재검토한다.
2. 작은 테스트 → module compile/test → migration test → 전체 영향 모듈 test 순으로 실행한다. Testcontainers/실제 DB 검증은 core→event→api 순서로 한 번에 하나씩 실행한다. singleton launcher, `@ResourceLock(API_INTEGRATION_RESOURCE, READ_WRITE)`, `AfterAll` cleanup 증거가 없는 DB test는 PASS로 세지 않는다.
3. 성능·안정성 scan에서 bounded query/trigger cap, batch retry/backpressure, blocking call, coroutine cancellation, DB index/round trip, startup/shutdown cleanup을 확인한다. 1,000 decision canary 기준을 재현할 수 있는 짧은 benchmark 또는 query-count proof를 기록한다.
4. P0/P1은 수정 후 영향을 받은 테스트와 review lane을 다시 실행한다. P2/P3는 수정·후속 issue·명시적 rationale 중 하나로 닫는다.
5. lesson에는 개인정보 경계, 기존 `CONFIRMED` 보호, policy snapshot/cas, migration rollback, review miss와 future guard를 증거와 함께 기록하고 Lore commit에 포함한다.

**Proof:**

```bash
./gradlew :appointment-core:compileTestKotlin :appointment-event:compileTestKotlin :appointment-api:compileTestKotlin
./gradlew :appointment-core:test
./gradlew :appointment-event:test
./gradlew :appointment-api:test
git diff --check
git status --short --untracked-files=all
```

**Stop condition:** 대상 모듈의 fresh compile/test, migration·문서·diagram 검사, plan/spec verifier, six-lens final review가 모두 PASS이고 P0=0/P1=0일 때만 PR delivery gate로 이동한다.

## 명세·수용 기준 추적성

| 기준 | 구현 task | 검증 증거 |
|---|---|---|
| 병원별 versioned threshold와 clinic override | Task 1 | policy compiler/validator/codec/hash tests |
| 고객 책임만 집계하고 clinic/system/unknown 제외 | Task 2, 4 | evaluator + event ingress tests |
| immutable decision audit, bounded read | Task 2, 3 | evaluator/repository/privacy tests |
| staff preview/override/clear와 clinic capability | Task 5, 7 | controller/security/config/OpenAPI/health tests |
| `#170` read-only decision contract | Task 2, 6 | port/gate/commitment tests |
| PROPOSED/HELD/신규 CONFIRMED 적용, 기존 CONFIRMED 보호 | Task 6 | commitment regression tests |
| idempotency/CAS/stale/outage/reorder/batch retry | Task 3, 4, 6 | repository/ingress/gate tests |
| H2/MySQL/PostgreSQL additive migration | Task 4 | migration test 및 순차 DB 검증 |
| OFF/SHADOW/ENFORCE, canary, rollback, observability | Task 5, 7 | context/health/metrics/runbook evidence |
| 한국어 기준 문서·API examples·README locale·visual parity | Task 5, 8 | DTO/OpenAPI/docs-as-contract, writer/diagram audit 및 full PNG inspection |
| fresh module tests, P0/P1 zero, durable lesson | Task 9 | Gradle output, review artifact, lesson commit |

## 위험 예측 및 대응 (Step 3-P)

| 위험 | 조기 신호 | 완화 | rollback/rerun |
|---|---|---|---|
| 대규모 clinic에서 decision 재평가 폭증 | query latency, duplicate job, unavailable backlog 상승 | bounded read, digest idempotency, post-commit durable job, canary cap | SHADOW/OFF, Task 3·7 재검증 |
| 병원 책임 사건이 고객 책임으로 오분류 | attribution-missing 또는 UNKNOWN 비율 상승 | typed source/책임 enum, unknown 제외, quarantine, metric threshold | ENFORCE 승격 보류, Task 2·4 RED 재실행 |
| stale policy가 예약을 잘못 차단 | stale decision count, policy version mismatch | commit 직전 재검증, digest/CAS, `BOOKING_DECISION_STALE` | gate를 SHADOW/OFF로 내리고 Task 6 재검증 |
| 기존 CONFIRMED가 변경됨 | commitment update/cancel test 또는 audit에서 non-zero | state machine guard와 explicit existing-confirmed branch | 해당 gate commit revert, 기존 commitment test 전체 실행 |
| MemberId/감사 데이터가 PII로 확장됨 | schema/DTO에 name/phone/free text 발견 | schema allowlist, privacy test, API response denylist | migration 추가 중지, V17 additive 상태 유지 |
| generic tenant matcher가 reliability route 권한을 우회함 | missing-scope/wrong-clinic security test가 2xx를 반환 | `SecurityConfig` route-specific matcher를 generic matcher보다 앞에 두고 read/audit/write capability를 분리 | reliability controller 비활성화, security test부터 재실행 |
| migration backend 차이 | V17 checksum/order 또는 index 오류 | 세 SQL을 동시 설계하고 backend별 순차 테스트 | 적용 전 OFF, 실패 파일만 수정, 이미 적용된 version rewrite 금지 |
| Spring 조건/권한 우회 | ApplicationContext negative test 또는 unauthorized 2xx | constructor-bound props, explicit guards, ActorContext scope | configuration 비활성화, security tests부터 복구 |

## 구현 전후 게이트

- 계획 artifact 저장 후 `docs/superpowers/specs/...`와 이 plan을 Lore commit으로 커밋한다. `.superpowers/` untracked 산출물은 제외한다.
- Step 3-R은 performance, stability, security, operator/Ops, developer/API, user/caller 6개 독립 lane과 main integration을 사용한다. P0/P1이 0이 아니면 코드 구현으로 이동하지 않는다.
- 계획 승인 전에는 Task 1–9의 production code, migration, README, diagram을 작성하지 않는다.
- 계획 승인 후 첫 mutation 전에 `checklist-contract.md`와 `common-gates.md`에 따른 checklist를 생성하고, 정확한 feature worktree 절대 경로로 `bluetape-flow.py mutation-check`를 통과시킨다.
- PR 생성 전에는 `verification-before-completion`, final six-lens code review, lesson commit, `git diff --check`, module tests, live PR metadata/CI를 완료한다. Merge는 별도 fresh approval 없이는 수행하지 않는다.
