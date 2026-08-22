# #316 리마인더 복구 스케줄러 `@LeaderScheduled` 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `NotificationReminderSchedulingRunner`의 수동 `@Scheduled`·`runIfLeader` 조합을 `bluetape4k-leader 0.5.0`의 `@LeaderScheduled` 단일 실행 경계로 바꾸고, DB claim/fencing·health·metrics·Spring lifecycle 계약을 회귀 테스트로 고정한다.

**Architecture:** runner의 `poll()`은 reminder lock 이름과 기존 fixed-delay placeholder를 가진 `@LeaderScheduled` 메서드가 된다. `failureMode=SKIP`으로 leader contention/backend 오류를 scheduled tick 경계에서 흡수하고, 별도 bootstrap bean이 proxied runner를 호출해 application-ready self-invocation 우회를 막는다. health 조회는 같은 Redis factory에서 만든 `LeaderElector`를 사용하며, upstream AOP callback을 bounded notification health 상태로 연결하는 adapter만 notification 모듈에 둔다. DB claim/fencing은 변경하지 않는다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Spring AOP, `bluetape4k-leader 0.5.0`, Exposed ORM, JUnit 5, MockK, Kluent, `ApplicationContextRunner`, Micrometer.

---

## 변경 파일과 책임

- Modify: `gradle/libs.versions.toml`
  - `io.github.bluetape4k.leader:bluetape4k-leader-spring-boot` BOM-managed alias를 추가한다.
- No change: `settings.gradle.kts`, CI/Nightly workflow, test resources, coverage aggregation
  - 새 Gradle module을 만들지 않으므로 module registration·publish variant·coverage target을 추가하지 않는다. 기존 `appointment-notification` test/build graph를 그대로 검증한다.
- Modify: `appointment-notification/build.gradle.kts`
  - `api(libs.bluetape4k.leader.spring.boot)`를 추가한다.
  - 기존 `bluetape4k-leader`와 `bluetape4k-leader-micrometer`는 Redis compatibility 테스트와 upstream AOP recorder auto-configuration 사용처가 있으므로 검증 후 유지한다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunners.kt`
  - `LeaderGroupElector` 주입·`runIfLeader` 호출·수동 `@Scheduled`를 제거한다.
  - `poll()`에 `@LeaderScheduled(name = REMINDER_RECOVERY_LOCK_NAME, fixedDelayString = "\${clinic.notification.worker.reminder-recovery-interval:PT1H}", failureMode = LeaderAspectFailureMode.SKIP)`를 적용한다.
  - scheduler 호출, 결과 metric, recovery 로그, 일반 예외 흡수, `CancellationException` 재전파를 유지한다.
  - `NotificationReminderSchedulingBootstrap`을 추가해 `ApplicationReadyEvent`에서 proxied runner의 `poll()`을 호출한다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderHealth.kt`
  - `LeaderGroupElector`/`LeaderGroupState`를 `LeaderElector`/`LeaderState`로 바꾸고 단일 `LeaderLease?`만 읽는다.
  - bounded 성공·backend 실패 기록과 UP/DEGRADED/DOWN 판정은 보존한다.
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderAopMetricsRecorder.kt`
  - reminder lock에 한정해 `onLockAcquired`를 `recordAcquired()`로, `SkipReason.BACKEND_ERROR`만 `recordAcquisitionFailure()`로 전달한다.
  - 정상 contention은 health 실패로 기록하지 않는다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
  - group elector/decorator bean과 관련 import를 제거한다.
  - upstream `LeaderElectorFactory`에서 health 조회용 `LeaderElector`를 조건부 생성하고 host-provided elector를 우선한다.
  - runner 생성자에는 scheduler, optional outbox metrics, validated suspend bridge timeout만 전달한다.
  - bootstrap과 AOP recorder bean을 조건부 등록한다.
  - Redis/Lettuce/factory가 없는 classpath에서 notification 자체가 시작되는 조건을 유지한다.
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/AppointmentApiApplication.kt`
  - notification이 직접 구성하는 Redis elector와 AOP factory는 유지하면서, API runtime에 없는 선택적 Exposed backend를 eager import하는 upstream `LeaderElectionAutoConfiguration`을 애플리케이션 경계에서 제외한다.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunnersTest.kt`
  - old group elector 테스트를 제거하고 annotation metadata, runner body, bootstrap delegation, exception/cancellation 계약을 고정한다.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderHealthMonitorTest.kt`
  - single `LeaderState` fixture와 `LeaderElector` mock으로 동일한 bounded health 판정을 검증한다.
