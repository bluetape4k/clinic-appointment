# #254 리마인더 리더 경계와 Micrometer 교훈

## 문제

notification auto-configuration이 `LettuceLeaderGroupElector`를 만들고도
리마인더 scheduler에는 Boolean `ReminderRecoveryTriggerGuard`만 전달하고
있었다. guard 확인과 paged scan 사이에 leadership이 바뀔 수 있어, 한 tick의
전체 scan을 같은 리더십 안에서 실행한다는 계약이 없었다.

## 선택한 해법

- `bluetape4k-leader:0.5.0`의 공식
  `InstrumentedLeaderGroupElector`를 사용해 별도 metric wrapper를 만들지 않았다.
- `NotificationReminderSchedulingRunner`가 optional
  `LeaderGroupElector.runIfLeader` action 안에서 기존
  `runSynchronously { scheduler.triggerOnce() }`를 호출한다.
- Redis가 없으면 `leaderElector == null` direct path를 유지하고,
  `MeterRegistry`가 없으면 raw `LettuceLeaderGroupElector`를 반환한다.
- metric tag는 decorator 기본 sanitization을 그대로 사용해
  `lock.name=redacted-lock`만 노출한다. DB lease/fencing은 계속 outbox 발송
  정확성의 권위다.

## 구현 중 발견한 함정

초기 runner 구현에서 다음과 같은 nullable Elvis를 사용했다.

```kotlin
leaderElector?.runIfLeader(LOCK) { action() } ?: directPath()
```

`runIfLeader`의 정상적인 미획득 결과도 `null`이므로, Redis elector가 존재해도
미획득 시 direct path가 다시 실행되는 결함이 생겼다. acquired/skip 회귀 테스트가
이를 잡았고, 현재는 elector 유무를 먼저 `if`로 분기한 뒤 결과 `null`만 skip으로
처리한다.

또한 scheduled runner는 blocking group API를 사용하므로 action 바깥에서
`runSynchronously`를 감싸면 leader lease가 suspend 작업 전체를 보호하지 못한다.
현재는 blocking `runIfLeader` action 내부에 기존 동기 bridge를 배치했다. broad
`Exception` catch보다 `CancellationException`을 먼저 재전파해 취소 계약도
보존한다.

## 검증

- `NotificationLeaderMicrometerTest`: acquired/not-acquired/duration/active,
  action 실패 후 active gauge 0, raw lock tag 부재.
- `NotificationSchedulingRunnersTest`: acquired action, 미획득 skip, Redis 오류,
  action 취소 재전파.
- `NotificationAutoConfigurationTest`: registry가 있으면 instrumented elector,
  registry가 없으면 raw elector, startup 성공.
- `./gradlew :appointment-notification:test`: 142개 통과.
- `./gradlew :appointment-notification:build`: `BUILD SUCCESSFUL`.
- dependency insight에서 `bluetape4k-leader-micrometer:0.5.0` resolve 확인.

## 남은 운영 갭

실제 Redis 서버에서 lease 만료, fencing, 네트워크 단절 중 active gauge와 DB
권위가 함께 회복되는지는 이 로컬 테스트에서 실행하지 않았다. CI 또는 운영
Redis 환경에서 별도 통합 검증할 후속 작업이며, 현재 구현 실패로 간주하지 않고
issue/DoD의 `PENDING` 경계로 남긴다.
