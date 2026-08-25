# Issue #317: leader-aware scheduled task 정책 외부화 설계

## 문제

`NotificationReminderSchedulingRunner.poll()`은 현재 `@LeaderScheduled`에
lock name, Spring fixed delay, failure mode를 직접 선언한다. 운영 환경에서
이 값을 바꾸려면 코드를 다시 빌드해야 하고, selector·backend·lease 정책이
runner 구현과 실행 환경 설정에 나뉜다.

Issue #317은 기존 `@Scheduled` 작업을 YAML 정책으로 감싸는
`bluetape4k-leader` 기능을 notification reminder recovery에 적용해 이 계약을
설정으로 단일화하는 작업이다. 동적 scheduler 재구성이나 업무 로직의 leader
의존 확대는 이 설계에 포함하지 않는다.

## 현재 근거

- Clinic 현재 구현: `appointment-notification/.../NotificationSchedulingRunners.kt`
  의 `NotificationReminderSchedulingRunner.poll()`이 `@LeaderScheduled`를
  사용하고 lock name `appointment-reminder-recovery`, failure mode `SKIP`,
  `clinic.notification.worker.reminder-recovery-interval`을 선언한다.
- Clinic 현재 테스트: `NotificationSchedulingRunnersTest`와
  `NotificationLeaderScheduledIntegrationTest`가 AOP proxy, fixed delay,
  contention, backend failure, cancellation, scheduler lifecycle을 검증한다.
