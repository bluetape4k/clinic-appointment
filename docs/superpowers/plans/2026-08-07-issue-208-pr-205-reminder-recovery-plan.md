# PR #205 reminder 복구 구현 계획 3-R 백필

기준 명세: `docs/superpowers/specs/2026-08-07-issue-208-pr-205-reminder-recovery-design.md`
historical exact head: `cb8c093ff77289242093b4e1c832e95e73b46870`

## 실행 순서

1. **Schema/checkpoint** — Flyway V16과 `ReminderRecoveryCheckpoints`의 run/cursor/active 계약을 H2·PostgreSQL·MySQL에서 확인한다.
2. **Bounded source** — `CONFIRMED` + keyset 조건, date window, page limit, commitment schedule lookup을 확인한다.
3. **Materializer** — enqueue, future scheduling, missed suppression을 동일 idempotency digest로 연결한다.
4. **Coroutine 경계** — cursor 보호는 `Mutex`, Exposed transaction은 `withContext(ioDispatcher)` 안에 두고 JVM monitor로 suspend 함수를 감싸지 않는다.
5. **Scheduler/운영** — application-ready/hourly trigger, disabled gate, 결과 metric, failure logging을 확인한다.
6. **검증** — duplicate scan, checkpoint restart, due boundary, cancellation, migration 및 notification focused tests를 실행한다.

## 명세 추적성 및 3-R

| 명세 기준 | 계획 증거 | P0/P1 |
|---|---|---:|
| bounded keyset + cursor | `JdbcAppointmentReminderRecoveryStore` query/limit/checkpoint | 0/0 |
| due/future/missed semantics | `NotificationReminderRecoveryScanner` branch tests | 0/0 |
| outbox idempotency | repository digest/CAS tests | 0/0 |
| restart/leader recovery | active checkpoint and runId tests | 0/0 |
| non-blocking coroutine boundary | Mutex + IO dispatcher test/static check | 0/0 |
| privacy/disabled operation | payload redaction and gate tests | 0/0 |

## 역사적 divergence

이 계획 계약은 올바른 non-blocking 경계를 요구하지만, PR #205 exact head에는 `synchronized(cursorLock)` 안에서 suspend `findCandidates`와 Exposed transaction을 수행하는 P1이 있었다. 따라서 exact head에 3-R PASS를 소급하지 않고, remediation 이후 6-R에서만 구현 PASS를 판정한다. PR #215가 `Mutex` + IO dispatcher로 수정했다.

**Step 3-R: PASS — P0=0, P1=0.** 계획 gate의 PASS이며 historical implementation verdict와 분리한다.
