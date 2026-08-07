# PR #205 누락 reminder 복구 설계 명세

상태: Issue #208 historical backfill용 복원 명세
기준일: 2026-08-07
대상 PR: [#205](https://github.com/bluetape4k/clinic-appointment/pull/205)
historical exact head: `cb8c093ff77289242093b4e1c832e95e73b46870`

## 문제와 목표

스케줄러 outage 또는 leader 교체 동안 생성되지 않은 appointment reminder를 durable checkpoint와 outbox 멱등성으로 복구한다. 대상은 확정 예약 projection이며 환자 목록·연락처 snapshot을 새로 만들지 않는다.

1. startup 및 hourly bounded scan으로 누락을 찾는다.
2. same-day/day-before due window를 구분하고, 이미 지난 후보는 suppress한다.
3. 아직 due가 아니면 `availableAt`이 미래인 outbox를 기록할 수 있다.
4. 동일 idempotency key는 enqueue/suppress를 한 번만 적용한다.
5. `(runId, lastAppointmentId)` checkpoint로 restart/leader recovery를 보장한다.
6. worker disabled gate와 운영 metric/logging으로 안전하게 중지·재개한다.

## 계약

| 영역 | 계약 |
|---|---|
| 조회 | `CONFIRMED`와 keyset cursor, 고정 batch limit; 전체 테이블 memory load 금지 |
| 시간 | `dueAt > now`는 future scheduling, `dueAt < now - catchUpWindow`는 missed suppression |
| 멱등성 | 기존 outbox unique key를 그대로 사용하며 `ALREADY_EXISTS`는 성공 수렴 |
| 저장 | checkpoint/outbox mutation은 `transaction {}` 안에서 수행 |
| coroutine 경계 | suspend source는 `Mutex`와 `withContext(Dispatchers.IO)`로 cursor를 보호하고 blocking Exposed 작업을 IO 경계 안에 둔다 |
| 개인정보 | notification payload에 필요한 최소 값만 사용하고 원본 patient/contact snapshot을 보관하지 않음 |
| 장애 | materialization 실패는 candidate 상태를 확정 전진시키지 않고 다음 scan에서 재시도 |

## 비목표

provider 호출, notification content 정책 변경, 예약 상태 변경, 무제한 backfill, 분산 lock 도입은 포함하지 않는다.

## 수용 기준

- 두 번 스캔해도 enqueue/suppress가 중복되지 않는다.
- cursor는 appointment id가 증가하는 순서로만 전진한다.
- worker가 disabled이면 side effect가 없다.
- cancellation/restart 뒤 checkpoint가 손상되지 않는다.
- `git diff --check`, reminder recovery focused tests, notification module test가 기준 증거가 된다.
