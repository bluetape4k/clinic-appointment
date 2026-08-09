# Issue #17 동기 closure 상태 이벤트 보강 교훈

## 결정

legacy 동기 임시휴진 재배정만 이번 범위에 포함했다. `ClosureRescheduleService`는
알림 notification port와 분리된 필수 `AppointmentStatusEventWriter`를 받아
`PENDING_RESCHEDULE` 전이 의도를 전달한다. API adapter는 canonical appointment row와
최신 state history를 다시 읽어 version, `fromState`, `toState`를 검증한 뒤
`STATUS_CHANGED` outbox를 저장한다. SSE batch와 commitment-v2는 별도 후속 범위로 남긴다.

HTTP correlation 값은 trace 용도로만 보존한다. causation 값은
`http-command-<UUID>` 형식으로 서버가 생성하므로 호출자가 lineage를 위조할 수 없다.
closure matcher는 `clinicId` query, tenant 소유권, ADMIN/STAFF 역할과 principal의
비어 있지 않은 exact `allowedClinicIds`를 함께 확인한다. candidate GET·confirm·auto도 canonical
appointment clinic을 읽은 뒤 같은 allow-list를 재검증하며, 세 HTTP mutation 모두
client correlation과 서버 causation을 분리한다.

## 트랜잭션과 롤백

closure 처리는 preflight와 write 두 단계로 나눈다. preflight는 병원 소유권과 `LIMIT 101`
probe를 확인하고 snapshot을 만든다. 슬롯 precompute는 transaction 밖에서 cache를 사용하고,
write 단계는 snapshot의 ID/version/status를 재검증한 뒤 상태 변경, history, 후보와 outbox를
같은 transaction에 기록한다. status-event writer 또는 outbox adapter가 실패하면 mutation
전체가 rollback되어야 한다.

closure/confirm/auto에는 durable idempotency key가 없다. 따라서 `503` 또는 응답 유실 뒤에는
correlation ID와 exact scope로 appointment/history/outbox를 제한 조회하고, commit된 mutation이
없을 때만 재시도한다. 동일 key의 응답 replay를 운영 계약으로 주장하지 않는다.

회귀 증거:

- `AppointmentNotificationAtomicityTest.closureStatusWriterFailureRollsBackStateHistoryAndCandidates`
  는 core transaction에서 상태·이력·후보 rollback을 확인한다.
- `RescheduleControllerTest.closureOutboxFailureReturns503AndRollsBack`는 실제 API wiring에서
  `503 APPOINTMENT_MESSAGING_UNAVAILABLE`, 상태/version 원복, history/candidate/outbox 원복을
  확인한다.

## 성능·경합 smoke

`ClosureRescheduleServicePerformanceTest`는 H2에서 100건/30일/후보 2,000건 fixture를 사용하고,
singleton PostgreSQL에서 mutation row-lock 수렴을 확인한다.
2회 warm-up 뒤 10회 측정하며 다음 경계를 실행 가능한 assertion으로 고정한다.

| 증거 | 계약 |
|---|---|
| 슬롯 cache | 동일 key당 계산 1회, 총 30회 |
| write SQL | core `<= 2,400` + 실제 status writer `<= 3/event`, 합성 `<= 2,700` |
| latency | 측정 10회의 p95 `<= 10초` |
| 후보 상한 | 2,001건 path 3회 모두 mutation row 0 |
| 경합 | PostgreSQL mutation lock을 관찰하고 latch 해제 뒤 2초 안에 service/CAS 수렴 |

이번 로컬 H2 실행 결과는 측정 10회 `[74, 71, 69, 75, 74, 71, 66, 79, 61, 60]ms`,
p95 `79ms`, core write statement `2,304개`, 슬롯 계산 `30회`였다. 실제 status writer는
event당 최대 3 statements였고, 후보 상한과 PostgreSQL 경쟁 writer 테스트도 통과했다.

실행 명령:

```bash
./gradlew :appointment-core:test --no-daemon --console=plain \
  --tests 'io.bluetape4k.clinic.appointment.service.ClosureRescheduleServicePerformanceTest'
./gradlew :appointment-messaging:test --no-daemon --console=plain \
  --tests 'io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriterTest'
```

이 harness는 singleton PostgreSQL의 제한된 row-lock 의미만 확인하며 production PostgreSQL
lock-wait, broker, Schema Registry와 배포 SLO를 증명하지
않는다. 해당 운영 증거는 별도 rollout 작업에서 인증된 환경으로 수집해야 한다.

## 남은 후속 작업

Issue #17의 SSE status/lifecycle event 확장은 별도 owner와 acceptance criteria를 가진
follow-up으로 등록한다. commitment-v2 상태 전이는 legacy bulk closure writer가 임의로
변경하지 않는다.
