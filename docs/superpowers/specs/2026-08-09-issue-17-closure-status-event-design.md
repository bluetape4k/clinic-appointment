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
- `appointment-core`는 `appointment-messaging`을 의존하지 않는다. 현재
  `AppointmentRescheduleNotificationWriter`가 재배정 알림과 outbox를 연결하는
  dependency-neutral port다.
- `ServiceConfig`는 이 port의 API composite 구현에서 최종 `RESCHEDULED` outbox를
  이미 기록하므로 동일한 경계에 상태 변경 위임을 추가할 수 있다.
- 현재 README와 운영 runbook은 closure의 중간 `PENDING_RESCHEDULE`를 명시적으로
  제외한다고 기록한다.

이번 변경은 legacy 동기 closure endpoint와 그 문서/검증을 다룬다. SSE batch stream의
status event·부분 진행 복구·취소 lifecycle, commitment-v2 전체 stream, 실제 broker·Schema
Registry·운영 SLO, notification provider의 새 사용자 동작은 범위에 포함하지 않는다.

## 대안 비교

### A. 기존 callback에 fail-closed `statusChanged` 확장 (채택)

`AppointmentRescheduleNotificationWriter`에 기본 fail-closed 메서드를 추가한다. 기존
4-인자 함수형 람다 구현은 컴파일 호환성을 유지하지만 상태 이벤트를 지원하지 않는
구현이 실제 closure 명령을 호출하면 명시적 예외로 transaction을 rollback한다. API
composite만 이 메서드를 override하여 notification과 messaging writer를 호출한다. core가
messaging 모듈을 역참조하지 않으며 현재 재배정 callback과 같은 transaction 경계를
재사용한다.

### B. 별도 core 상태 이벤트 port 추가

의미상 분리는 명확하지만 service 생성자, Spring wiring, 테스트 fixture에 새로운 의존성을
추가한다. 기존 재배정 port와 호출 순서가 분리되어 두 callback의 transaction 계약을
동시에 유지해야 하므로 이번 단일 전이 보강에는 과하다.

### C. transaction 이후 application event 발행

기존 Spring event 패턴과 유사하지만 상태 commit과 durable outbox intent가 분리된다.
outbox insert 실패 시 예약이 이미 `PENDING_RESCHEDULE`가 되어 Issue #17의 transactional
outbox 계약을 위반하므로 채택하지 않는다.

## 선택 설계

### Core 계약

기존 port에 다음 의미의 기본 메서드를 추가한다.

- 입력: `TenantClinicScope`, 갱신된 `AppointmentRecord`, `fromState`, `toState`,
  `AppointmentCommandContext`
- 기본 동작: `UnsupportedOperationException`을 발생시키는 fail-closed. 기존 호출자와
  함수형 람다의 source compatibility는 유지하되, 미지원 adapter가 성공을 숨기지
  못하게 한다.
- `toState`는 현재 항상 `PENDING_RESCHEDULE`이며, callback은
  `toState == appointment.status`, `fromState != toState`를 검증한다.
- callback의 `AppointmentRecord`는 service가 같은 transaction에서
  `findByIdAndScope`로 읽은 canonical row다. API/messaging 경계도 같은 scope와 ID로
  canonical row를 재확인하며 version/status가 다르면 예외를 발생시킨다.

동기 closure 경로는 optimistic update 직후 `findByIdAndScope`로 갱신 row를 읽는다. 기존
상태를 `fromState`로 보존하고 상태 이력과 callback을 같은 transaction에서 호출한다.
갱신 row의 `version`을 이벤트 version으로 사용하며 별도 version 인자를 중복해서 받지
않는다. callback 예외는 transaction 전체를 rollback시킨다.

동기 closure API는 service 경계에서 transaction에 진입하기 전에 `searchDays`를 `1..30`으로
검증하고, 조회된 affected appointment 수를 최대 100건으로 제한한다. 쓰기 transaction을
시작하기 전에 tenant/clinic과 legacy active 조건을 적용한 ID preflight query를
`LIMIT 101`로 실행하여 101번째 row가 보이면 즉시 거부한다. 실제 쓰기 transaction의
재조회도 반드시 동일 조건의 `LIMIT 101` query로 bounded하게 수행하고, 반환된 row가
100건을 초과하면 mutation 전에 거부한다. 이 재검증으로 preflight와 mutation 사이에
추가된 예약이 있어도 unbounded materialization 없이 all-or-nothing 계약을 유지한다.
상한을 넘으면 상태를 하나도 변경하지 않고 명시적 validation error를 반환한다.

후보 fan-out도 암묵적으로 무제한으로 두지 않는다. 한 closure batch에서 저장할 수 있는
재배정 후보는 최대 2,000건으로 고정하고, 다음 후보가 상한을 넘으면 결과를 잘라 성공으로
위장하지 않고 transaction 전체를 실패시켜 상태·이력·후보를 모두 rollback한다. 이 검사는
각 날짜의 slot 목록을 저장하기 전에 수행하여 transaction 안의 후보 materialization과
DB insert 비용도 bounded하게 유지한다. SSE batch stream의 상한·부분 진행·취소 계약은
별도 후속 설계로 남긴다.

서비스의 `commandContext`는 마지막 선택 인자로 추가한다. API caller는 요청 correlation을
검증한 context를 전달하고, legacy 내부 호출자는 고정된 root correlation을 사용하여 기존
호출 시그니처를 유지한다.

### API wiring과 context 전달

`ServiceConfig`의 composite callback은 다음 순서로 동작한다.