- Replace: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderMicrometerTest.kt`
  - `InstrumentedLeaderGroupElector`와 `shedlock.leader.*` 검증을 제거하고 upstream `leader.aop.*` recorder/namespace 및 notification adapter 계약을 검증한다.
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderAopMetricsRecorderTest.kt`
  - lock 이름 필터, acquired/backend failure, contention 무시를 단위 검증한다.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt`
  - single elector factory, host override, Redis/classpath 조건, health/recorder/bootstrap 조건을 `ApplicationContextRunner`로 검증한다.
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderScheduledIntegrationTest.kt`
  - Spring proxy와 fake `LeaderElectorFactory`를 이용해 정상 획득, contention, backend 오류, bootstrap proxy 호출을 검증한다. Docker가 필요한 lease/lifecycle 검증은 이 모듈의 singleton launcher 규칙을 따른다.
- Create: `docs/lessons/2026-08-22-issue-316-leader-scheduled.md`
  - upstream annotation 선택, AOP metric 경계, DB fencing authority, proxy/lifecycle 검증 결과를 한국어로 기록한다.

## 실행 전 고정 조건

- 작업 브랜치는 `feat/issue-316-leader-scheduled`이며, 기존 설계·설계 리뷰 커밋 이후의 변경만 포함한다.
- Issue #316의 scope에 없는 `NotificationProperties` leader wait/lease 외부화(#317), lease/trace observability 확장(#319), DB claim/fencing 변경은 하지 않는다.
- `@LeaderScheduled`의 AOP 경계를 우회하는 same-bean `ApplicationReadyEvent` 호출은 허용하지 않는다.
- 기존 `REMINDER_RECOVERY_LOCK_NAME`, `AppointmentReminderScheduler` public API, outbox schema, deprecated direct-call surface를 유지한다.
- 모든 신규 KDoc, 테스트 설명, lesson, commit message는 이 repository의 한국어 정책을 따른다.

## Task 1: dependency와 기존 계약의 RED를 만든다

**Files:**
- `gradle/libs.versions.toml`
- `appointment-notification/build.gradle.kts`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunnersTest.kt`

- [x] **Step 1: catalog alias와 module API dependency를 추가한다.**
  - `bluetape4k-leader-spring-boot = { module = "io.github.bluetape4k.leader:bluetape4k-leader-spring-boot" }`를 기존 leader alias 옆에 추가한다.
  - `appointment-notification`에 `api(libs.bluetape4k.leader.spring.boot)`를 추가하고 version을 직접 지정하지 않는다.
- [x] **Step 2: 새 계약을 먼저 컴파일하는 failing test를 작성한다.**
  - `NotificationSchedulingRunnersTest`에 `poll` 메서드의 `LeaderScheduled` merged annotation을 읽어 lock 이름, fixed-delay placeholder, `LeaderAspectFailureMode.SKIP`을 확인하는 테스트를 추가한다.
  - bootstrap fixture에서 `NotificationReminderSchedulingBootstrap.onApplicationReady()`가 mock runner의 `poll()`을 정확히 한 번 호출하는 테스트를 추가한다.
  - 현재 production class가 아직 annotation/bootstrap을 제공하지 않으므로 RED compile failure를 확인한다.
- [x] **Step 3: RED를 기록한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationSchedulingRunnersTest" --no-build-cache --console=plain`
  - Expected: 새 annotation 또는 bootstrap symbol이 없어 실패한다. dependency resolution 실패가 발생하면 alias/module 좌표를 확인하고 이 task의 구현을 중단한 채 해당 실패를 기록한다.

