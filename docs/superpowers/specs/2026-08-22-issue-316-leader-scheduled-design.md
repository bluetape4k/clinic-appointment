# #316 리마인더 복구 스케줄러의 `@LeaderScheduled` 전환 설계

## 문제와 목표

`NotificationReminderSchedulingRunner`는 현재 Spring `@Scheduled`와
`LettuceLeaderGroupElector.runIfLeader`를 같은 클래스에서 수동으로 조합한다.
이 경계에는 스케줄 등록, leader 획득, 실패 흡수, health 기록, Micrometer
decorator 연결이 함께 들어 있어 실행 정책을 변경할 때 중복 배선과 테스트
범위가 커진다.

Issue #316의 목표는 `bluetape4k-leader 0.5.0`이 제공하는
`@LeaderScheduled`를 reminder recovery 실행 경계에 적용해 스케줄과 단일
leader 획득을 하나의 annotation 계약으로 단순화하는 것이다. 다음 계약은
그대로 유지한다.

- `AppointmentReminderScheduler.triggerOnce()`가 한 번의 leader action 안에서
  전체 DB claim/fencing 흐름을 실행한다.
- DB lease와 fencing이 발송 정합성의 최종 권위이며 leader 기능을 비즈니스
  정확성의 유일한 근거로 사용하지 않는다.
- 애플리케이션 준비 직후의 첫 recovery 실행, coroutine 취소 전파, bounded
  notification health 상태 요약을 보존한다.
- 여러 인스턴스에서 동시에 scheduler가 실행되지 않도록 하고, leader lease가
  사라지거나 backend 오류가 발생해도 다음 scheduled tick을 막지 않는다.

## 근거 원장

