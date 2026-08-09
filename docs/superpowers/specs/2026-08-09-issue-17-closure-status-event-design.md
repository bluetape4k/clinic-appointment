# Issue #17 closure `PENDING_RESCHEDULE` 상태 이벤트 설계

## 목적

Issue #17의 legacy 예약 이벤트 stream에서 임시휴진으로 인한
`PENDING_RESCHEDULE` 상태 전이가 누락된 문제를 보강한다. 기존 create/status/cancel과
최종 reschedule 이벤트는 유지하고, 동기 closure 재배정 endpoint의 중간 상태를 동일한
transactional outbox 계약으로 전달한다.

## 현재 근거와 범위

- `ClosureRescheduleService.processClosureReschedule`는 예약 상태와 상태 이력을 하나의
  Exposed transaction에서 기록하지만 messaging callback을 호출하지 않는다.
- `streamClosureReschedule`는 예약별 transaction과 SSE lifecycle을 별도 운영 계약으로
  사용하며, 이번 변경에서는 status event wiring을 건드리지 않는다.
- `appointment-core`는 `appointment-messaging`을 의존하지 않는다. 재배정 알림 port와
  상태 이벤트 intent port를 분리해 core가 메시징 구현을 알지 못하게 한다.
- `ServiceConfig`는 두 port의 API adapter를 구성한다. 상태 변경 adapter는 알림 writer와
  durable outbox writer를 caller transaction 안에서 순서대로 호출한다.
- 현재 README와 운영 runbook은 closure의 중간 `PENDING_RESCHEDULE`를 명시적으로
  제외한다고 기록한다.

이번 변경은 legacy 동기 closure endpoint와 그 문서/검증을 다룬다. SSE batch stream의
status event·부분 진행 복구·취소 lifecycle, commitment-v2 전체 stream, 실제 broker·Schema
Registry·운영 SLO, notification provider의 새 사용자 동작은 범위에 포함하지 않는다.

## 대안 비교

### A. 별도 core 상태 이벤트 port 추가 (채택)

`AppointmentStatusEventWriter`를 `appointment-core`에 추가하고
`AppointmentRescheduleNotificationWriter`에는 재배정 알림 책임만 남긴다. closure service는
두 의존성을 생성 시점에 모두 요구하므로 상태 이벤트를 지원하지 않는 구현이 성공한 뒤
런타임에 실패하는 substitutability 위반을 허용하지 않는다. API adapter가 두 writer를
같은 caller transaction에서 호출하고, 상태 이벤트 writer는 durable outbox intent를
담당한다.

### B. 기존 notification port에 fail-closed `statusChanged` 확장

source-compatible 기본 메서드는 기존 lambda를 보존하지만, notification writer가 상태
이벤트 capability를 가진 것처럼 보이게 만든다. 구현체 누락을 생성 시점에 검출하지 못해
port 경계와 장애 원인이 불명확하므로 채택하지 않는다.

### C. transaction 이후 application event 발행

기존 Spring event 패턴과 유사하지만 상태 commit과 durable outbox intent가 분리된다.
outbox insert 실패 시 예약이 이미 `PENDING_RESCHEDULE`가 되어 Issue #17의 transactional
outbox 계약을 위반하므로 채택하지 않는다.

## 선택 설계

### Core 계약

새 `AppointmentStatusEventWriter`에 다음 의미의 필수 메서드를 둔다.

- 입력: `TenantClinicScope`, 갱신된 `AppointmentRecord`, `fromState`, `toState`,
  `AppointmentCommandContext`
- 기본 동작은 두지 않는다. `ClosureRescheduleService`와 API adapter 생성자가 이 port를
  필수로 요구해 capability 누락을 애플리케이션 시작 시 드러낸다.
- `toState`는 현재 `PENDING_RESCHEDULE`이며, writer는
  `toState == appointment.status`, `fromState != toState`와 최신 상태 이력의
  `fromState/toState` 일치를 검증한다.
- callback의 `AppointmentRecord`는 service가 같은 transaction에서
  `findByIdAndScope`로 읽은 canonical row다. API/messaging 경계도 같은 scope와 ID로
  canonical row를 재확인하며 version/status가 다르면 예외를 발생시킨다.

동기 closure 경로는 optimistic update 직후 `findByIdAndScope`로 갱신 row를 읽는다. 기존
상태를 `fromState`로 보존하고 상태 이력과 상태 이벤트 writer를 같은 transaction에서
호출한다. 갱신 row의 `version`을 이벤트 version으로 사용하며 별도 version 인자를 중복해서
받지 않는다. writer는 canonical row와 최신 상태 이력을 다시 읽어 `fromState/toState`를
검증한다. callback 예외는 transaction 전체를 rollback시킨다.

