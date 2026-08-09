# Issue #17 closure `PENDING_RESCHEDULE` 상태 이벤트 구현 계획

> 에이전트 실행 참고: 이 계획은 승인된 설계서의 작업 순서를 그대로 따른다. 각 단계는 체크박스로 진행 상태를 기록하고, RED 테스트가 실패하는 것을 확인한 뒤 최소 구현을 추가한다.

**목표:** legacy 동기 closure 재배정 endpoint가 `PENDING_RESCHEDULE` 상태 전이와 `STATUS_CHANGED` durable outbox intent를 하나의 transaction에서 원자적으로 기록하도록 보강한다.

**구조:** `appointment-core`는 messaging 모듈을 참조하지 않고 fail-closed callback port와 bounded batch 계약만 제공한다. `appointment-api`의 composite callback은 알림 writer와 `AppointmentOutboxWriter`를 같은 caller transaction에서 호출하며, `appointment-messaging`은 canonical appointment row를 재확인해 payload를 만든다. SSE batch stream, commitment-v2, 실제 broker/Schema Registry 운영 검증은 이 계획에서 변경하지 않는다.

**기술 스택:** Kotlin 2.3, Spring Boot 4, Exposed JDBC v1, JUnit 5, bluetape4k assertions, H2/PostgreSQL TestDB dialects, Gradle module-scoped tests.

---

## 1. 변경 경계와 파일 책임

### 구현 파일

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
  - legacy active appointment ID preflight를 `LIMIT 101`로 조회하는 scoped query를 추가한다.
  - 동기 write transaction의 affected 재조회도 동일한 bounded limit을 사용하도록 기존 조회 API에 limit 경계를 추가한다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleService.kt`
  - callback의 `statusChanged` fail-closed 기본 메서드와 command context 인자를 추가한다.
  - service 경계의 `searchDays` 검증, preflight, affected 100건/후보 2,000건 상한을 구현한다.
  - optimistic update 직후 canonical row를 읽고 callback을 transaction 안에서 호출한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
  - production composite의 `statusChanged`를 알림 writer와 messaging writer에 순서대로 위임한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleController.kt`
  - REST request correlation을 command context로 만들어 동기 closure service에 전달한다.
  - `searchDays` 1..30 검증 오류가 mutation 전에 반환되도록 한다.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriter.kt`
  - status event 입력 appointment를 tenant/clinic/version/status canonical row와 비교하고 canonical payload만 insert한다.

### 테스트 파일

- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleServiceTest.kt`
  - callback 성공, fail-closed rollback, optimistic conflict, searchDays/affected/candidate limits, context 전달을 검증한다.
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriterTest.kt`
  - `STATUS_CHANGED` envelope와 canonical mismatch rollback을 검증한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleControllerTest.kt`
  - REST closure 성공 결과의 상태, outbox event type/payload/context를 검증한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleControllerPrivacyTest.kt`
  - controller mock signature 변경에 맞춰 `HttpServletRequest`와 command context 전달을 검증한다.

### 문서 파일

- `README.md` 또는 closure 범위를 설명하는 현재 README 섹션
  - synchronous closure만 `PENDING_RESCHEDULE` event를 포함하고 commitment-v2는 제외한다고 수정한다.
- `docs/runbooks/appointment-messaging-operations.md`
  - legacy closure event의 bounded read-only 대조 절차와 SSE/commitment-v2 후속 범위를 명시한다.
- `docs/lessons/2026-08-09-issue-17-closure-status-event.md`
  - fail-closed callback과 bounded preflight에서 얻은 재사용 가능한 교훈을 기록한다.

변경하지 않는 파일은 `RescheduleBatchStreamController.kt`, `RescheduleProgressEvent.kt`,
`streamClosureReschedule` 호출부, commitment-v2 proposal/closure 경로다. 이 범위는 SSE lifecycle과
부분 진행 계약을 별도 설계로 남긴 승인된 명세에 따른다.

## 2. Task 1 — repository bounded query와 core RED 테스트

**파일:** 위 책임 목록의 `AppointmentRepository.kt`, `ClosureRescheduleServiceTest.kt`

- [ ] **Step 1: 실패하는 callback 계약 테스트 작성**

`ClosureRescheduleServiceTest`에 recorder writer를 추가하고 다음 행위를 검증하는 테스트를 먼저 작성한다.

```kotlin
val observed = mutableListOf<StatusChangeCall>()
val writer = object : AppointmentRescheduleNotificationWriter {
    override fun rescheduled(tenantGroupId: Long, original: AppointmentRecord,
                              replacement: AppointmentRecord, version: Long) = Unit

    override fun statusChanged(scope: TenantClinicScope, appointment: AppointmentRecord,
                               fromState: AppointmentState, toState: AppointmentState,
                               commandContext: AppointmentCommandContext) {
        observed += StatusChangeCall(scope, appointment, fromState, toState, commandContext)
    }
}
val result = service(writer).processClosureReschedule(
    scope(clinicId), MONDAY, searchDays = 1,
    commandContext = AppointmentCommandContext.root("closure-request-1"),
)
observed.single().appointment.version shouldBeEqualTo 1L
observed.single().fromState shouldBeEqualTo AppointmentState.CONFIRMED
observed.single().toState shouldBeEqualTo AppointmentState.PENDING_RESCHEDULE
observed.single().commandContext.correlationId shouldBeEqualTo "closure-request-1"
```

- [ ] **Step 2: RED 테스트 실행**

실행:

```bash
./gradlew :appointment-core:test --no-daemon --console=plain \
  --tests 'io.bluetape4k.clinic.appointment.service.ClosureRescheduleServiceTest'