| 근거 | 확인한 내용 |
|---|---|
| `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunners.kt` | 현재 `@Scheduled`와 `LeaderGroupElector.runIfLeader` 수동 경계, `ApplicationReadyEvent` 즉시 호출, 취소 재전파 |
| `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt` | 기존 group elector/decorator bean, runner 주입, optional leader health 조건 |
| `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderHealth.kt` | group lease 상태와 최근 획득 성공·backend 실패 bounded 상태 |
| `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunnersTest.kt` | 현재 leader 획득·skip·Redis 오류·취소 테스트 계약 |
| `bluetape4k-leader/leader-spring-boot/.../LeaderScheduled.kt` | `@Scheduled`와 단일 `@LeaderElection`을 결합하는 0.5.0 annotation 속성 |
| `bluetape4k-leader/leader-spring-boot/.../LeaderElectionAspect.kt` | `LeaderElectorFactory` 선택, `failureMode`, `LeaderAopMetricsRecorder` callback, cancellation 처리 |
| `bluetape4k-leader/leader-core/.../LeaderState.kt` | 단일 leader lease 상태 구조 |
| `bluetape4k-leader/leader-micrometer/.../MicrometerNames.kt` | AOP metric 이름 `leader.aop.*`와 기본 lock tag sanitization |
| [Issue #316](https://github.com/bluetape4k/clinic-appointment/issues/316) | 요구사항, 제외 범위, 수용 기준 |
| [bluetape4k-leader #536](https://github.com/bluetape4k/bluetape4k-leader/issues/536) | upstream `@LeaderScheduled` 도입 배경 |

`@LeaderScheduled`는 group elector가 아니라 `LeaderElectorFactory`가 만드는
single elector를 사용한다. 따라서 기존 group lease의 `leaders` 목록을
그대로 재사용하지 않고, 같은 Redis backend factory에서 만든
`LeaderElector.state(lockName)`을 health 상태 조회에 사용한다. annotation AOP가
실행한 elector와 health 조회용 elector는 동일 Redis lock 이름을 사용하지만
서로 다른 객체일 수 있다. 객체 동일성은 계약으로 요구하지 않는다.

## 결정한 설계

### 실행 경계

`NotificationReminderSchedulingRunner.poll()`을 다음 계약으로 바꾼다.

- `@Scheduled`를 제거하고 `@LeaderScheduled`를 적용한다.
- lock 이름은 기존 `REMINDER_RECOVERY_LOCK_NAME`을 유지한다.
- fixed delay는 기존
  `clinic.notification.worker.reminder-recovery-interval` placeholder를
  그대로 사용한다.
- `failureMode = LeaderAspectFailureMode.SKIP`으로 지정해 leader backend
  오류와 정상 contention을 현재 scheduled tick 내부에서 흡수한다.
- `poll()`의 본문은 scheduler를 호출하고 결과 metrics/logging을 처리하는
  책임만 갖는다. leader 획득 여부를 판단하거나 elector의 action callback을
  직접 호출하지 않는다.
- 본문 내부에서는 기존처럼 `CancellationException`을 재전파하고, 일반
  scheduler 오류는 tick 경계에서 기록한 뒤 흡수한다.

annotation의 AOP 경계가 `poll()`을 감싸므로 self-invocation으로 leader
경계를 우회하지 않는다. `ApplicationReadyEvent`의 즉시 실행은
`NotificationReminderSchedulingBootstrap` 같은 별도 bean이 proxied runner의
`poll()`을 호출하도록 분리한다. 이 bean은 runner 자신이 `poll()`을 직접
호출하는 현재 구조를 대체한다.

### health와 metrics

- `NotificationLeaderHealthMonitor`의 elector 타입을 `LeaderElector`로
  바꾸고 `LeaderState.leader`의 lease 하나를 읽는다. `leaderPresent`와
  `leaseAtRisk`의 bounded 상태 요약 계약은 유지한다.
- health monitor는 scheduler를 허용하거나 차단하지 않는다. DB claim/fencing과
  AOP 실행 결과가 실제 작업을 결정한다.
- `NotificationLeaderAopMetricsRecorder`를 notification auto-configuration에
  연결한다. `onLockAcquired`에서는 기존 `recordAcquired()`를 호출하고,
  `onLockNotAcquired`의 `BACKEND_ERROR`만 `recordAcquisitionFailure()`로
  기록한다. 정상 contention은 실패로 세지 않는다.
- `bluetape4k-leader-micrometer`가 제공하는 `leader.aop.attempts`,
  `leader.aop.acquired`, `leader.aop.lock.not.acquired`,
  `leader.aop.task.failed`, `leader.aop.active` 등 upstream metric을
  사용한다. notification에서 `InstrumentedLeaderGroupElector`를 더 이상
  실행 경계에 연결하지 않으며, 기존 `shedlock.leader.*` decorator metric을
  같은 action에 중복 기록하지 않는다.
- health 조회용 `LeaderElector`는 Redis가 제공하는
  `lettuceLeaderElectionFactory`에서 생성하고, Redis/factory가 없으면
  기존처럼 health monitor를 만들지 않는다. host가 제공한 compatible
  `LeaderElector`는 우선 존중한다.

### auto-configuration과 dependency

- version catalog에
  `io.github.bluetape4k.leader:bluetape4k-leader-spring-boot` alias를
  추가한다. 버전은 현재 BOM 관리 정책을 따르고 직접 선언하지 않는다.
- `appointment-notification`은 annotation과 auto-configuration을 사용하기
  위해 해당 alias를 API dependency로 추가한다.
- 기존 Redis Lettuce와 leader micrometer dependency는 compatibility test와
  upstream AOP metrics recorder에 필요하므로 실제 사용처를 확인한 뒤에만
  제거한다. `InstrumentedLeaderGroupElector` 전용 import와 bean은 제거한다.
- Spring Boot conditional bean은 optional class가 없는 classpath에서
  notification 모듈을 시작할 수 있어야 한다. `LeaderElector`/factory를
  참조하는 configuration phase에는 class-name 조건과 bean 조건을 함께
  적용한다.

## 실패 모드와 수명 주기

| 상황 | 기대 동작 | 검증 경계 |
|---|---|---|
| 정상 leader 획득 | `triggerOnce()`를 한 번 실행하고 recovery metrics를 기록한다. | runner/AOP integration test |
| contention | action 본문을 실행하지 않고 다음 tick을 기다린다. | AOP proxy test |
| leader backend 오류 | `failureMode=SKIP`으로 tick에서 흡수하고 health recorder가 실패를 기록한다. | failure-mode test |
| scheduler 본문 오류 | 일반 예외를 로그로 남기고 scheduled loop를 계속한다. | runner unit test |
| `CancellationException` | 예외를 삼키지 않고 재전파하며 AOP/elector cleanup을 허용한다. | cancellation regression test |
| lease 만료·ownership loss | 현재 action의 lock cleanup 후 DB fencing이 stale claim을 차단한다. | lease/lifecycle test 또는 명시적 통합 증거 |
| 애플리케이션 종료 | scheduled task와 AOP lease가 정리되고 새 작업을 시작하지 않는다. | Spring lifecycle test |
| MeterRegistry 부재 | 기능은 유지하고 optional AOP metrics recorder만 비활성화한다. | context-runner negative test |
| Redis/factory 부재 | local factory의 기존 단일 JVM 동작을 유지하고 notification health monitor는 만들지 않는다. | classpath/condition test |

## 호환성·롤백

- `AppointmentReminderScheduler`의 public API, DB outbox schema, claim/fencing,
  reminder policy는 변경하지 않는다.
- `ReminderRecoveryTriggerGuard`와 scheduler의 deprecated compatibility
  surface는 이 issue에서 제거하지 않는다. 다만 scheduled runner의 leader
  실행 경계에서는 사용하지 않는다.
- 직접 생성하는 기존 테스트와 호출부는 `NotificationReminderSchedulingRunner`
  생성자에서 새 필드가 필요하지 않도록 유지하거나, 필요한 경우 기본값을
  제공한다.
- 문제가 생기면 `@LeaderScheduled` dependency와 annotation 변경을 되돌리고,
  이전 runner/auto-configuration commit으로 복귀할 수 있다. DB schema나
  발송 데이터에는 migration rollback이 필요하지 않다.

## 범위 제외

- DB claim/fencing을 제거하거나 leader lock을 비즈니스 정합성의 유일한
  보장으로 승격하지 않는다.
- `NotificationProperties`에 leader wait/lease/metrics 정책을 새로 외부화하지
  않는다. 이는 후속 Issue #317의 범위다.
- lease 연장·ownership loss를 notification trace/metric 전체에 연결하는
  확장은 후속 Issue #319의 범위다. 이 issue에서는 upstream AOP recorder와
  기존 bounded health 상태를 연결하는 데 그친다.
- 새로운 scheduler framework, 자체 AOP aspect, 새로운 외부 dependency를
  추가하지 않는다.

## 수용 기준과 DoD

- [ ] `poll()`은 수동 `@Scheduled`/`runIfLeader` 없이 `@LeaderScheduled` 단일
      경계를 사용한다.
- [ ] 애플리케이션 준비 직후 첫 recovery 실행은 proxied runner를 통해 한 번
      수행된다.
- [ ] 다중 인스턴스 contention에서 scheduler action이 한 인스턴스에서만
      실행되고, DB claim/fencing은 최종 중복 방어로 남는다.
- [ ] backend 오류·본문 오류·취소·shutdown에서 의도한 예외/cleanup 계약을
      회귀 테스트로 증명한다.
- [ ] notification health monitor가 single `LeaderState`를 읽고 bounded
      성공·backend 실패 기록을 유지한다.
- [ ] 기존 `shedlock.leader.*` 중복 실행 metric 없이 upstream `leader.aop.*`
      metric을 검증한다.
- [ ] optional dependency/classpath 조건과 host-provided elector를
      `ApplicationContextRunner`로 검증한다.
- [ ] `./gradlew :appointment-notification:test`와 필요한 clean compile/test,
      `git diff --check`가 통과한다.
- [ ] 구현 계획, 6-lens review, lesson, PR body와 Issue #316 read-back을
      한국어로 남긴다.

## 검토 초점

- **정합성:** leader action은 reminder materialization을 감싸지만 DB
  claim/fencing이 최종 권위인지 확인한다.
- **수명 주기:** AOP lease, scheduled task, application-ready bootstrap,
  shutdown, coroutine cancellation의 소유권과 정리를 확인한다.
- **관측성:** `leader.aop.*`와 notification health 상태가 중복 없이
  low-cardinality로 기록되는지 확인한다.
- **Spring 경계:** `@LeaderScheduled`가 실제 proxy에서 실행되고, optional
  class가 없는 context가 안전하게 조건 제외되는지 확인한다.
- **호환성:** deprecated direct-call surface와 Redis 없는 단일 JVM 동작을
  불필요하게 깨뜨리지 않는지 확인한다.