동기 closure API는 service 경계에서 transaction에 진입하기 전에 `searchDays`를 `1..30`으로
검증하고, 조회된 affected appointment 수를 최대 100건으로 제한한다. 쓰기 transaction을
시작하기 전에 tenant/clinic과 legacy active 조건을 적용한 ID preflight query를
`LIMIT 101`로 실행하여 101번째 row가 보이면 즉시 거부한다. 실제 쓰기 transaction의
재조회도 반드시 동일 조건의 `LIMIT 101` query로 bounded하게 수행하고, 반환된 row가
100건을 초과하면 mutation 전에 거부한다. 이 재검증으로 preflight와 mutation 사이에
추가된 예약이 있어도 unbounded materialization 없이 all-or-nothing 계약을 유지한다.
상한을 넘으면 상태를 하나도 변경하지 않고 명시적 validation error를 반환한다.

후보 fan-out도 암묵적으로 무제한으로 두지 않는다. 먼저 write transaction 밖에서
`affectedAppointmentCount * searchDays`를 `MAX_SLOT_CALCULATIONS = 3_000` 이하로
검증하고, `(scope, doctorId, treatmentTypeId, candidateDate)` 키로 슬롯 계산 결과를
캐시한다. 후보 누적은 모든 날짜를 계산한 뒤 저장 전에 `MAX_TOTAL_CANDIDATES = 2_000`을
검증한다. 상한을 넘으면 mutation transaction에 진입하지 않는다. write transaction은
affected row를 `LIMIT 101`로 재조회해 preflight의 ID/version/status snapshot과 일치하는지
확인한 뒤 상태·이력·후보를 저장한다. snapshot이 바뀌면 mutation 없이 충돌 오류를
반환한다. 따라서 슬롯 계산은 장시간 DB write lock을 보유하지 않지만, 후보 가용성은
계산 시점 snapshot을 의미하며 stale snapshot을 허용하지 않는 후속 재검증이 필요한 경우
별도 운영 설계로 다룬다.

closure service는 `affected_limit_rejected`, `slot_calculation_limit_rejected`,
`candidate_limit_rejected`, `snapshot_conflict`, `rollback`, `committed`의 저카디널리티
구조화 log code를 기록한다. log에는 tenant, clinic, appointment ID, patient 정보와
원문 reason을 넣지 않고 affected count, candidate count, searchDays, precompute 및
write duration만 기록한다. 운영 adapter가 Micrometer를 제공하는 경우 같은 code를
counter와 transaction timer로 연결하되, 이번 PR은 broker/SLO alert threshold를 확정하지
않는다.

서비스의 `commandContext`는 마지막 선택 인자로 추가한다. API caller는 요청 correlation을
검증한 뒤 `AppointmentCommandContext.httpRoot(correlationId)`로 context를 만든다. 이때
client 값은 correlation에만 보존하고 causation은 `http-command-<UUID>`로 서버가 생성한다.
legacy 내부 호출자는 고정된 root correlation을 사용하여 기존 호출 시그니처를 유지한다.

### API wiring과 context 전달

`ServiceConfig`의 composite callback은 다음 순서로 동작한다.

1. 기존 `AppointmentNotificationWriter.statusChanged`를 호출한다. 현재 writer는
   `PENDING_RESCHEDULE` 대상 notification을 생성하지 않으므로 provider 동작은 변하지
   않는다.
2. `AppointmentOutboxWriter.statusChanged`는 같은 caller transaction에서 scope와 ID로
   canonical row와 최신 상태 이력을 다시 읽고 입력 record의 ID/clinic/version/status 및
   `fromState/toState`를 비교한 뒤 payload를 canonical row로 생성한다. `toState`는
   interface 인자로 명시해 caller가 payload 상태를 위조하지 못하게 한다.
   scope는 callback으로 전달받은 검증된 `TenantClinicScope`를 그대로 사용하고,
   `appointment.clinicId`는 canonical scope equality 검증에만 사용한다. context는
   `AppointmentMessagingContext.from(commandContext)`로 변환한다.

REST closure endpoint는 `HttpServletRequest`에서 correlation을 읽고 인증 principal의
`allowedClinicIds`에 query `clinicId`가 포함되는지 확인한 뒤 service에 전달한다. 빈
allow-list와 다른 clinic은 거부한다. `SecurityConfig`에는 closure 정확 경로용 matcher를
generic tenant POST rule보다 앞에 둬 동일한 clinic 정책을 적용한다. SSE endpoint의 worker
context 전달은 이번 범위에서 변경하지 않는다.

### 오류와 원자성

- optimistic update가 실패하면 상태 이력·callback·후보 생성을 실행하지 않고 기존
  concurrent-change 오류를 유지한다.
- 동기 process의 optimistic update 실패는 조용히 건너뛰지 않고 기존
  concurrent-change 예외로 실패시킨다. SSE 경로의 conflict 결과는 후속 설계에서
  별도로 고정한다.
- 갱신 row를 다시 읽지 못하면 transaction을 실패시켜 stale version 이벤트를 방지한다.
- outbox writer가 scope/codec/DB 오류를 던지면 상태 변경과 후보까지 rollback되어 API가
  성공 응답을 반환하지 않는다.