```

예상 결과: `statusChanged` 메서드와 command context가 없어 컴파일 또는 테스트가 실패한다. 기존 테스트가 즉시 통과하면 새 assertion이 실제 동작을 잡는지 확인하고 테스트를 수정한다.

- [ ] **Step 3: bounded repository query 추가**

기존 scoped active query의 공통 조건을 유지하면서 ID만 읽는 bounded method를 추가한다.

```kotlin
fun countActiveByClinicAndDate(
    scope: TenantClinicScope,
    date: LocalDate,
    activeStatuses: List<AppointmentState> = AppointmentState.ACTIVE_STATUSES,
    limit: Int,
): Int {
    require(limit > 0) { "limit must be positive" }
    return Appointments
        .select(Appointments.id)
        .where { (Appointments.clinicId eq scope.clinicId) and
            (Appointments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId)) }
        .andWhere { Appointments.appointmentDate eq date }
        .andWhere { Appointments.status inList activeStatuses }
        .andWhere { Appointments.modelVersion eq AppointmentModelVersion.LEGACY }
        .andWhere { completeAppointmentProjection() }
        .limit(limit)
        .count()
        .toInt()
}
```

`findActiveByClinicAndDate`에는 `limit: Int? = null`을 추가하고 query 생성 뒤 `limit?.let(query::limit)`을 적용한다. 동기 service는 preflight와 write transaction 모두 `limit = MAX_AFFECTED_APPOINTMENTS + 1`을 사용한다. 기존 SSE caller는 limit을 전달하지 않아 기존 조회 의미를 유지한다.

- [ ] **Step 4: RED 테스트 재실행 후 최소 query 구현 확인**

다시 같은 targeted test를 실행하고, query API 컴파일 실패가 있으면 Exposed v1의 `select(Appointments.id).limit(limit)` 형태에 맞춰 수정한다. SQL이 101개 이상을 materialize하지 않는지 테스트 fixture에서 101개 ID를 넣고 반환 count가 101인지 확인한다.

## 3. Task 2 — core service의 fail-closed callback과 bounded all-or-nothing 구현

**파일:** `ClosureRescheduleService.kt`, `ClosureRescheduleServiceTest.kt`

- [ ] **Step 1: fail-closed 및 limit RED 테스트 추가**

다음 테스트를 기존 fixture에 추가한다.

```kotlin
assertFailsWith<IllegalArgumentException> {
    service(writer).processClosureReschedule(scope(clinicId), MONDAY, searchDays = 0)
}
assertFailsWith<IllegalArgumentException> {
    service(writer).processClosureReschedule(scope(clinicId), MONDAY, searchDays = -1)
}
assertFailsWith<IllegalArgumentException> {
    service(writer).processClosureReschedule(scope(clinicId), MONDAY, searchDays = 31)
}
```

4-인자 lambda만 구현한 writer로 process를 호출하는 테스트는 `UnsupportedOperationException`을 기대하고, transaction 밖에서 `Appointments.status == CONFIRMED`, history/candidate count가 0인지 확인한다. 101 affected 예약 fixture는 동일 clinic/date에 서로 다른 start/end를 넣고 preflight가 mutation 전에 실패하는지 확인한다. fake slot service가 날짜마다 2,001개를 반환하도록 하여 후보 상한 초과 시 status/history/candidate가 모두 rollback되는 테스트도 추가한다.

- [ ] **Step 2: limit RED 실행**

```bash
./gradlew :appointment-core:test --no-daemon --console=plain \
  --tests 'io.bluetape4k.clinic.appointment.service.ClosureRescheduleServiceTest'