## Task 2: runner와 application-ready proxy 경계를 구현한다

**Files:**
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunners.kt`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunnersTest.kt`

- [x] **Step 1: runner 단위 테스트를 새 body 계약으로 정리한다.**
  - old `LeaderGroupElector.runIfLeader`, `recordAcquired`, `recordAcquisitionFailure` 검증을 제거한다.
  - elector 없이 직접 `poll()`을 호출했을 때 `triggerOnce()` 결과 metric/logging이 유지되는지, 일반 예외가 tick에서 흡수되는지, `CancellationException`이 재전파되는지 남긴다.
  - contention/backend 결과는 proxy integration test에서 검증하도록 경계를 분리한다.
- [x] **Step 2: 최소 runner 구현을 추가한다.**
  - `@Scheduled`와 group elector constructor parameter를 제거하고 `@LeaderScheduled`를 정확한 placeholder와 `SKIP` mode로 선언한다.
  - `runSynchronously(suspendBridgeTimeout) { scheduler.triggerOnce() }` 및 기존 metrics/logging을 보존한다.
  - `CancellationException`은 다시 던지고 다른 `Exception`은 기존 warn 로그 후 반환한다.
- [x] **Step 3: bootstrap bean을 추가한다.**
  - `NotificationReminderSchedulingBootstrap`은 runner를 constructor injection하고 `@EventListener(ApplicationReadyEvent::class)`에서 `runner.poll()`만 호출한다.
  - runner와 같은 bean 안에서 직접 호출하지 않는다.
- [x] **Step 4: GREEN을 확인한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationSchedulingRunnersTest" --no-build-cache --console=plain`
  - Expected: runner body, annotation metadata, bootstrap delegation, exception/cancellation 테스트가 모두 PASS한다.

## Task 3: health monitor와 AOP callback adapter를 이식한다

**Files:**
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderHealth.kt`
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderAopMetricsRecorder.kt`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderHealthMonitorTest.kt`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderAopMetricsRecorderTest.kt`

- [x] **Step 1: single-elector health RED를 작성한다.**
  - mock `LeaderElector.state(lockName)`가 `LeaderState.occupied(lockName, LeaderLease(...))`, empty/non-leader, expired lease를 반환하는 fixture를 만든다.
  - 기존 success/failure window와 lease-risk window에 대해 UP/DEGRADED/DOWN, `leaderPresent`, `leaseAtRisk`가 동일하게 유지되는지 확인한다.
- [x] **Step 2: recorder RED를 작성한다.**
  - reminder lock에서 `onLockAcquired`가 monitor의 `recordAcquired()`를 한 번 호출하는지 확인한다.
  - 같은 lock의 `SkipReason.BACKEND_ERROR`만 `recordAcquisitionFailure()`를 호출하는지 확인한다.
  - `SkipReason.CONTENTION`, 다른 lock 이름, 다른 callback은 notification health 상태를 변경하지 않는지 확인한다.
- [x] **Step 3: health monitor를 `LeaderElector` 기반으로 구현한다.**
  - `LeaderState.leader` 단일 lease를 읽고 기존 bounded 상태 계산을 유지한다.
  - scheduler 허용/차단 결정을 health monitor에 넣지 않는다.
- [x] **Step 4: recorder adapter를 구현한다.**
  - `LeaderAopMetricsRecorder`를 구현하고 reminder lock 이름을 constructor default로 고정한다.
  - upstream `LeaderElectionOptions`, `Duration`, `SkipReason` callback signature를 그대로 사용한다.
