# #254 리마인더 리더 실행 경계와 Micrometer 관측성 설계

## 문제와 목표

`appointment-notification`은 Redis `LettuceLeaderGroupElector` 빈을 만들지만,
리마인더 보정 경로는 `ReminderRecoveryTriggerGuard`의 Boolean 결과를 확인한
뒤 별도로 scan을 실행한다. 따라서 guard 확인 직후 리더 lease가 바뀌어도 전체
scan 구간이 동일한 leadership 안에 있다는 보장이 없다. 또한 생성된 elector가
실제 실행 경계에서 사용되지 않는다.

목표는 기존 DB lease/fencing이 제공하는 발송 정확성 권위를 유지하면서,
리마인더 보정 한 tick 전체를 leader action으로 감싸고 `bluetape4k-leader:0.5.0`
의 `leader-micrometer` decorator로 획득·skip·duration·active를 낮은 cardinality로
관측하는 것이다.

## 현재 근거

- `AppointmentReminderScheduler.triggerOnce()`는 guard를 먼저 검사한 뒤 paged
  scanner를 반복 호출한다.
- `NotificationReminderSchedulingRunner.poll()`은 현재 scheduler를 직접 호출하며
  elector를 받지 않는다.
- `NotificationAutoConfiguration.notificationLeaderElection()`은
  `LettuceLeaderGroupElector`를 생성하지만 실행 경계에는 연결하지 않는다.
