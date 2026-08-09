# Issue #17 closure `PENDING_RESCHEDULE` 상태 이벤트 구현 계획

> 승인된 설계의 구현 순서다. 각 단계는 RED 테스트를 먼저 만들고 최소 구현으로 GREEN을 만든다. 모든 범위·권한·성능 계약은 현재 Issue #17의 legacy 동기 closure endpoint에만 적용한다.

**목표:** 동기 closure 재배정이 `PENDING_RESCHEDULE` 상태 전이, 상태 이력, 후보, `STATUS_CHANGED` durable outbox intent를 한 caller transaction에서 원자적으로 기록한다.

**제외:** `streamClosureReschedule` SSE lifecycle/status event, commitment-v2, broker/Schema Registry 실제 연동, 배포 SLO. 이 항목은 Issue #17의 별도 follow-up으로 등록하고 이번 PR에서 닫지 않는다.

**공통 계약:** `appointment-core`는 messaging을 의존하지 않는다. 재배정 알림 port와 상태 이벤트 intent port를 분리한다. 상태 이벤트 port는 생성자 필수 의존성으로 구성하며 기본 no-op 또는 런타임 `UnsupportedOperationException`을 제공하지 않는다.

## 1. 변경 파일과 소유권

### 구현

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
  - `findActiveByClinicAndDate`에 nullable limit을 추가하고 bounded ID probe `probeActiveIdsByClinicAndDate`를 제공한다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentCommandContext.kt`
  - `httpRoot(correlationId)`를 추가해 client correlation과 server causation을 분리한다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleService.kt`
  - `AppointmentStatusEventWriter` 필수 port, `commandContext`, 1..30 검증, affected 100/slot calculation 3,000/candidate 2,000 bounds를 구현한다.
  - 슬롯 후보를 write transaction 밖에서 계산·캐시하고, write 직전에 ID/version/status snapshot을 재검증한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
  - 알림 writer와 `AppointmentOutboxWriter`를 `AppointmentStatusEventWriter` adapter로 조합한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleController.kt`
  - authenticated `SchedulingUserPrincipal`의 non-empty `allowedClinicIds`에 query clinic이 포함되는지 직접 검증하고 `httpRoot`를 전달한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
  - `/api/{tenantCode}/appointments/*/reschedule/closure` exact matcher를 generic POST matcher 앞에 둔다. matcher는 query `clinicId`와 principal allow-list를 검사한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantClinicAccessChecker.kt`
  - tenant ownership와 principal clinic membership 검사를 재사용 가능한 `verifyClinicForPrincipal`로 묶는다.
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriter.kt`
  - `toState`를 명시 인자로 받고 `AppointmentStateHistoryRepository`를 통해 canonical appointment 및 최신 history의 from/to를 검증한다.

### 테스트

- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentCommandContextTest.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleServiceTest.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleServicePerformanceTest.kt`
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriterTest.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleControllerTest.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleControllerPrivacyTest.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/RescheduleClosureSecurityIntegrationTest.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentMessagingFailureTestConfiguration.kt`
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt`의
  `확정은 상태 이력과 확정 및 두 리마인더를 같은 transaction에 기록한다` 회귀 method

### 문서와 운영 후속

- `appointment-api/README.ko.md` — closure endpoint의 중간 상태 outbox와 bounds를 명시한다.
- `docs/runbooks/appointment-messaging-operations.md` — legacy closure 대조 절차와 SSE/commitment-v2 제외를 명시한다.
- `docs/lessons/2026-08-09-issue-17-closure-status-event.md` — 결정, 실패 주입, 성능/lock evidence를 기록한다.
- GitHub Issue #17에 SSE `PENDING_RESCHEDULE` lifecycle follow-up 체크 항목과 owner를 추가한다. 이번 PR은 Issue #17을 자동 종료하지 않는다.

## 2. Task 1 — RED: port, context, bounded query 계약

1. `AppointmentCommandContextTest`에 다음을 추가한다.

   - `httpRoot("client-41")`의 `correlationId.value == "client-41"`.
   - `causationId.value`가 `http-command-`로 시작하고 correlation과 다름.
   - invalid/blank client correlation은 기존 metadata validation으로 거절.

2. `ClosureRescheduleServiceTest`에 필수 `AppointmentStatusEventWriter` recorder를 만들고 다음을 검증한다.

   - `scope`가 요청 tenant/clinic과 동일하다.
   - `appointment.version == 이전 version + 1`.
   - `fromState == 원래 상태`, `toState == PENDING_RESCHEDULE`.
   - `commandContext.correlationId.value`는 client 값이고 `causationId.value`는 server 값이다.
   - writer가 없는 생성 fixture는 컴파일되지 않도록 모든 fixture를 명시적 no-op status writer로 바꾼다.

3. `AppointmentRepository`에 `probeActiveIdsByClinicAndDate(scope, date, activeStatuses, limit)`를 추가한다. method 이름은 정확한 count가 아닌 bounded ID probe 의미를 표현해야 한다. `limit`은 `limit.requireInRange(1, MAX_AFFECTED_APPOINTMENTS + 1, "limit")`로 검증한다.

4. RED 실행:

   ```bash
   ./gradlew :appointment-core:test --no-daemon --console=plain \
     --tests 'io.bluetape4k.clinic.appointment.service.AppointmentCommandContextTest' \
     --tests 'io.bluetape4k.clinic.appointment.service.ClosureRescheduleServiceTest'
   ```

   기대 결과는 새 context factory, port signature, bounded probe가 없어 컴파일 또는 assertion이 실패하는 것이다.

## 3. Task 2 — GREEN: core closure two-phase transaction

1. `ClosureRescheduleService`의 생성자에 다음 port를 필수로 추가한다.

   ```kotlin
   fun interface AppointmentStatusEventWriter {
       fun statusChanged(
           scope: TenantClinicScope,
           appointment: AppointmentRecord,
           fromState: AppointmentState,
           toState: AppointmentState,
           commandContext: AppointmentCommandContext,
       )
   }
   ```

   기존 `AppointmentRescheduleNotificationWriter`는 rescheduled 알림만 유지한다. `statusChanged` capability를 default method로 합치지 않는다.

2. `processClosureReschedule(scope, closureDate, searchDays, commandContext)`에서 `searchDays.requireInRange(1, MAX_SEARCH_DAYS, "searchDays")`를 transaction 진입 전에 실행한다. closure clinic ownership을 한 번 확인하고 `probeActiveIdsByClinicAndDate(..., limit = 101)`로 preflight한다. 결과가 101이면 첫 mutation 전에 `IllegalArgumentException`을 던진다.

3. preflight 결과를 snapshot으로 보관하고 write transaction 밖에서 각 `(scope, doctorId, treatmentTypeId, candidateDate)` key를 한 번만 `slotCalculationService.findAvailableSlots`로 계산한다. call counter를 테스트에서 노출할 수 있도록 계산 함수를 constructor에 주입하거나 recorder fake를 사용한다. `affectedCount * searchDays > MAX_SLOT_CALCULATIONS (3_000)`이면 계산과 mutation 모두 실행하지 않는다. 모든 후보를 materialize한 뒤 총합이 `MAX_TOTAL_CANDIDATES (2_000)`를 넘으면 write transaction에 진입하지 않는다.

4. write transaction에서 동일 조건 `findActiveByClinicAndDate(..., limit = 101)`를 재조회한다. ID 집합, version, status가 preflight snapshot과 다르면 mutation 없이 optimistic-concurrency 오류를 던진다. 일치할 때만 각 row를 CAS update하고, canonical reread → history save → `statusEventWriter.statusChanged` → precomputed candidate insert 순서를 지킨다. callback/DB/codec 예외는 상태·history·candidate 전체 rollback이다.

5. `ClosureRescheduleServiceTest`에 다음 RED→GREEN 행위를 고정한다.

   - `searchDays` 0, -1, 31은 `IllegalArgumentException`이고 DB mutation 0.
   - affected 101은 preflight row 101에서 종료하고 status/history/candidate 0.
   - preflight와 write 사이 version drift는 mutation 0.
   - 동일 slot key가 여러 appointment에 걸쳐 있어도 fake calculation call은 key당 1회.
   - `affected * searchDays == 3_001`은 calculation call 0, mutation 0.
   - candidate 2,001은 mutation 0.
   - status writer 예외는 status/version/history/candidate를 모두 원복한다.
   - 기존 `AppointmentService` caller가 새 `toState` 인자와 함께 정상 동작한다.

   service의 `KLogging` 구조화 code도 검증한다. `affected_limit_rejected`,
   `slot_calculation_limit_rejected`, `candidate_limit_rejected`, `snapshot_conflict`,
   `rollback`, `committed`만 허용하며 log에는 tenant/clinic/appointment/patient 값을
   기록하지 않는다. affected/candidate count, searchDays, precompute/write duration만
   남긴다.

6. GREEN 실행:

   ```bash
   ./gradlew :appointment-core:test --no-daemon --console=plain \
     --tests 'io.bluetape4k.clinic.appointment.service.ClosureRescheduleServiceTest' \
     --tests 'io.bluetape4k.clinic.appointment.service.AppointmentCommandContextTest'
   ```

## 4. Task 3 — GREEN: messaging canonical writer와 기존 caller 회귀

1. `AppointmentOutboxWriter.statusChanged` signature를
   `(scope, appointment, fromState, toState, context, reasonCode)`로 바꾼다. `AppointmentService.updateStatus`와 closure adapter를 포함한 모든 caller를 컴파일 검색으로 갱신한다.

2. `DefaultAppointmentOutboxWriter.statusChanged`에서 caller record의 ID/clinic/version/status와 scope를 검증한 뒤 `findByIdAndScope` canonical row를 읽는다. `canonical.version == appointment.version`, `canonical.status == toState`, `fromState != toState`를 확인한다. `AppointmentStateHistoryRepository.findByAppointmentId(id).firstOrNull()`의 from/to가 입력 from/to와 다르면 거부한다. payload는 canonical row와 명시된 from/to로만 생성한다.

3. `AppointmentOutboxWriterTest` fixture에 상태 history table/record를 준비한다. 정상 event의 version/from/to/correlation/causation을 확인하고, stale version, forged toState, forged fromState, cross-clinic row는 `IllegalArgumentException`과 outbox 0을 기대한다.

4. `AppointmentNotificationAtomicityTest.확정은 상태 이력과 확정 및 두 리마인더를 같은 transaction에 기록한다`는
   기존 `AppointmentService.updateStatus` caller가 `AppointmentOutboxWriter.statusChanged(..., fromState, toState, ...)`를
   한 번 호출하는지 확인해 명시적인 `toState` 전달 회귀를 고정한다.

5. 실행:

   ```bash
   ./gradlew :appointment-messaging:test --no-daemon --console=plain \
     --tests 'io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriterTest'
   ./gradlew :appointment-api:test --no-daemon --console=plain \
     --tests 'io.bluetape4k.clinic.appointment.api.service.AppointmentServiceStatusEventRegressionTest'
   ```

## 5. Task 4 — GREEN: API adapter, exact clinic authorization, lineage

1. `ServiceConfig.closureRescheduleService`에서 `AppointmentStatusEventWriter` anonymous adapter를 구성한다. `statusChanged` 호출은 `appointmentNotificationWriter.statusChanged` 후 `appointmentOutboxWriter.statusChanged(..., fromState, toState, AppointmentMessagingContext.from(commandContext))` 순서다. 모든 closure service constructor call과 `AppointmentNotificationAtomicityTest` fixture를 갱신한다.

2. `RescheduleController.processClosureReschedule`에 `HttpServletRequest`와 `@AuthenticationPrincipal SchedulingUserPrincipal`을 받는다. `TenantClinicAccessChecker.verifyClinicForPrincipal(tenantCode, clinicId, principal)`은 tenant DB ownership, role, non-empty allow-list, exact clinic membership을 모두 확인한다. 다른 clinic 또는 empty allow-list는 403이며 service 호출은 0회다. context는 `AppointmentCommandContext.httpRoot(CorrelationIdFilter.requireCorrelationId(request))`로 만든다.

3. `SecurityConfig`에 closure exact matcher를 generic `POST /api/{tenantCode}/**` 앞에 둔다. matcher는 query parameter `clinicId`를 양수로 파싱하고 `SchedulingUserPrincipal.allowedClinicIds` membership을 확인한다. `clinicPolicyAccess`의 path-variable 전용 구현을 재사용하지 않는다.

4. `RescheduleControllerTest`와 `RescheduleControllerPrivacyTest`에 request/principal 인자를 반영하고, valid clinic에서는 service에 server causation context가 전달되는지 확인한다.

5. `RescheduleClosureSecurityIntegrationTest`는 authenticated ADMIN/STAFF principal에 대해 다음 matrix를 실행한다.

   | case | expected | service call |
   |---|---:|---:|
   | allowed clinic in non-empty allow-list | 200/handler result | 1 |
   | cross-clinic in same tenant | 403 | 0 |
   | empty allow-list | 403 | 0 |
   | another tenant | 403 | 0 |
   | non ADMIN/STAFF role | 403 | 0 |

6. 실행:

   ```bash
   ./gradlew :appointment-api:test --no-daemon --console=plain \
     --tests 'io.bluetape4k.clinic.appointment.api.controller.RescheduleControllerTest' \
     --tests 'io.bluetape4k.clinic.appointment.api.controller.RescheduleControllerPrivacyTest' \
     --tests 'io.bluetape4k.clinic.appointment.api.security.RescheduleClosureSecurityIntegrationTest'
   ```

## 6. Task 5 — API composite rollback 주입과 성능/lock harness

1. `AppointmentMessagingFailureTestConfiguration.kt`에 `@TestConfiguration` bean을 둔다. closure integration test profile에서 `AppointmentOutboxWriter`의 `statusChanged`만 `AppointmentMessagingContractException`을 던지는 `FailingAppointmentOutboxWriter`로 교체한다. API 테스트 소유 method는 `RescheduleControllerTest.closureOutboxFailureReturns503AndRollsBack`이고, direct core transaction 회귀 method는 `AppointmentNotificationAtomicityTest.closureStatusWriterFailureRollsBackStateHistoryAndCandidates`다.

2. 다음 두 명령을 실행해 경계를 각각 증명한다.

   ```bash
   ./gradlew :appointment-api:test --no-daemon --console=plain \
     --tests '*RescheduleControllerTest.closureOutboxFailureReturns503AndRollsBack'
   ./gradlew :appointment-api:test --no-daemon --console=plain \
     --tests '*AppointmentNotificationAtomicityTest.closureStatusWriterFailureRollsBackStateHistoryAndCandidates'
   ```

   API test는 HTTP 503, appointment status/version 원복, history/candidate/outbox row 0을 확인한다. direct test는 HTTP 없이 동일한 transaction rollback을 확인한다. 주입 bean이 없거나 test context가 기본 writer를 사용하면 테스트를 통과시키지 않고 configuration을 고친다.

3. `ClosureRescheduleServicePerformanceTest.kt`에는 다음 harness를 구현한다. 슬롯 계산기는
   production `SlotCalculationService`를 직접 호출하지 않고 주입 가능한 함수 counter로 감싼다.

   - 100 affected appointment와 searchDays 30 fixture.
   - key별 slot query call counter와 Exposed statement counter.
   - 2 warm-up + 10 measured run, 측정 10회의 p95 <= 10s.
   - key당 slot calculation 1회, preflight returned rows <= 101, write-phase SQL statement count <= 2,700. 이 수치는 bounded requery 1회 + affected당 canonical/history/status 검증 최대 5회 × 100 + candidate insert 최대 2,000 + outbox 여유분으로 고정한다.
   - `CountDownLatch` 두 transaction: 한 thread는 precompute 중이고 다른 thread는 같은 clinic 다른 appointment를 갱신한다. precompute 구간에 write lock이 잡히지 않고 mutation lock duration p95 <= 2s를 검증한다.
   - candidate 2,001 path 3회 반복에서 mutation row 0.

   `ClosureRescheduleService`의 저카디널리티 log code와 precompute/write duration을
   capture해 운영 lesson 표에 함께 기록한다. Micrometer를 연결하는 API adapter가
   존재할 때는 동일 code의 counter/timer를 검증하고 clinic·appointment 식별자 tag는
   사용하지 않는다.

4. 실행 명령은 다음 하나로 고정한다.

   ```bash
   ./gradlew :appointment-core:test --no-daemon --console=plain \
     --tests 'io.bluetape4k.clinic.appointment.service.ClosureRescheduleServicePerformanceTest'
   ```

   측정값과 SQL/lock counter를 lesson 표에 저장한다. 실제 PostgreSQL lock-wait/SLO는 이 로컬 harness가 증명하지 않으며 운영 검증 항목으로 남긴다.

## 7. Task 6 — 문서와 follow-up

1. `appointment-api/README.ko.md`의 endpoint 표와 reschedule 설명에 legacy 동기 closure가 `PENDING_RESCHEDULE` `STATUS_CHANGED` outbox를 기록하고 `searchDays 1..30`, affected 100, slot calculation 3,000, candidate 2,000, preflight/write `LIMIT 101`을 넘으면 mutation 없이 실패한다고 적는다.
2. `docs/runbooks/appointment-messaging-operations.md`의 현재 “closure 전이가 포함되지 않는다” 문장을 “legacy 동기 closure는 포함되며 SSE batch와 commitment-v2는 포함되지 않는다”로 교체한다. 대조 query는 row/event ID를 수정하지 않는 bounded read-only 절차로 적는다.
3. `docs/lessons/2026-08-09-issue-17-closure-status-event.md`에 설계 선택, 권한/lineage 검증, two-phase snapshot trade-off, rollback 주입, performance/lock 결과를 한국어로 기록한다.
4. Issue #17에 SSE status/lifecycle follow-up의 owner와 acceptance criteria를 추가하고, 이 PR body에는 `Closes #17`을 쓰지 않는다.

## 8. Task 7 — 최종 검증과 PR readiness

1. 순차 검증:

   ```bash
   ./gradlew :appointment-core:test --no-daemon --console=plain
   ./gradlew :appointment-messaging:test --no-daemon --console=plain
   ./gradlew :appointment-api:test --no-daemon --console=plain
   git diff --check
   ```

2. 설계 acceptance와 현재 diff를 대조한다. six-lens 재검토 결과 `P0=0/P1=0`이 아니면 PR을 만들지 않는다. 특히 SecurityConfig exact matcher, `AppointmentService` 기존 caller, API failure injection, performance harness 파일과 명령이 실제 diff에 있어야 한다.
3. flow evidence에 targeted/full tests, security matrix, SQL/lock 측정값, unchecked production checks를 기록한다. 실제 broker/registry/SLO와 SSE는 `PENDING`으로 남긴다.
4. Lore commit을 한국어로 만든다.

   ```text
   동기 closure 상태 이벤트를 canonical transaction 경계로 보강한다

   Constraint: legacy closure만 포함하고 SSE lifecycle과 commitment-v2는 별도 운영 계약으로 유지해야 한다.
   Rejected: notification port에 상태 이벤트 capability를 숨기는 기본 메서드와 write transaction 내부의 무제한 slot fan-out은 substitutability와 lock 안전성을 해치므로 채택하지 않는다.
   Confidence: high
   Scope-risk: moderate
   Directive: 다음 status event adapter도 exact clinic scope, server causation, canonical history 검증을 유지한다.
   Tested: core/messaging/api targeted 및 full tests, security matrix, performance harness, git diff --check.
   Not-tested: 실제 broker/Schema Registry/SLO와 SSE lifecycle.
   ```

5. PR body는 한국어로 작성하고 마지막 section을 `## DoD Status`로 둔다. Issue/PR metadata parity와 CI readiness를 확인한 뒤 exact head SHA를 보고한다. merge는 별도 fresh approval 이후에만 수행한다.

## 9. 계획 자체 검토

- 모든 P1 review finding에 구현 파일, 테스트 method, 실행 명령을 연결했다.
- `countActiveByClinicAndDate`처럼 정확한 count를 암시하는 이름을 사용하지 않고 bounded ID probe semantics를 명시했다.
- caller value class는 `.value`로 비교하고 input 범위는 `requireInRange`를 사용한다.
- API 권한은 tenant ownership과 principal exact clinic membership을 모두 검증하며 empty allow-list를 허용하지 않는다.
- client correlation과 server causation의 provenance를 core test와 API test에서 분리한다.
- write transaction은 precomputed snapshot을 재검증한 뒤에만 mutation하며, SQL/lock budget을 executable harness로 고정한다.