- [x] **Step 5: GREEN을 확인한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderHealthMonitorTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderAopMetricsRecorderTest" --no-build-cache --console=plain`
  - Expected: 단일 lease 상태와 callback filtering 테스트가 모두 PASS한다.

## Task 4: auto-configuration과 optional dependency 조건을 교체한다

**Files:**
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt`

- [x] **Step 1: context RED를 새 bean 계약으로 전환한다.**
  - Redis `StatefulRedisConnection`과 upstream `lettuceLeaderElectionFactory`가 있는 context에서 `LeaderElector` state bean이 하나 생성되는지 확인한다.
  - `MeterRegistry`가 없어도 state elector는 생성되고 optional upstream AOP Micrometer recorder만 비활성화되는지 확인한다.
  - host-provided `LeaderElector`가 있으면 자체 state elector를 만들지 않고 동일 instance를 유지하는지 확인한다.
  - Redis/factory가 없는 classpath에서는 notification이 시작되고 notification-owned `LeaderElector`/health monitor가 생성되지 않는지 확인한다.
  - leader health enabled context가 `NotificationLeaderHealthMonitor`와 `NotificationLeaderAopMetricsRecorder`를 만들고, disabled context가 둘 다 만들지 않는지 확인한다.
  - runner가 있으면 `NotificationReminderSchedulingBootstrap`이 생성되는지 확인한다.
  - 기존 `LeaderGroupElector`, `LettuceLeaderGroupElector`, `InstrumentedLeaderGroupElector`, `shedlock.leader.*` bean/metric assertion을 제거한다.
  - upstream `LeaderAopFactoryAutoConfiguration`/`LeaderAopAutoConfiguration`이 먼저 factory/aspect를 등록한 뒤 notification configuration이 state elector와 recorder를 평가하는지 context configuration order를 확인하고, 필요한 경우 `@AutoConfigureAfter`로 순서를 고정한다.
- [x] **Step 2: notification auto-configuration을 구현한다.**
  - runner bean에서 group elector/health monitor provider를 제거한다.
  - `@ConditionalOnClass`/`@ConditionalOnBean`/`@ConditionalOnMissingBean`으로 `LeaderElectorFactory` 기반 state elector를 조건부 등록한다. factory bean 이름은 upstream `lettuceLeaderElectionFactory`를 `@Qualifier`로 명시한다.
  - health monitor condition을 `@ConditionalOnBean(LeaderElector::class)`로 바꾼다.
  - bootstrap과 `NotificationLeaderAopMetricsRecorder`를 각각 runner/health monitor 존재 조건으로 등록한다.
  - factory-created health elector는 injected Redis connection의 owner가 아니므로 notification bean이 connection을 닫지 않음을 명시하고, context close 시 shared connection lifecycle은 기존 owner bean에 남긴다.
  - 기존 Redis connection을 사용하는 notification concurrency wiring을 건드리지 않고 leader-specific group decorator만 제거한다.
- [x] **Step 3: context GREEN을 확인한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationAutoConfigurationTest" --no-build-cache --console=plain`
  - Expected: Redis/factory, host override, no Redis, health/recorder/bootstrap 조건 테스트가 모두 PASS한다.

## Task 5: upstream AOP metrics와 proxy/lifecycle 계약을 통합 검증한다