- `bluetape4k-leader:0.5.0`의
  [`InstrumentedLeaderGroupElector`](https://github.com/bluetape4k/bluetape4k-leader/blob/0.5.0/leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/InstrumentedLeaderElectors.kt)는
  `LeaderGroupElector.runIfLeader` action을 보유한 동안
  `shedlock.leader.acquired`, `shedlock.leader.not_acquired`,
  `shedlock.leader.duration`, `shedlock.leader.active`를 기록한다.
- [`LeaderMetricTagOptions`](https://github.com/bluetape4k/bluetape4k-leader/blob/0.5.0/leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/LeaderMetricTagOptions.kt)의
  기본 lock tag는 `redacted-lock`이다.

## 제약과 경계

- 변경 모듈은 `appointment-notification`과 중앙 version catalog의 새 alias로
  제한한다.
- `bluetape4k-leader-micrometer`는 BOM이 관리하는 버전을 사용하며 직접 버전을
  선언하지 않는다.
- DB lease/fencing, outbox schema, 알림 정책, `leader-spring-boot`/Actuator
  자동 설정은 변경하지 않는다.
- 기존 직접 호출자 호환성을 위해 `ReminderRecoveryTriggerGuard` 타입과
  scheduler의 선택적 legacy 인자는 deprecated 상태로 남긴다. 다만
  auto-configuration은 더 이상 guard를 등록하지 않으며 scheduled runner는
  guard가 아닌 leader action 경계를 사용한다.
- Redis elector가 없으면 기존 단일 인스턴스 로컬 동작을 보존한다. elector가
  있으면 leader action이 `triggerOnce()` 전체를 감싼다.
- Redis 획득 실패는 scheduler tick 경계에서 흡수하되, action의
  `CancellationException`은 재전파하여 coroutine 취소 계약을 훼손하지 않는다.

## 대안과 선택

### A. 기존 blocking group elector를 runner action 경계에 연결 (권고)

Auto-configuration이 `LettuceLeaderGroupElector`를 registry가 있을 때
`InstrumentedLeaderGroupElector`로 감싸고, runner가
`runSynchronously { elector.runIfLeader(LOCK, { scheduler.triggerOnce() }) }`를
호출한다. registry가 없으면 raw elector를 반환하고, Redis 자체가 없으면
runner는 기존 direct path를 사용한다.

장점은 issue가 요구한 공식 decorator를 그대로 사용하고 public scheduler API와
DB 정확성 경계를 건드리지 않는다는 점이다. 단점은 Spring scheduled blocking
경계에서 `runSynchronously`를 계속 사용한다는 점이며, 이는 현재 동일 runner가
사용하는 동기 event 경계와 일치한다.

### B. suspend group elector로 교체하고 로컬 Micrometer wrapper를 작성

`LettuceSuspendLeaderGroupElector`로 suspend action을 직접 감싸고, 현재
`leader-micrometer`에 group suspend decorator가 없으므로 notification 모듈에
별도 metric wrapper를 추가한다.

취소에는 자연스럽지만 공식 `InstrumentedLeaderGroupElector`를 활용하지 못하고
metric 구현·cardinality 정책을 복제한다. 범위와 유지보수 비용이 불필요하게
커져서 선택하지 않는다.

### C. 기존 Boolean guard만 elector 조회로 대체

guard가 `runIfLeader` 결과만 계산하고 scan은 밖에서 실행한다. 현재 구조보다
간단하지만 lease가 scan 동안 유지되지 않아 이 issue의 핵심 정확성 결함을
해결하지 못한다. 선택하지 않는다.

## 구성과 데이터 흐름

1. `NotificationAutoConfiguration`이 `StatefulRedisConnection`에서 raw
   `LettuceLeaderGroupElector`를 만든다.
2. `MeterRegistry`가 있으면 동일 bean을
   `InstrumentedLeaderGroupElector(delegate, registry,
   REMINDER_RECOVERY_LOCK_NAME)`로 감싼다. decorator의 기본 tag sanitization을
   유지하여 lock/leader 식별자를 raw metric tag로 내보내지 않는다.
3. `NotificationReminderSchedulingRunner`는 optional `LeaderGroupElector`를
   받아 lock 이름으로 한 tick 전체를 action에 넣는다.
4. acquired 결과만 `NotificationOutboxMetrics.recordReminderRecovery`로
   기록한다. not-acquired는 null로 종료하고 scanner를 호출하지 않는다.
5. action/Redis 실패는 기존 scheduled tick의 예외 경계에서 처리한다. 취소는
   재전파한다. decorator와 Lettuce elector의 `finally`가 active gauge와 lease를
   정리하는지 테스트로 확인한다.

## 실패 모드와 완화

| 실패 모드 | 기대 동작 | 검증 |
|---|---|---|
| leader 미획득 | `triggerOnce()` 미호출, metric not-acquired 증가 | runner 단위 테스트 |
| Redis 획득/통신 실패 | tick에서 예외 흡수, DB scanner 미호출 | fake elector 예외 테스트 |
| scanner/action 실패 | lease 정리 후 다음 tick을 위해 예외 흡수 | runner 실패 테스트 + decorator cleanup |
| coroutine 취소 | `CancellationException` 재전파, active/lease cleanup | 취소 회귀 테스트 |
| MeterRegistry 부재 | raw elector로 동작, 기능 중단 없음 | auto-config 조건 테스트 |
| metric tag 오염 | 기본 `redacted-lock`으로 제한 | SimpleMeterRegistry tag assertion |

## 호환성·롤백

- `AppointmentReminderScheduler`의 scanner/paging API는 유지한다. 기존
  `ReminderRecoveryTriggerGuard` 타입과 선택적 인자는 direct-call 호환용으로
  deprecated 유지하되 auto-configuration에서는 주입하지 않는다.
- runner의 새 elector 인자는 기본값 `null`로 두어 직접 생성하는 기존 호출부와
  테스트를 보존한다.
- Redis를 되돌리거나 decorator를 제거해도 DB lease/fencing은 그대로 동작한다.
- 문제가 발생하면 새 catalog alias와 auto-config decorator를 되돌리고,
  runner의 optional elector를 `null`로 두면 기존 direct scheduler path로
  즉시 복귀할 수 있다.

## 수용 기준과 DoD

- [ ] reminder scan의 모든 page가 동일 leader action 내부에서만 실행된다.
- [ ] 실제 elector bean이 runner에 주입되고, 미획득 시 scanner가 호출되지 않는다.
- [ ] acquired/not-acquired/duration/active meter가 `lock.name` 단일 저-cardinality
      tag로 기록되고 기본 sanitization이 유지된다.
- [ ] action 실패·취소·Redis 실패에서 metric active와 leader lease 누수가 없다.
- [ ] scheduled reminder path에서 `ReminderRecoveryTriggerGuard` Boolean
      선행 검사가 제거되고 leader action이 전체 scan을 감싼다.
- [ ] `./gradlew :appointment-notification:test` 및 diff/문서 검증이 통과한다.
- [ ] Korean lesson/review를 남기고 issue #254에 구현·검증 증거를 댓글로 남긴다.

## 7-tier 검토 초점

성능은 leader acquire와 decorator overhead를 기존 tick 경계에서 측정하고,
안정성은 lease/fencing 및 취소 cleanup을 확인한다. 보안은 lock/leader 식별자의
raw metric 노출을 막고, 운영은 네 가지 leader meter를 연결한다. 개발자/API는
optional elector와 순수 scanner 경계를 유지하며, 사용자/호출자는 중복 trigger를
줄이되 DB 정확성 계약을 변경하지 않는다. 통합은 notification 모듈 테스트와
Spring auto-configuration 조건을 함께 검증한다.
