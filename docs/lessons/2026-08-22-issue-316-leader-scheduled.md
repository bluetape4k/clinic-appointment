# Issue #316 `@LeaderScheduled` 전환 lesson

## 결정

리마인더 recovery의 실행 경계를 수동 `@Scheduled`와
`LeaderGroupElector.runIfLeader` 조합에서 `bluetape4k-leader 0.5.0`의
`@LeaderScheduled`로 전환했다. lock 이름과 fixed-delay property는 유지하고,
`failureMode=SKIP`으로 contention·leader backend 오류를 해당 tick에서 흡수한다.
`AppointmentReminderScheduler`의 DB claim/fencing은 그대로 두어 발송 정합성의 최종
권위를 leader lock으로 바꾸지 않았다.

## 구현에서 얻은 교훈

- `@LeaderScheduled`는 Spring AOP proxy에서만 leader 경계를 적용한다. 따라서
  `NotificationReminderSchedulingRunner`와 `poll()`을 `open`으로 두고,
  `ApplicationReadyEvent` 즉시 실행은 별도 `NotificationReminderSchedulingBootstrap`이
  주입된 proxied runner를 호출하게 해야 한다. 같은 bean의 self-invocation은 이 경계를
  우회한다.
- `ScheduledTaskHolder`에서 실제 `FixedDelayTask`를 읽어 fixed delay가 한 개 등록되는지
  확인해야 annotation reflection만으로 스케줄 등록을 주장하지 않게 된다.
- upstream Micrometer recorder의 `leader.aop.*` namespace와 `redacted-lock` tag를
  사용하고, 기존 `shedlock.leader.*` 중복 metric은 제거했다. reminder lock에 한정한
  notification recorder는 acquired와 backend failure만 bounded health 상태에 전달하며
  정상 contention을 장애로 세지 않는다.
- upstream `LeaderElectorFactory`에서 만든 elector는 notification이 소유한 Redis
  connection을 닫지 않는다. host-provided `LeaderElector`를 우선하고, Redis/factory가
  없는 classpath에서는 notification 자체가 계속 시작되도록 조건을 분리했다.
- Redis Lettuce single elector는 0.5.0에서 `supportsAuditLeaderState=false`이며
  `state(lockName)`는 기본 `LeaderState.empty(lockName)`을 반환한다. 따라서 이 API의
  empty read-back을 실제 Redis lease 상태 증거로 사용하지 않는다. 두 개의 single
  elector를 Redis 8.8에 연결하고, 첫 action을 1초 lease보다 오래 붙잡은 동안 두 번째
  elector가 lease 만료 후 재취득하는 통합 테스트를 authoritative lease evidence로
  사용했다. 실제 audit-state/ownership-loss 관측은 후속 Issue #319 범위다.

## 검증 증거

로컬 Docker 환경은 `colima status=running`, Docker context `default`, server
`28.4.0`으로 확인했다. `@Testcontainers`를 사용하지 않고 repository의
`Redis88Launcher` singleton을 사용했다.

| 검증 | 결과 |
|---|---|
| `NotificationSchedulingRunnersTest` | 13 tests passed |
| `NotificationLeaderHealthMonitorTest` + `NotificationLeaderAopMetricsRecorderTest` | 7 tests passed |
| `NotificationAutoConfigurationTest` | 24 tests passed |
| `NotificationLeaderMicrometerTest` | 3 tests passed |
| `NotificationLeaderScheduledIntegrationTest` | 5 tests passed; proxy, contention, backend error, cancellation, context-close cancellation |
| `RedisLeaderScheduledLeaseIntegrationTest` | 1 test passed; Redis 8.8 lease expiry and next-elector reacquisition |
| dependency lock/verification checks | PASS; lockfiles and `gradle/verification-metadata.xml` remain aligned |

## 운영·복귀

문제 발생 시 DB schema나 reminder scheduler API를 되돌리지 않고,
`@LeaderScheduled` dependency·runner·auto-configuration 변경을 마지막 GREEN
checkpoint 이전 commit 단위로 revert한다. #317의 wait/lease 설정 외부화와 #319의
lease extension·ownership-loss telemetry는 이 작업에서 선행 구현하지 않는다.

## 남은 경계

Redis Lettuce single elector의 audit-state 미지원 때문에 notification health가
실제 Redis lock 소유자와 lease 시각을 직접 read-back하지 못한다. 이번 변경은 이를
새로운 추정 로직으로 감추지 않고, lease 재취득과 DB fencing을 정확성 증거로 분리했다.
audit-state 또는 ownership-loss 관측을 추가할 때는 upstream capability와
`supportsAuditLeaderState` 조건을 먼저 재검토해야 한다.