**Files:**
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderMicrometerTest.kt`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderScheduledIntegrationTest.kt`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/RedisLeaderScheduledLeaseIntegrationTest.kt`

- [x] **Step 1: Micrometer 테스트를 upstream 계약으로 교체한다.**
  - `SimpleMeterRegistry`와 upstream `MicrometerLeaderAopMetricsRecorder`/AOP auto-configuration을 사용해 `leader.aop.attempts`, `leader.aop.acquired`, `leader.aop.lock.not.acquired`, `leader.aop.task.failed`, `leader.aop.active` 중 실제 callback이 발생한 항목을 확인한다.
  - lock tag가 upstream sanitization(`redacted-lock`)을 따르고 reminder lock 원문이나 tenant/cardinality를 노출하지 않는지 확인한다.
  - `shedlock.leader.*` 이름이 생성되지 않는다는 negative assertion을 둔다.
  - 본문이 일반 예외를 내부에서 흡수하는 경우 `task.failed`를 잘못 기대하지 않고 notification outbox metric/logging 테스트로 분리한다.
- [x] **Step 2: 실제 Spring proxy RED를 작성한다.**
  - test configuration에서 fake `LeaderElectorFactory`와 `LeaderAopAutoConfiguration`을 연결하고 `NotificationReminderSchedulingRunner`를 Spring bean으로 만든다.
  - `AopTestUtils` 또는 실제 bean identity로 `getBean(NotificationReminderSchedulingRunner::class.java)`가 target과 다른 proxy임을 확인한다.
  - `@EnableScheduling` context의 `ScheduledTaskHolder`에서 reminder task가 하나 등록되고 annotation의 fixed-delay placeholder가 해석되는지 확인한다. reflection metadata만으로 scheduler 등록을 대체하지 않는다.
  - fake elector가 정상 획득을 반환하면 bootstrap 호출이 `triggerOnce()`를 정확히 한 번 실행하는지 확인한다.
  - contention이면 `triggerOnce()`가 호출되지 않고, backend exception이면 `failureMode=SKIP`이 예외를 외부로 내보내지 않는지 확인한다.
  - 직접 target 호출과 proxied bean 호출의 결과를 구분해 application-ready 경계가 AOP를 우회하지 않음을 고정한다.
- [x] **Step 3: cancellation, lease loss, shutdown 계약을 검증한다.**
  - fake elector가 `CancellationException`을 던지면 cancellation이 재전파되고 scheduled executor가 일반 exception으로 오인하지 않는지 확인한다.
  - Redis 8 singleton launcher에서 짧은 lease 동안 첫 action을 붙잡은 채 두 번째 single elector가 lease 만료 후 재취득하는지 확인한다. `bluetape4k-leader 0.5.0`의 Redis single elector는 `supportsAuditLeaderState=false`라 `state()`가 기본 `LeaderState.empty(lockName)`을 반환하므로, 해당 read-back을 실제 Redis lease 증거로 오인하지 않고 두 elector 재취득을 authoritative evidence로 사용한다. stale DB claim은 existing fencing 회귀 테스트로 차단된다.
  - Spring context close 뒤 `ScheduledTask`의 `ScheduledFuture`가 취소되어 새 작업을 시작하지 않고 AOP scheduling lifecycle이 정리되는지 확인한다.
  - Docker/Redis가 필요한 경우 `@Testcontainers`를 사용하지 않고 repository singleton launcher를 사용한다. 환경에서 Docker를 실행할 수 없으면 실패/skip을 성공으로 처리하지 않고 명령·원인을 기록한다.
- [x] **Step 4: 통합 GREEN을 확인한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderMicrometerTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderScheduledIntegrationTest" --no-build-cache --console=plain`
  - Expected: upstream metric namespace, proxy invocation, contention/backend/cancellation/shutdown 계약이 PASS한다.

## Task 6: 전체 검증과 문서화

**Files:**
- `docs/lessons/2026-08-22-issue-316-leader-scheduled.md`
- `build.gradle.kts`
- `src/consumerFixture/notification/kotlin/io/bluetape4k/clinic/appointment/consumer/NotificationApiConsumerFixture.kt`
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/AppointmentApiApplication.kt`
- 변경된 모든 Kotlin/Gradle/docs 파일

- [x] **Step 1: module targeted test를 실행한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationSchedulingRunnersTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderHealthMonitorTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderAopMetricsRecorderTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationAutoConfigurationTest" --no-build-cache --console=plain`
  - Expected: 지정된 회귀 테스트가 모두 PASS한다.