- Upstream 기능: [bluetape4k-leader Issue #603](https://github.com/bluetape4k/bluetape4k-leader/issues/603)이
  닫혔고, 구현 PR [#761](https://github.com/bluetape4k/bluetape4k-leader/pull/761)이
  merge되었다. `LeaderScheduledPolicyProperties`는 `selector`, `name`,
  `wait-time`, `lease-time`, `min-lease-time`, `bean`, `auto-extend`,
  `stream-bounded`, `failure-mode`를 제공한다.
- 현재 clinic lockfile의 leader 의존성은 `0.5.0`이며 Issue #603 기능은 해당
  release 이후 merge되었다. 2026-08-25에 Central Snapshots에서 네 leader
  모듈의 동일한 immutable timestamp `1.0.0-20260824.195548-7`을 확인했다.
- 해당 immutable timestamp artifact의 Gradle module variant는 `org.gradle.jvm.version=25`만
  제공한다. 현재 clinic Gradle toolchain이 21로 고정돼 있어 timestamp artifact를
  resolution하면 JVM 25 호환성 오류가 발생하며, CI도 이미 `JAVA_VERSION=25`를
  사용한다. 따라서 이 기능의 consumer compile/runtime 경계를 Java 25로
  정렬해야 한다.

## 선택한 접근

### A. upstream property policy를 그대로 적용한다 (권장)

1. reminder runner의 명시적 `@LeaderScheduled`를 제거하고 기존 fixed delay만
   가진 plain `@Scheduled`로 둔다.
2. `bluetape4k.leader.scheduling.enabled=true`와 exact selector
   `notificationReminderSchedulingRunner#poll`을 애플리케이션 설정에 선언한다.
3. lock name, wait/lease/min-lease, backend bean, auto-extend, failure mode는
   `LeaderScheduledPolicyProperties`로 바인딩하고, upstream
   `LeaderScheduledPolicyBeanPostProcessor`와 AOP가 기존 Spring scheduler
   lifecycle을 유지하면서 leader 경계를 적용하도록 한다.
4. notification runner bean은 leader factory와 enabled policy registry가 있을
   때만 생성하고, reminder selector가 설정에 없으면 bean 생성 단계에서
   시작을 거부한다. 정책을 끄거나 backend를 제공하지 않는 context에서 plain
   scheduled 작업이 leader 보호 없이 실행되는 우회를 허용하지 않는다.
5. 기존 `AppointmentReminderScheduler`의 DB claim/fence가 실행 정합성의
   최종 기준임을 코드 문서와 README에 명시한다. leader lock은 중복 tick을
   줄이는 실행 경계이지 DB 상태 변경의 권위가 아니다.

장점은 upstream의 immutable registry, selector 검증, explicit annotation
우선순위, observation lifecycle을 재사용한다는 점이다. 새 정책 모델이나
전역 scheduler를 clinic에 복제하지 않는다.

### B. `@LeaderScheduled`를 유지하고 clinic 전용 설정 adapter를 만든다

기존 annotation을 유지하면서 `NotificationProperties`에서 leader 옵션을
읽어 annotation/AOP 입력을 조립한다. 현재 upstream 계약에서는 explicit
`@LeaderScheduled`가 matching property policy보다 우선하므로 YAML 값이
annotation에 덮이지 않는다. policy 모델과 우선순위를 다시 구현해야 하므로
채택하지 않는다.

### C. upstream stable release까지 구현을 보류한다

stable artifact만 사용하면 사전 릴리스 공급망 위험은 없다. 하지만 기능은 이미
upstream develop에 merge되었고 Central Snapshots에 동일 timestamp artifact가
존재한다. Issue #317을 다시 upstream release 대기로 묶으면 현재 운영 설정
계약을 검증할 수 없으므로 채택하지 않는다. timestamp pin과 rollback 절차를
catalog/문서/PR에 명시해 위험을 제한한다.

## 설정 계약

운영 기본 profile에는 다음 정책을 명시한다. 실제 backend bean 이름은 현재
clinic auto-configuration이 등록하는 `lettuceLeaderElectionFactory`를
사용한다.

```yaml
bluetape4k:
  leader:
    scheduling:
      enabled: true
      policies:
        - selector: "notificationReminderSchedulingRunner#poll"
          name: "appointment-reminder-recovery"
          wait-time: 0s
          lease-time: 60s
          min-lease-time: 5s
          bean: "lettuceLeaderElectionFactory"
          auto-extend: false
          stream-bounded: false
          failure-mode: SKIP
```

`clinic.notification.worker.reminder-recovery-interval`은 작업 실행 주기를
결정하므로 `@Scheduled`에 남긴다. `lease-time`은 reminder scan의 단일 실행
경계를 보호하고, worker의 DB lease/fencing 계약을 대체하지 않는다.

정책 기능의 upstream 기본값은 `enabled=false`다. 따라서 공통 application
설정에서 notification worker를 실제로 켜는 profile은 policy를 함께 켜야
한다. 정책을 끄는 profile은 reminder runner를 생성하지 않는 명시적 rollback
상태로 문서화한다. selector는 exact `beanName#methodName`만 허용하며
wildcard·regex·overload·공백 selector는 사용하지 않는다.

## 실행 경계와 실패 처리

- **설정 누락:** policy enabled인데 reminder selector가 없으면 startup에서
  `IllegalStateException`으로 거부한다. policy disabled 또는 leader factory
  부재에서는 reminder runner를 생성하지 않아 plain scheduled 우회를 막는다.
- **selector 오류:** upstream registry가 빈 selector, `#` 개수 오류, 중복,
  unmatched, overload를 startup에서 거부한다.
- **duration/lease 오류:** upstream validator가 음수·0 lease, min lease 초과,
  auto-extension/stream 조합, SpEL name 문제와 backend bean 문제를 startup에서
  거부한다. clinic worker의 기존 `leaseDuration` 검증도 그대로 유지한다.
- **leader backend 장애:** `SKIP` policy로 현재 tick을 건너뛰고 기존 runner의
  cancellation 전파와 일반 예외 흡수 계약을 유지한다. 다음 tick은 Spring
  scheduler가 호출한다.
- **짧은 leader lease:** reminder policy의 `lease-time`은 명시적으로 설정하고
  `NotificationProperties.worker.suspendBridgeTimeout` 이상이어야 한다. 이
  경계보다 짧으면 bounded recovery 호출이 끝나기 전에 leader ownership이
  만료될 수 있으므로 startup에서 거부한다.
- **DB claim/fence:** leader lock을 획득해도 DB claim/fence가 실패하면 작업은
  저장된 outbox 상태를 변경하지 않는다. 중복 실행 방지와 최종 상태 정합성의
  책임을 분리한다.

## 호환성과 rollback

- Spring `@Scheduled` fixed delay, ready bootstrap, metrics, cancellation,
  observation을 유지한다.
- 테스트 context는 policy enabled, exact selector, test factory bean을
  명시한다. 기존 annotation reflection assertion은 property binding과
  `ScheduledTaskHolder`/AOP integration assertion으로 대체한다.
- `bluetape4k-leader-*` 직접 버전은 timestamp artifact를 임시로 고정하고
  lockfile/verification metadata를 함께 갱신한다. upstream stable 1.0.x가
  릴리스되면 이 예외를 제거하고 BOM 기준으로 되돌리는 follow-up을 Issue
  #317에 남긴다.
- timestamp variant를 읽을 수 있도록 Gradle Java/Kotlin toolchain과
  `appointment-api` Gatling/consumer fixture compile 경계를 Java 25로
  정렬한다. Java 21 variant를 고정하면 Java 25 project dependency를 읽을 수
  없어 governance resolution이 실패하므로, 저장소의 명시된 Java 25 계약을
  유지한다.
- 긴급 rollback은 `bluetape4k.leader.scheduling.enabled=false`로 policy와
  reminder runner를 중지하거나, timestamp pin을 기존 `0.5.0`으로 되돌리는
  것이다. `@LeaderScheduled`를 되살려 설정과 코드를 동시에 두 권위로
  만들지는 않는다.

## 범위 밖

- 모든 `@Scheduled` 작업의 전역 자동 변환
- runtime policy reload 또는 scheduler 재구성
- 새로운 leader backend, 새로운 scheduler/executor/observation 구현
- 업무 서비스가 leader 상태를 직접 해석하는 기능
- DB claim/fence 의미론 변경

## 검증 acceptance criteria

1. 설정만 변경해 reminder selector의 lock name, wait/lease/min-lease, backend,
   auto-extend, failure mode가 적용된다.
2. default-off/누락 policy와 leader factory 부재에서 leader 보호 없는 reminder
   scheduled 실행이 발생하지 않는다.
3. 빈 selector, unmatched/중복 selector, 잘못된 duration, min lease 초과,
   `lease-time < suspendBridgeTimeout`, backend bean 누락, stream/auto-extend
   오류가 startup에서 거부된다.
4. leader contention은 scheduler 본문을 실행하지 않고, backend 오류는
   `SKIP`으로 흡수하며, cancellation은 전파한다.
5. Spring `ScheduledTaskHolder` fixed delay, AOP proxy, ready bootstrap,
   context close lifecycle이 유지된다.
6. DB claim/fence가 실행 정합성의 최종 기준이라는 README/KDoc/lesson 설명과
   profile별 설정 예시가 존재한다.
7. 영향 모듈 테스트, 정적검사, dependency lock/verification, `git diff --check`,
   CI가 통과한다.

## DoD

- 설계/구현/리뷰/lesson 문서가 현재 upstream source와 clinic source를
  가리킨다.
- Kotlin/Spring/test checklist의 적용 가능한 행이 모두 PASS이고 P0/P1은
  0건이다.
- PR의 정확한 head에서 CI와 inline review를 다시 확인한다.
- fresh merge approval 이후에만 병합하고, merge SHA·local `develop` parity·
  worktree/branch cleanup을 증명한다.