```

예상 결과: 새 callback/limit 동작이 없어 테스트가 실패한다. `searchDays` 검증 실패가 transaction 내부 SQL 오류가 아닌 명시적 `IllegalArgumentException`인지 읽는다.

- [ ] **Step 3: 최소 service 구현**

service 경계에서 먼저 `require(searchDays in 1..MAX_SEARCH_DAYS)`를 수행하고, 별도 read transaction에서 clinic scope를 확인한 뒤 `countActiveByClinicAndDate(..., limit = MAX_AFFECTED_APPOINTMENTS + 1)`를 실행한다. 반환값이 101이면 즉시 실패시킨다. 이후 write transaction 안에서 `findActiveByClinicAndDate(..., limit = MAX_AFFECTED_APPOINTMENTS + 1)`를 다시 조회하고 100건 초과를 mutation 전에 거부한다.

callback port는 다음 형태로 추가한다.

```kotlin
fun statusChanged(
    scope: TenantClinicScope,
    appointment: AppointmentRecord,
    fromState: AppointmentState,
    toState: AppointmentState,
    commandContext: AppointmentCommandContext,
) = throw UnsupportedOperationException("Appointment status event writer is not configured")
```

각 appointment에 대해 기존 version으로 update한 뒤 canonical row를 재조회하고, `updated.version == appointment.version + 1`, `updated.status == PENDING_RESCHEDULE`를 `check`로 검증한다. 상태 이력과 callback은 canonical row 및 `fromState`를 사용해 같은 transaction에서 호출한다. 후보 누적 counter가 2,000을 초과하기 전에 예외를 던지고 일부 후보를 반환하지 않는다. companion constants는 `MAX_SEARCH_DAYS = 30`, `MAX_AFFECTED_APPOINTMENTS = 100`, `MAX_TOTAL_CANDIDATES = 2_000`, `LEGACY_CLOSURE_CORRELATION_ID`로 둔다.

기존 3-인자 호출을 보존하기 위해 새 `commandContext`는 마지막 default 인자로 추가하고 legacy overload는 고정 correlation root를 사용한다. SSE method와 constants 사용은 변경하지 않는다.

- [ ] **Step 4: GREEN core targeted test**

```bash
./gradlew :appointment-core:test --no-daemon --console=plain \
  --tests 'io.bluetape4k.clinic.appointment.service.ClosureRescheduleServiceTest'
```

성공 callback, fail-closed rollback, searchDays 0/음수/31, affected 101, candidate 2,001, optimistic conflict가 모두 PASS여야 한다.

## 4. Task 3 — messaging canonical status outbox writer

**파일:** `AppointmentOutboxWriter.kt`, `AppointmentOutboxWriterTest.kt`

- [ ] **Step 1: `STATUS_CHANGED` RED 테스트 작성 및 실행**

기존 appointment fixture를 canonical pending row로 갱신한 뒤 다음을 검증한다.

```kotlin
val appointment = repository.findByIdAndScope(appointmentId, scope)!!.copy(
    status = AppointmentState.PENDING_RESCHEDULE,
    version = 1L,
)
writer.statusChanged(
    scope, appointment, AppointmentState.CONFIRMED,
    AppointmentMessagingContext.from(AppointmentCommandContext.root("closure-1")),
)
```

outbox row의 `eventType == "AppointmentStatusChanged"`, `aggregateId == "924"`, pending status, partition key, correlation/causation을 확인하고 payload JSON에 `version:1`, `fromState:CONFIRMED`, `toState:PENDING_RESCHEDULE`가 있는지 확인한다. stale version/status 또는 다른 clinic record를 전달하는 테스트는 `IllegalArgumentException`과 outbox row 0을 기대한다.

```bash
./gradlew :appointment-messaging:test --no-daemon --console=plain \
  --tests 'io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriterTest'