- [x] **Step 2: 전체 notification test와 build를 순서대로 실행한다.**
  - Run: `./gradlew :appointment-notification:test --no-build-cache --console=plain`
  - Run: `./gradlew :appointment-notification:build --no-build-cache --console=plain`
  - Expected: 각 명령이 `BUILD SUCCESSFUL`을 출력하고 실패·의도하지 않은 skip이 없다.
- [x] **Step 3: Kotlin/Spring checklist와 diff 검사를 적용한다.**
  - `transaction {}` 경계, cancellation 재전파, optional bean 조건, resource ownership, low-cardinality metric, `!!` 금지와 한국어 KDoc을 확인한다.
  - 이번 변경에는 새 blocking call, polling loop, retry, allocation-heavy collection을 추가하지 않았는지 확인하고, 기존 `runSynchronously` timeout 경계와 AOP task lifecycle을 유지한다.
  - 새 사용자 입력·secret·권한 경계를 추가하지 않았음을 확인하고, 고정 reminder lock 이름과 upstream lock-tag sanitization을 security evidence로 기록한다.
  - `README*`와 공개 설정 문서는 변경하지 않는다. public API, property key, 운영 명령이 바뀌지 않고 내부 scheduler wiring만 바뀌므로 한국어 KDoc과 lesson, PR body가 필요한 독자 문서 범위를 충족한다.
  - Run: `git diff --check`
  - Expected: whitespace 오류가 없다.
- [x] **Step 4: lesson 문서를 작성한다.**
  - `@LeaderScheduled`를 선택한 이유, `LeaderElector`와 기존 group elector의 타입 경계, upstream `leader.aop.*`와 notification metric의 책임 분리, proxy/bootstrap 필수성, DB fencing 보존, rollback 명령과 실행된 검증 결과를 기록한다.
- [x] **Step 5: 계획·문서 자연스러움 검사를 실행한다.**
  - Run: `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --series clinic-appointment docs/superpowers/plans/2026-08-22-issue-316-leader-scheduled-plan.md docs/lessons/2026-08-22-issue-316-leader-scheduled.md`
  - Expected: `PASS` 및 findings=0.
- [x] **Step 6: 공개 API 소비자 fixture 계약을 갱신하고 CI 동일 build를 재실행한다.**
  - `@LeaderScheduled`가 public runner API의 직접 type-use이므로 root API scope allowlist와 notification consumer fixture inventory에 `bluetape4k-leader-spring-boot`를 반영한다.
  - API runtime에서 upstream `LeaderElectionAutoConfiguration`이 선택적 Exposed backend를 eager import하지 않도록 애플리케이션 exclusion을 추가하고, notification 소유 Redis elector/AOP factory 경로는 유지한다.
  - Run: `./gradlew assertModuleConsumerFixtureApiVariants --no-configuration-cache --no-parallel --console=plain`
  - Run: `./gradlew compileModuleConsumerFixtures --no-configuration-cache --no-parallel --console=plain`
  - Run: `./gradlew build -x test -x :frontend:appointment-frontend:build --parallel --refresh-dependencies`
  - Run: `./gradlew :appointment-api:test --no-daemon -Dspring.profiles.active="test,test-postgresql" --no-build-cache --no-configuration-cache --console=plain`
  - Expected: API scope assertion, 세 consumer fixture compile, CI 동일 compile/build, 전체 API PostgreSQL 테스트가 모두 성공한다. configuration-cache warning은 기존 fixture 검증 task의 known warning으로 남기되 build failure가 아니어야 한다.

## Task 7: code review와 PR handoff

**Files/artifacts:**
- 변경된 모든 source/test/docs 파일
- `docs/reviews/2026-08-22-issue-316-leader-scheduled-code-review.md`
- PR body 임시 파일 `docs/reviews/2026-08-22-issue-316-leader-scheduled-pr-body.md`