- 필수 상태 이벤트 writer bean이 없으면 애플리케이션이 시작되지 않으며, 구성된 writer가
  scope/canonical/history/codec/DB 오류를 던지면 동일하게 rollback된다.
- SSE batch stream의 callback 실패·progress 전송·terminal 상태는 기존 운영 계약을
  유지하며, 이번 PR의 DoD가 보장하지 않는다. 해당 stream을 status event까지 확장할
  때는 취소/부분 진행/structured error를 별도 설계하고 검토해야 한다.
- request correlation은 trace continuity용 metadata일 뿐 auth/audit/idempotency나
  causation의 권한 근거가 아니다. client header는 검증된 correlation으로만 사용하고
  HTTP command causation은 서버 생성 값으로 보존한다.

## 검증 계획

1. core RED 테스트로 동기 `PENDING_RESCHEDULE` callback의 scope, `from/to`, 갱신 version,
   correlation/서버 causation 전달을 고정하고, 필수 상태 writer wiring을 확인한다.
2. process 경로가 callback을 호출하고 optimistic concurrency failure를 전파하는지
   회귀 테스트한다. SSE 경로는 후속 설계 범위로 명시한다.
3. `searchDays`가 0/음수 또는 30 초과일 때와 affected 100건 초과가 `LIMIT 101`
   preflight 및 mutation 전 재검증으로 거부되는지 테스트한다. `affected * searchDays`가
   3,000을 넘거나 후보 누적이 2,000건을 초과하면 write transaction에 진입하지 않는지
   확인한다. 동일 슬롯 query cache가 중복 계산을 제거하는지도 확인한다.
4. messaging writer 테스트로 canonical row mismatch를 거부하고 `STATUS_CHANGED`
   payload의 appointment version, `fromState`, `toState`, context 및 pending row를
   확인한다.
5. API wiring/통합 테스트로 동기 closure 상태와 outbox row가 같은 transaction에서
   commit되고, 구성된 `AppointmentOutboxWriter` 실패 주입 시 HTTP 503과 함께
   상태·이력·후보·outbox가 모두 0건으로 rollback되는지 확인한다.
6. `ClosureRescheduleServicePerformanceTest`에 100건/30일 fixture, 슬롯 계산 call/cache
   counter, SQL statement counter를 두고 `./gradlew :appointment-core:test --tests
   '*ClosureRescheduleServicePerformanceTest'`로 실행한다. 2회 warm-up 뒤 10회 측정의
   나머지 8회 p95는 10초 이하, slot calculation은 cache key당 1회, preflight row는
   최대 101, write transaction SQL은 `MAX_WRITE_SQL_STATEMENTS = 2_700` 이하이어야
   한다( bounded requery 1회 + affected당 canonical/history/status 검증 최대 5회 × 100 +
   candidate insert 최대 2,000 + outbox 여유분). 두 transaction과 `CountDownLatch`를 이용한 competing writer 시나리오에서
   precompute 동안 write lock을 잡지 않고, mutation lock duration p95를 2초 이하로
   측정한다. 후보 2,001건 경로는 3회 실행해 mutation row 0을 확인한다. 이 값은 배포
   SLO가 아니라 bounded transaction 회귀를 감지하는 smoke threshold다.
7. 영향을 받은 Gradle module targeted test와 `git diff --check`를 실행하고 README/runbook
   문구가 실제 stream 범위와 일치하는지 확인한다.

## 수용 기준과 DoD

- 동기 closure endpoint가 `PENDING_RESCHEDULE` 상태 이벤트를 durable outbox에 기록한다.
- SSE batch stream status event 확장은 명시된 후속 작업으로 남고 이번 PR의 완료 조건으로
  오인되지 않는다.
- 기존 재배정 notification 호출자는 유지하고 상태 이벤트 writer는 필수 dependency로
  구성된다. 상태 이벤트를 지원하지 않는 실행 adapter는 애플리케이션 시작 단계에서
  검출된다.
- 이벤트는 canonical row의 version/status와 요청 command context를 보존한다.
- 상태·이력·후보·outbox의 transaction 원자성이 테스트로 증명된다.
- `searchDays <= 30`, affected `<= 100`, slot calculation `<= 3,000`, 후보 `<= 2,000`,
  write SQL `<= 2,700`
  bounded 계약과 성능 smoke 증거가 있다. precompute cache hit, 상태/history/candidate
  write SQL 예산, competing writer의 lock duration 관찰값을 기록한다.
- README와 운영 runbook은 closure 중간 상태가 포함되고 commitment-v2는 여전히 제외됨을
  명시한다.
- 관련 테스트가 통과하고 P0/P1 검토 blocker가 없다. 실제 broker/registry/SLO 검증은
  별도 운영 작업으로 남기며 Issue #17을 자동 종료하지 않는다.