```

예상 RED: 현재 writer가 caller record를 그대로 쓰므로 canonical mismatch 테스트가 실패한다.

- [ ] **Step 2: canonical 검증 구현**

`statusChanged`에서 `appointment.requireId()` 후 `appointmentRepository.findByIdAndScope(id, scope)`를 한 번만 호출한다. canonical row가 없거나 clinic이 다르면 거부하고, `canonical.version == appointment.version`, `canonical.status == appointment.status`, `fromState != canonical.status`를 검증한다. payload는 caller record가 아니라 canonical row로 생성한다. 기존 created/cancelled/rescheduled 경로의 `proveScope` 동작은 변경하지 않는다.

- [ ] **Step 3: GREEN messaging test**

동일 targeted test를 재실행해 정상 payload, scope mismatch, version/status mismatch, transaction rollback을 확인한다.

## 5. Task 4 — API composite와 REST context wiring

**파일:** `ServiceConfig.kt`, `RescheduleController.kt`, `RescheduleControllerTest.kt`, `RescheduleControllerPrivacyTest.kt`

- [ ] **Step 1: API RED 테스트 작성**

closure 요청에 `X-Correlation-Id: closure-api-1`을 넣고 응답 후 transaction에서 appointment status/version과 scheduling outbox를 조회한다. event type, payload from/to/version, correlation/causation이 일치해야 한다. controller privacy test mock은 `processClosureReschedule(scope, date, searchDays, commandContext)` 호출을 verify하도록 바꾼다.

```kotlin
restClient.post()
    .uri("/api/{tenant}/appointments/{id}/reschedule/closure?clinicId={clinic}&closureDate={date}", tenant, id, clinic, MONDAY)
    .header("X-Correlation-Id", "closure-api-1")
    .retrieve()
    .toBodilessEntity()
```

`searchDays=0` 및 `31` 요청은 400이고 `Appointments.status`가 CONFIRMED로 남는지 확인한다. writer가 예외를 던지는 atomicity test는 status/history/candidates/outbox가 모두 rollback되는지 확인한다.

- [ ] **Step 2: RED 실행**

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.RescheduleControllerTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.RescheduleControllerPrivacyTest'
```

예상 RED: controller가 request context를 전달하지 않고 ServiceConfig callback이 status event를 위임하지 않는다.

- [ ] **Step 3: 최소 wiring 구현**

anonymous `AppointmentRescheduleNotificationWriter.statusChanged`에서 먼저 `appointmentNotificationWriter.statusChanged`를 호출하고, 이어 `appointmentOutboxWriter.statusChanged(scope, appointment, fromState, AppointmentMessagingContext.from(commandContext))`를 호출한다. REST controller에 `HttpServletRequest`를 추가하고 `AppointmentCommandContext.root(CorrelationIdFilter.requireCorrelationId(request))`를 전달한다. 기존 rescheduled overload와 SSE controller signature는 유지한다.

- [ ] **Step 4: GREEN API targeted test**

위 API targeted command를 재실행하고 status/version, outbox row, payload, context, invalid searchDays mutation 부재를 읽는다.

## 6. Task 5 — 통합 원자성 및 성능 smoke

- [ ] **Step 1: callback/outbox failure RED fixture**

core recorder가 callback에서 `AppointmentMessagingContractException`을 던지도록 하고, process 호출 후 transaction 밖에서 appointment status, version, history, candidates가 원복되는 테스트를 작성한다. API test는 동일 transaction 경계에서 outbox insert failure를 주입할 수 있을 때 같은 assertion을 유지한다.

- [ ] **Step 2: 성능 smoke 실행**

동일한 로컬 TestDB dialect에서 affected 100건/searchDays 30을 준비하고 2회 warm-up 후 10회를 순차 실행한다. 각 실행의 preflight 반환 count, SQL count, transaction duration, lock-wait, 결과 row를 기록한다. 나머지 8회의 p95가 10초 이하, preflight row가 101 이하, lock-wait 0이면 PASS다. 후보 2,001건 rollback은 같은 조건에서 3회 실행해 mutation row 0과 rollback 시간이 성공 p95의 2배 이하인지 확인한다. 측정은 별도 프로세스/worker와 병렬로 실행하지 않는다.

- [ ] **Step 3: smoke 결과 기록**

실측값과 command를 `docs/lessons/2026-08-09-issue-17-closure-status-event.md`의 검증 표에 기록한다. 임계치 미달이면 구현을 되돌아가 query/candidate fan-out을 줄이고 targeted test와 smoke를 처음부터 재실행한다.