- [x] **Step 1: implementation diff에 대해 six-perspective code review를 수행한다.**
  - performance, stability, security, operator/ops, developer/API, user/caller 관점에서 현재 diff와 fresh test output만 검토한다.
  - P0/P1은 merge 전 수정하고, P2/P3는 수정하거나 후속 Issue 번호와 근거를 기록한다.
  - performance/stability scan에서 `runSynchronously` cancellation, AOP lease cleanup, shutdown, metric cardinality, optional bean startup을 다시 읽는다.
- [x] **Step 2: lesson과 review evidence를 commit한다.**
  - lesson에는 실제 실행한 명령·결과, Docker/Redis 환경 여부, 미실행 검증 사유를 포함한다.
  - code review 문서에는 검토한 commit SHA, test/build 결과, acceptance traceability, P0/P1 count를 포함한다.
- [x] **Step 3: PR을 만들기 전 live Issue/branch 상태를 read-back한다.**
  - Run: `git status --short --branch`
  - Run: `git log --oneline --decorate -8`
  - Run: `gh issue view 316 --repo bluetape4k/clinic-appointment --json number,title,state,assignees,labels,milestone,url`
  - Expected: 현재 head가 `feat/issue-316-leader-scheduled`, base가 `develop`, Issue #316이 OPEN·`debop`·`1.4.0`·`enhancement`/`test` 상태임을 확인한다.
- [x] **Step 4: 한국어 PR을 생성하고 CI를 확인한다.**
  - target repository: `bluetape4k/clinic-appointment`
  - base: `develop`
  - head: `feat/issue-316-leader-scheduled`
  - PR title/body는 한국어로 작성하고 body에 `Closes #316`, 변경 범위, DoD Status 표, 실행 명령과 결과, known gap을 포함한다.
  - Run: `gh pr create --repo bluetape4k/clinic-appointment --base develop --head feat/issue-316-leader-scheduled --title "리마인더 복구 스케줄러를 @LeaderScheduled로 전환한다" --body-file docs/reviews/2026-08-22-issue-316-leader-scheduled-pr-body.md`
  - Run: `gh pr checks --repo bluetape4k/clinic-appointment --watch`
  - Expected: PR #376 body read-back과 required CI checks 15개가 모두 PASS한다.
- [ ] **Step 5: merge는 별도 승인 게이트로 남긴다.**
  - fresh `gh pr view --repo bluetape4k/clinic-appointment --json headRefName,baseRefName,statusCheckRollup,reviews,reviewDecision,mergeStateStatus,body,url`로 exact head/CI/review/mergeability를 다시 읽는다.
  - 사용자의 새 명시 승인 전에는 merge, auto-merge, tag, branch deletion을 실행하지 않는다.

## 수용 기준 추적표

| 설계 수용 기준 | 구현·검증 task | 증거 |
|---|---|---|
| 수동 `@Scheduled`/`runIfLeader` 제거와 `@LeaderScheduled` 단일 경계 | Task 2 | annotation metadata 테스트, runner diff |
| application-ready 첫 실행이 proxied runner를 통과 | Task 2, Task 5 | bootstrap delegation 및 proxy identity/invocation 테스트 |
| contention에서 단일 action, DB claim/fencing 유지 | Task 5 | fake elector contention 테스트, existing scheduler DB contract regression |
| backend 오류·본문 오류·취소·shutdown 계약 | Task 2, Task 5 | runner, AOP, lifecycle 테스트 |
| lease 상태와 bounded health 기록 유지 | Task 3, Task 4 | `LeaderState` health 테스트, conditional context 테스트 |
| `leader.aop.*` 사용 및 `shedlock.leader.*` 중복 제거 | Task 3, Task 5 | recorder callback 및 metric namespace positive/negative assertion |
| optional dependency/classpath와 host elector 조건 | Task 4 | `ApplicationContextRunner` matrix |
| API runtime의 선택적 backend auto-configuration 호환성 | Task 6 | API PostgreSQL 전체 테스트와 application exclusion |
| module test/build 및 diff clean | Task 6 | Gradle output와 `git diff --check` |
| 한국어 계획·lesson·delivery artifact | Task 6 및 delivery gate | naturalness audit, review/PR read-back |