1. 기존 `AppointmentNotificationWriter.statusChanged`를 호출한다. 현재 writer는
   `PENDING_RESCHEDULE` 대상 notification을 생성하지 않으므로 provider 동작은 변하지
   않는다.
2. `AppointmentOutboxWriter.statusChanged`는 같은 caller transaction에서 scope와 ID로
   canonical row를 다시 읽고 입력 record의 ID/clinic/version/status를 비교한 뒤
   payload를 canonical row로 생성한다.
   scope는 `TenantClinicScope(tenantGroupId, appointment.clinicId)`로 만들고 context는
   `AppointmentMessagingContext.from(commandContext)`로 변환한다.

REST closure endpoint는 `HttpServletRequest`에서 correlation을 읽어 service에 전달한다.
SSE endpoint의 worker context 전달은 이번 범위에서 변경하지 않는다.

### 오류와 원자성

- optimistic update가 실패하면 상태 이력·callback·후보 생성을 실행하지 않고 기존
  concurrent-change 오류를 유지한다.
- 동기 process의 optimistic update 실패는 조용히 건너뛰지 않고 기존
  concurrent-change 예외로 실패시킨다. SSE 경로의 conflict 결과는 후속 설계에서
  별도로 고정한다.
- 갱신 row를 다시 읽지 못하면 transaction을 실패시켜 stale version 이벤트를 방지한다.
- outbox writer가 scope/codec/DB 오류를 던지면 상태 변경과 후보까지 rollback되어 API가
  성공 응답을 반환하지 않는다.
- 지원하지 않는 callback의 fail-closed 예외도 동일하게 rollback된다.
- SSE batch stream의 callback 실패·progress 전송·terminal 상태는 기존 운영 계약을
  유지하며, 이번 PR의 DoD가 보장하지 않는다. 해당 stream을 status event까지 확장할
  때는 취소/부분 진행/structured error를 별도 설계하고 검토해야 한다.
- request correlation은 trace continuity용 metadata일 뿐 auth/audit/idempotency나
  causation의 권한 근거가 아니다. client header provenance 및 고정 legacy root
  causation은 별도 보안 후속 작업으로 추적한다.

## 검증 계획

1. core RED 테스트로 동기 `PENDING_RESCHEDULE` callback의 scope, `from/to`, 갱신 version,
   correlation/causation 전달을 고정하고, 미지원 callback의 fail-closed rollback을
   확인한다.
2. process 경로가 callback을 호출하고 optimistic concurrency failure를 전파하는지
   회귀 테스트한다. SSE 경로는 후속 설계 범위로 명시한다.
3. `searchDays`가 0/음수 또는 30 초과일 때와 affected 100건 초과가 `LIMIT 101`
   preflight 및 mutation 전 재검증으로 거부되는지 테스트한다. 후보 누적이 2,000건을
   초과하면 일부 후보를 남기지 않고 전체 rollback되는지도 확인한다.
4. messaging writer 테스트로 canonical row mismatch를 거부하고 `STATUS_CHANGED`
   payload의 appointment version, `fromState`, `toState`, context 및 pending row를
   확인한다.
5. API wiring/통합 테스트로 동기 closure 상태와 outbox row가 같은 transaction에서
   commit되고, writer 실패 시 둘 다 rollback되는지 확인한다.
6. 대표 affected 수(100건)와 최대 searchDays(30)를 기준으로 동일한 로컬 TestDB
   dialect에서 2회 warm-up 후 10회 순차 측정한다. p95 처리시간은 10초 이하이고,
   preflight affected query는 반환 row가 최대 101건이며, 측정 transaction의
   lock-wait는 0이어야 PASS로 판정한다. SQL count와 transaction duration은 매 회
   기록하고 첫 측정 2회가 아닌 나머지 8회의 p95를 사용한다. 후보 fan-out 초과
   rollback을 같은 조건에서 3회 재실행하여 mutation row가 0이고 rollback 시간이
   성공 경로 p95의 2배 이하인지 확인한다. 이 값은 배포 SLO가 아니며 bounded
   transaction/fan-out 회귀를 감지하기 위한 명시적 smoke threshold다.
7. 영향을 받은 Gradle module targeted test와 `git diff --check`를 실행하고 README/runbook
   문구가 실제 stream 범위와 일치하는지 확인한다.

## 수용 기준과 DoD

- 동기 closure endpoint가 `PENDING_RESCHEDULE` 상태 이벤트를 durable outbox에 기록한다.
- SSE batch stream status event 확장은 명시된 후속 작업으로 남고 이번 PR의 완료 조건으로
  오인되지 않는다.
- 기존 core 호출자와 함수형 writer fixture는 컴파일 호환성을 유지하되, 상태 이벤트를
  지원하지 않는 실행 adapter는 fail-closed된다.
- 이벤트는 canonical row의 version/status와 요청 command context를 보존한다.
- 상태·이력·후보·outbox의 transaction 원자성이 테스트로 증명된다.
- `searchDays <= 30`, affected `<= 100` bounded transaction 계약과 성능 smoke 증거가
  있다. 예약당 추가 canonical read/scope 검증 SQL 예산과 p95/lock 관찰값을 기록한다.
- README와 운영 runbook은 closure 중간 상태가 포함되고 commitment-v2는 여전히 제외됨을
  명시한다.
- 관련 테스트가 통과하고 P0/P1 검토 blocker가 없다. 실제 broker/registry/SLO 검증은
  별도 운영 작업으로 남기며 Issue #17을 자동 종료하지 않는다.