## 7. Task 6 — README/runbook과 lesson

- [ ] **Step 1: 문서 RED 점검**

`rg`로 closure `PENDING_RESCHEDULE`가 여전히 “제외”로만 기술된 위치를 찾고, SSE status event를 이번 PR이 보장한다고 오인하게 만드는 문구가 없는지 확인한다.

- [ ] **Step 2: 문서 수정**

README와 `docs/runbooks/appointment-messaging-operations.md`에 다음 계약을 반영한다.

1. legacy 동기 closure endpoint는 `PENDING_RESCHEDULE` `STATUS_CHANGED` outbox를 기록한다.
2. `searchDays 1..30`, affected 최대 100, 후보 전체 최대 2,000, preflight/write `LIMIT 101`을 넘으면 mutation 없이 실패한다.
3. 상태·history·candidate·outbox를 bounded read-only query로 대조하고 event ID는 삭제/수정하지 않는다.
4. SSE batch lifecycle/status event와 commitment-v2는 별도 후속 작업이며 이번 PR의 DoD가 아니다.

- [ ] **Step 3: lesson 작성**

lesson에는 context, 결정(fail-closed callback/canonical row/bounded preflight), 발견된 성능 P1과 수정, 검증 command/측정값, correlation provenance 후속 작업을 한국어로 기록한다.

## 8. Task 7 — 최종 검증과 PR 준비

- [ ] **Step 1: 전체 affected module 검증**

순차 실행:

```bash
./gradlew :appointment-core:test --no-daemon --console=plain
./gradlew :appointment-messaging:test --no-daemon --console=plain
./gradlew :appointment-api:test --no-daemon --console=plain
git diff --check
```

실패 시 raw output의 최초 원인을 기준으로 해당 task로 돌아가 RED/GREEN부터 재검증한다. TestDB/실제 DB 검사는 module 간 병렬 실행하지 않는다.

- [ ] **Step 2: final diff/review**

설계서와 계획의 각 수용 기준을 현재 diff 및 테스트 결과와 대조한다. six-lens review에서 P0/P1이 나오면 PR 생성을 중단하고 해당 task·테스트·review를 다시 수행한다. SSE 미변경과 commitment-v2 제외가 diff에 실제로 보이는지 확인한다.

- [ ] **Step 3: Lore commit**

변경 목적과 검증을 담은 한국어 commit message를 사용한다.

```text
동기 closure 상태 이벤트 범위와 bounded 계약을 확정한다

Constraint: SSE lifecycle과 commitment-v2는 별도 운영 계약으로 범위를 제한해야 한다.
Rejected: 무제한 affected/candidate 조회와 callback no-op은 원자성과 운영 안전성을 훼손하므로 채택하지 않는다.
Confidence: high
Scope-risk: moderate
Directive: 다음 status event adapter도 canonical row와 bounded preflight 계약을 유지한다.
Tested: core/messaging/api targeted 및 affected-module tests, git diff --check, 성능 smoke.
Not-tested: 실제 broker/Schema Registry/SLO 및 SSE lifecycle.
```

- [ ] **Step 4: PR readiness**

Issue #17의 milestone/labels/assignee를 읽어 PR metadata를 맞추고, 한국어 PR body의 마지막 `## DoD Status`에 동기 closure 포함, SSE/commitment-v2 제외, 테스트/성능/운영 미검증 항목을 기록한다. exact head SHA와 CI readiness를 확인한 뒤 merge approval을 요청한다. merge는 별도 fresh approval 전에는 실행하지 않는다.

## 9. 계획 자체 검토

- **명세 커버리지:** callback fail-closed(2, 3), canonical row(3), REST context(5), affected/search/candidate bounds(2, 3, 6), atomicity(2, 4, 5), docs/DoD(7), SSE 제외(1, 7, 8)를 모두 작업으로 연결했다.
- **placeholder 검사:** 미완성 표식이나 비어 있는 단계를 사용하지 않았고, 모든 구현 단계에 파일·메서드·검증 명령·예상 결과를 적었다.
- **타입 일관성:** `statusChanged(scope, appointment, fromState, toState, commandContext)`와 `processClosureReschedule(..., commandContext)`를 core, API, test 단계에서 동일하게 사용한다.
- **범위 누수 방지:** SSE controller/worker, commitment-v2, broker/registry/SLO는 구현 파일·테스트·DoD에서 후속/N/A로 명시했다.