## 실패·복귀·재실행 지점

- `bluetape4k-leader-spring-boot` artifact가 BOM에서 해석되지 않으면 alias와 `api` dependency를 되돌리고, upstream version/catalog 상태를 확인한 뒤 기존 runner를 유지한다. 해석되지 않은 상태에서 source migration을 진행하지 않는다.
- Spring proxy test가 target 직접 호출만 증명하거나 `ApplicationReadyEvent`가 proxy를 통과하지 못하면 해당 구현은 PENDING이다. same-bean self-invocation이나 수동 `runIfLeader` fallback으로 통과시키지 않는다.
- Redis/lease 통합 테스트가 Docker bind-mount 오류 또는 환경 skip으로 끝나면 성공으로 표시하지 않는다. `colima status`, `docker context show`, `docker info`를 확인하고 실패 원인과 대체 factory/state 증거를 lesson과 최종 DoD에 남긴다.
- 기존 DB claim/fencing 또는 deprecated direct-call 테스트가 깨지면 leader annotation만 되돌리고 DB/compatibility surface는 원복하지 않는다.
- 구현 중 언제든 마지막 녹색 checkpoint(설계·계획 문서와 source migration 직전) 이후의 구현 commit SHA를 지정한 `git revert`로 복귀할 수 있다. `git reset --hard`는 사용하지 않는다.
- 각 task의 RED/ GREEN 명령은 task 경계에서 재실행 가능하며, 다음 task는 선행 GREEN 증거가 있을 때만 시작한다.

## 계획 승인 및 구현 게이트

- [x] SPW-01: 계획이 설계 문서의 목표·제외 범위·수용 기준을 모두 추적한다.
- [x] SPW-02: 모든 구현 단계가 정확한 파일, 테스트, 실행 명령, 기대 결과를 가진다.
- [x] SPW-03: proxy/AOP, optional classpath, health state, metric namespace, DB fencing의 위험을 별도 검증한다.
- [x] SPW-04: 실패 시 복귀·재실행 절차가 명시되어 있고 placeholder가 없다.
- [x] SPW-05: 사용자 승인 전에는 Kotlin/Gradle source implementation을 시작하지 않는다.

계획 문서 자체 검토와 6-perspective 계획 리뷰에서 P0/P1이 0임을 확인하고 사용자 승인을 받은 뒤 Task 1부터 순서대로 실행한다. 구현 완료 후에는 별도 six-perspective code review, lesson, PR/CI 검증을 거친 뒤 merge에는 새 명시 승인을 요구한다.

## 커밋 checkpoint

- 계획 문서: Lore protocol 한국어 커밋 1개.
- runner/bootstrap: 테스트 RED와 GREEN을 확인한 뒤 작은 구현 커밋 1개.
- health/recorder: 테스트 RED와 GREEN을 확인한 뒤 작은 구현 커밋 1개.
- auto-configuration/dependency: context RED와 GREEN을 확인한 뒤 작은 구현 커밋 1개.
- metrics/integration: AOP/proxy/lifecycle GREEN 뒤 작은 구현 커밋 1개.
- lesson 및 최종 검증: 전체 build와 review 수정 후 하나의 delivery 커밋.

각 커밋은 다음 Lore trailer를 포함한다.

```
Constraint: upstream @LeaderScheduled와 기존 DB fencing 계약을 유지한다
Rejected: 수동 runIfLeader fallback | AOP proxy 경계를 다시 중복시키므로 제외
Confidence: high
Scope-risk: moderate
Directive: reminder lock의 business correctness를 leader lease에 위임하지 않는다
Tested: 실제 실행한 정확한 Gradle 명령을 기록한다
Not-tested: 실행하지 못한 환경 의존 검증과 원인을 기록한다
```
