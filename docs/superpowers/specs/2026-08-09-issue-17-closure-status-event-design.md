# Issue #17 closure `PENDING_RESCHEDULE` 상태 이벤트 설계

## 목적

Issue #17의 legacy 예약 이벤트 stream에서 임시휴진으로 인한
`PENDING_RESCHEDULE` 상태 전이가 누락된 문제를 보강한다. 기존 create/status/cancel과
최종 reschedule 이벤트는 유지하고, closure 재배정의 중간 상태도 동일한 transactional
outbox 계약으로 전달한다.

## 현재 근거와 범위

- `ClosureRescheduleService.processClosureReschedule`는 예약 상태와 상태 이력을 하나의
  Exposed transaction에서 기록하지만 messaging callback을 호출하지 않는다.
- `streamClosureReschedule`도 예약별 transaction에서 같은 전이를 수행하지만 callback은
  SSE 진행률에만 사용된다.
- `appointment-core`는 `appointment-messaging`을 의존하지 않는다. 현재
  `AppointmentRescheduleNotificationWriter`가 재배정 알림과 outbox를 연결하는
  dependency-neutral port다.
- `ServiceConfig`는 이 port의 API composite 구현에서 최종 `RESCHEDULED` outbox를
  이미 기록하므로 동일한 경계에 상태 변경 위임을 추가할 수 있다.
- 현재 README와 운영 runbook은 closure의 중간 `PENDING_RESCHEDULE`를 명시적으로
  제외한다고 기록한다.

이번 변경은 legacy closure 두 경로와 그 문서/검증을 다룬다. commitment-v2 전체 stream,
실제 broker·Schema Registry·운영 SLO, notification provider의 새 사용자 동작은 범위에
포함하지 않는다.

## 대안 비교

### A. 기존 callback에 기본 `statusChanged` 확장 (채택)

`AppointmentRescheduleNotificationWriter`에 기본 no-op 메서드를 추가한다. 기존
4-인자 함수형 람다 구현은 수정 없이 컴파일되고, API composite만 override하여
notification과 messaging writer를 호출한다. core가 messaging 모듈을 역참조하지 않으며
현재 재배정 callback과 같은 transaction 경계를 재사용한다.

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

- 입력: `tenantGroupId`, 갱신된 `AppointmentRecord`, 명시적 `version`,
  `fromState`, `toState`, `AppointmentCommandContext`
- 기본 동작: no-op. 기존 호출자/테스트의 source compatibility를 보존한다.
- `toState`는 현재 항상 `PENDING_RESCHEDULE`이며, callback 자체가 임의 상태를
  변경하지 않는다.

각 closure 경로는 optimistic update 직후 `findByIdAndScope`로 갱신 row를 읽는다. 기존
상태를 `fromState`로 보존하고 상태 이력과 callback을 같은 transaction에서 호출한다.
갱신 row의 version을 이벤트 version으로 사용하며, callback 예외는 transaction 전체를
rollback시킨다.

서비스의 `commandContext`는 마지막 선택 인자로 추가한다. API caller는 요청 correlation을
검증한 context를 전달하고, legacy 내부 호출자는 고정된 root correlation을 사용하여 기존
호출 시그니처를 유지한다.

### API wiring과 context 전달

`ServiceConfig`의 composite callback은 다음 순서로 동작한다.

1. 기존 `AppointmentNotificationWriter.statusChanged`를 호출한다. 현재 writer는
   `PENDING_RESCHEDULE` 대상 notification을 생성하지 않으므로 provider 동작은 변하지
   않는다.
2. `AppointmentOutboxWriter.statusChanged`를 같은 caller transaction에서 호출한다.
   scope는 `TenantClinicScope(tenantGroupId, appointment.clinicId)`로 만들고 context는
   `AppointmentMessagingContext.from(commandContext)`로 변환한다.

REST closure endpoint는 `HttpServletRequest`에서 correlation을 읽어 service에 전달한다.
SSE endpoint는 request 경계에서 context를 먼저 만들고 virtual worker에 값 객체로 캡처해
전달한다. worker 안에서 servlet request를 재사용하지 않는다.

### 오류와 원자성

- optimistic update가 실패하면 상태 이력·callback·후보 생성을 실행하지 않고 기존
  concurrent-change 오류를 유지한다.
- 갱신 row를 다시 읽지 못하면 transaction을 실패시켜 stale version 이벤트를 방지한다.
- outbox writer가 scope/codec/DB 오류를 던지면 상태 변경과 후보까지 rollback되어 API가
  성공 응답을 반환하지 않는다.
- SSE 한 예약 transaction의 callback 실패는 해당 예약 작업을 rollback하고 기존 SSE
  오류 경로를 따른다. 앞서 commit된 다른 예약의 이벤트는 유지된다.

## 검증 계획

1. core RED 테스트로 `PENDING_RESCHEDULE` callback의 `from/to`, 갱신 version,
   correlation/causation 전달을 고정한다.
2. process와 SSE service 경로가 각각 callback을 호출하는지 회귀 테스트한다.
3. messaging writer 테스트로 `STATUS_CHANGED` payload의 appointment version,
   `fromState`, `toState`, context 및 pending row를 확인한다.
4. API wiring/통합 테스트로 closure 상태와 outbox row가 같은 transaction에서 commit되고,
   writer 실패 시 둘 다 rollback되는지 확인한다.
5. 영향을 받은 Gradle module targeted test와 `git diff --check`를 실행하고 README/runbook
   문구가 실제 stream 범위와 일치하는지 확인한다.

## 수용 기준과 DoD

- 두 closure 경로 모두 `PENDING_RESCHEDULE` 상태 이벤트를 durable outbox에 기록한다.
- 기존 core 호출자와 함수형 writer fixture는 별도 수정 없이 계속 컴파일된다.
- 이벤트는 갱신된 version과 요청 command context를 보존한다.
- 상태·이력·후보·outbox의 transaction 원자성이 테스트로 증명된다.
- README와 운영 runbook은 closure 중간 상태가 포함되고 commitment-v2는 여전히 제외됨을
  명시한다.
- 관련 테스트가 통과하고 P0/P1 검토 blocker가 없다. 실제 broker/registry/SLO 검증은
  별도 운영 작업으로 남기며 Issue #17을 자동 종료하지 않는다.

