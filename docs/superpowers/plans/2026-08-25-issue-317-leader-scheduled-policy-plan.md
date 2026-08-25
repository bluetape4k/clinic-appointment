# Issue #317 leader-aware scheduled task 정책 외부화 구현 계획

> **For agentic workers:** 이 계획은 현재 세션의 inline execution으로
> 수행한다. 각 단계의 RED/GREEN 증거와 체크포인트를 기록하고, 구현 중
> 설계 계약이 바뀌면 설계 문서와 review를 먼저 갱신한다.

**Goal:** `NotificationReminderSchedulingRunner`의 leader 실행 정책을
`bluetape4k.leader.scheduling` YAML로 외부화하고 설정 누락·잘못된 lease·backend
조건에서 leader 보호 없는 scheduled 실행을 차단한다.

**Architecture:** Spring이 소유한 plain `@Scheduled` fixed delay는 유지하고,
upstream `LeaderScheduledPolicyProperties`/registry/BPP/AOP가 exact selector의
leader metadata를 적용한다. Clinic auto-configuration은 leader factory,
enabled policy registry, reminder selector를 runner 생성 경계로 사용한다.
새 scheduler·policy model·AOP를 만들지 않으며 DB claim/fence는 최종 상태 변경
권위로 남긴다.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, `bluetape4k-leader` immutable
timestamp `1.0.0-20260824.195548-7`, Spring `ApplicationContextRunner`, JUnit 5,
MockK, bluetape4k assertions, Gradle dependency locking/verification.

---

## 파일 책임과 변경 표면

| 파일 | 책임 |
|---|---|
| `build.gradle.kts` | Java/Kotlin toolchain을 Java 25 snapshot consumer 경계와 정렬하고 기존 Gatling 21 release 예외는 유지 |
| `gradle/libs.versions.toml` | upstream leader scheduled policy timestamp를 임시 catalog override로 고정 |
| `gradle.lockfile`, `appointment-notification/gradle.lockfile`, `gradle/verification-metadata.xml` | 새 artifact와 transitive dependency의 생성된 lock/hash |
| `appointment-notification/src/main/kotlin/.../NotificationSchedulingRunners.kt` | reminder 실행 주기를 plain `@Scheduled`로 유지하고 leader annotation 제거 |
| `appointment-notification/src/main/kotlin/.../NotificationAutoConfiguration.kt` | factory·policy registry·selector 조건과 startup guard |
| `appointment-notification/src/test/kotlin/.../NotificationSchedulingRunnersTest.kt` | annotation/호출 경계의 단위 검증 |
| `appointment-notification/src/test/kotlin/.../NotificationLeaderScheduledIntegrationTest.kt` | upstream property policy, AOP proxy, scheduler lifecycle, contention/backend/cancellation 검증 |
| `appointment-notification/src/test/kotlin/.../NotificationAutoConfigurationTest.kt` | policy enabled/disabled, factory 부재, selector 누락의 bean 경계 검증 |
| `appointment-api/src/main/resources/application.yml` | 운영 기본 profile의 exact reminder policy |
| `appointment-api/src/test/resources/application-test.yml` | Redis/leader가 없는 API test profile의 안전한 policy 비활성화 |
| `appointment-notification/README.md`, `README.ko.md` | Korean 설정 예시, 필드 의미, rollback, DB claim/fence 책임 경계 |
| `docs/review/2026-08-25-issue-317-leader-scheduled-policy-code-review.md` | 최종 inline six-lens review와 P0/P1 수렴 |
| `docs/lessons/2026-08-25-issue-317-leader-scheduled-policy.md` | timestamp dependency와 unguarded scheduler 방지의 재사용 guard |

## 의존성 순서와 체크포인트

### Task 1: upstream artifact를 catalog와 lock에 고정

**Files:** `build.gradle.kts`, `gradle/libs.versions.toml`, generated lock/verification files.

- [x] **Step 1: Java 25 consumer toolchain을 먼저 고정한다.**

  snapshot의 `apiElements`/`runtimeElements`가 Java 25 variant만 제공하므로
  root `java.toolchain`과 Kotlin `jvmToolchain`을 25로 맞춘다. `appointment-api`
  의 Gatling compile task가 유지하는 `options.release=21`과 `JvmTarget.JVM_21`
  예외는 그대로 둔다. 이 단계의 RED 증거는 Java 21 toolchain에서 snapshot을
  resolution할 때 발생한 `only compatible with JVM runtime version 25 or newer`
  오류다.

- [x] **Step 2: catalog에 timestamp version을 추가한다.**

```toml
[versions]
bluetape4k-leader-scheduled-policy = "1.0.0-20260824.195548-7"

[libraries]
bluetape4k-leader-core = { module = "io.github.bluetape4k.leader:bluetape4k-leader-core", version.ref = "bluetape4k-leader-scheduled-policy" }
bluetape4k-leader = { module = "io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce", version.ref = "bluetape4k-leader-scheduled-policy" }
bluetape4k-leader-micrometer = { module = "io.github.bluetape4k.leader:bluetape4k-leader-micrometer", version.ref = "bluetape4k-leader-scheduled-policy" }
bluetape4k-leader-spring-boot = { module = "io.github.bluetape4k.leader:bluetape4k-leader-spring-boot", version.ref = "bluetape4k-leader-scheduled-policy" }
```

  `bluetape4k-dependencies` BOM 전체를 사전 릴리스로 올리지 않는다. 네 leader
  artifact의 Central metadata timestamp가 동일한지 다시 확인하고, 다른
  timestamp면 implementation 전에 계획 evidence와 catalog 값을 함께 갱신한다.
  공식 BOM의 leader `0.5.0` constraint가 core를 되돌리지 않는지도
  `dependencyInsight`로 확인한다.

- [x] **Step 3: dependency resolution이 새 policy classes를 선택하는지 확인한다.**

  Run:

```bash
./gradlew :appointment-notification:dependencyInsight \
  --dependency io.github.bluetape4k.leader \
  --configuration testRuntimeClasspath
```

  Expected: leader core/redis/spring-boot/micrometer가
  `1.0.0-20260824.195548-7`로 해석되고 `LeaderScheduledPolicyProperties`와
  `LeaderScheduledPolicyRegistry`가 classpath에 있다.

- [x] **Step 4: lock과 verification metadata를 Gradle로 갱신한다.**

```bash
./gradlew :appointment-notification:dependencies --write-locks
./gradlew dependencies --write-locks
./gradlew --write-verification-metadata sha256 :appointment-notification:compileKotlin
```

  Expected: generated files에 leader timestamp와 필요한 transitive hash만
  추가되고 unrelated dependency version은 변하지 않는다. `git diff --check`
  후 lock diff를 읽어 BOM 전체 drift가 없음을 확인한다. Java 25 toolchain
  변경으로 생성되는 unrelated variant drift가 있으면 원인을 분리해 기록하고
  필요 최소 범위만 유지한다. Gradle 9.7.1 writer가 timestamp와 normalized
  snapshot component를 중복 키로 처리해 자동 verification write가 실패했으므로,
  Central Snapshots에서 다시 계산한 네 artifact·module SHA-256만
  `gradle/verification-metadata.xml`에 수동으로 추가하고 compile verification으로
  read-back한다.

### Task 2: property policy와 안전한 bean 경계의 RED 테스트를 먼저 작성

**Files:** `NotificationSchedulingRunnersTest.kt`,
`NotificationLeaderScheduledIntegrationTest.kt`, `NotificationAutoConfigurationTest.kt`.

- [ ] **Step 1: reflection assertion을 plain `@Scheduled` 계약으로 바꾼다.**

```kotlin
@Test
fun `reminder poll은 fixed delay Scheduled만 선언하고 leader policy는 외부 설정으로 위임한다`() {
    val method = NotificationReminderSchedulingRunner::class.java.getDeclaredMethod("poll")
    val scheduled = requireNotNull(AnnotatedElementUtils.findMergedAnnotation(method, Scheduled::class.java))

    scheduled.fixedDelayString shouldBeEqualTo "\${clinic.notification.worker.reminder-recovery-interval:PT1H}"
    AnnotatedElementUtils.findMergedAnnotation(method, LeaderScheduled::class.java) shouldBeEqualTo null
}
```

  `LeaderScheduled` import와 기존 `LeaderAspectFailureMode` assertion을 제거하고
  Spring annotation import를 추가한다. 이 테스트는 구현 전 RED가 되어야 한다.

- [ ] **Step 2: integration context에 upstream policy auto-configuration과 exact YAML을 넣는다.**

  `NotificationLeaderScheduledIntegrationTest.context()`의
  `AutoConfigurations`에 `LeaderScheduledPolicyAutoConfiguration`을 추가하고
  다음 property를 설정한다.

```kotlin
"bluetape4k.leader.scheduling.enabled=true",
"bluetape4k.leader.scheduling.policies[0].selector=notificationReminderSchedulingRunner#poll",
"bluetape4k.leader.scheduling.policies[0].name=appointment-reminder-recovery",
"bluetape4k.leader.scheduling.policies[0].wait-time=0s",
"bluetape4k.leader.scheduling.policies[0].lease-time=60s",
"bluetape4k.leader.scheduling.policies[0].min-lease-time=5s",
"bluetape4k.leader.scheduling.policies[0].bean=localLeaderElectionFactory",
"bluetape4k.leader.scheduling.policies[0].failure-mode=SKIP",
```

  Expected RED: 현재 `@LeaderScheduled`가 explicit annotation 우선순위를
  가지므로 property registry가 runner를 대체하지 않거나, 새 dependency가
  없으면 compile failure가 난다.

- [ ] **Step 3: auto-configuration negative cases를 추가한다.**

  `NotificationAutoConfigurationTest`에 다음 세 테스트를 추가한다.

  1. `bluetape4k.leader.scheduling.enabled`가 없으면 reminder runner와
     bootstrap이 0개다.
  2. enabled=true지만 `LeaderElectorFactory` bean이 없으면 runner가 0개이며
     plain scheduled 작업이 등록되지 않는다.
  3. enabled=true, factory, reminder ports는 있지만 exact selector가 없으면
     `startupFailure`가 `IllegalStateException`을 원인으로 가진다.
  4. selector는 있지만 `lease-time=10s`이고
     `clinic.notification.worker.suspend-bridge-timeout=30s`이면
     `startupFailure`가 safety bound 위반을 포함한다.

  테스트 context에 `LeaderScheduledPolicyAutoConfiguration`을 등록하고,
  positive case에는 `localLeaderElectionFactory` mock과 selector property를
  명시한다. bluetape4k `assertFailsWith`/`shouldBeEqualTo`만 사용한다.

### Task 3: 최소 production 변경으로 property policy를 적용

**Files:** `NotificationSchedulingRunners.kt`, `NotificationAutoConfiguration.kt`.

- [ ] **Step 1: runner에서 explicit leader annotation만 제거한다.**

```kotlin
import org.springframework.scheduling.annotation.Scheduled

@Scheduled(fixedDelayString = "\${clinic.notification.worker.reminder-recovery-interval:PT1H}")
open fun poll() { /* 기존 bounded recovery 본문을 그대로 유지 */ }
```

  `CancellationException` 재전파, 일반 예외 warning, metrics와
  `NotificationReminderSchedulingBootstrap`은 변경하지 않는다.

- [ ] **Step 2: auto-configuration에 upstream policy 조건을 추가한다.**

  다음 import를 사용한다.

```kotlin
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyProperties
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistry
```

  `notificationReminderSchedulingRunner` bean에
  `@ConditionalOnBean(LeaderElectorFactory::class)`,
  `@ConditionalOnBean(LeaderScheduledPolicyRegistry::class)`,
  `@ConditionalOnProperty(prefix = "bluetape4k.leader.scheduling", name = ["enabled"], havingValue = "true")`
  를 추가한다. 이 조건은 property disabled/factory 부재에서 unguarded
  `@Scheduled` bean이 생기는 경로를 차단한다.

- [ ] **Step 3: exact selector 누락을 bean creation에서 fail-fast한다.**

  runner factory에 `LeaderScheduledPolicyProperties`를 주입하고 아래 guard를
  적용한다.

```kotlin
private const val REMINDER_RECOVERY_POLICY_SELECTOR =
    "notificationReminderSchedulingRunner#poll"

private fun requireReminderRecoveryPolicy(
    properties: LeaderScheduledPolicyProperties,
    suspendBridgeTimeout: Duration,
) {
    val policy = properties.policies.firstOrNull { it.selector == REMINDER_RECOVERY_POLICY_SELECTOR }
    check(policy != null) {
        "Missing scheduled leader policy: $REMINDER_RECOVERY_POLICY_SELECTOR"
    }
    val leaseTime = checkNotNull(policy.leaseTime) {
        "Scheduled leader policy '$REMINDER_RECOVERY_POLICY_SELECTOR' must set lease-time"
    }
    check(leaseTime >= suspendBridgeTimeout) {
        "Scheduled leader policy '$REMINDER_RECOVERY_POLICY_SELECTOR' lease-time must be >= " +
            "worker.suspendBridgeTimeout"
    }
}
```

  `NotificationProperties.worker.validate()` 결과의
  `suspendBridgeTimeout`을 guard에 전달해 `lease-time`이 bounded recovery
  호출보다 짧지 않은지 함께 확인한다. guard는 upstream policy 모델/registry를
  복제하지 않고 clinic이 반드시 leader-protected 상태여야 하는 one-bean
  invariant만 확인한다. upstream BPP가
  중복/unmatched/overload/duration/backend/stream 조합을 계속 검증하도록
  policy properties를 그대로 전달한다.

- [ ] **Step 4: RED 테스트가 GREEN으로 바뀌는지 확인한다.**

```bash
./gradlew :appointment-notification:test \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationSchedulingRunnersTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationLeaderScheduledIntegrationTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationAutoConfigurationTest' \
  --no-build-cache
```

  Expected: targeted tests PASS, integration context는 proxy target class가
  `NotificationReminderSchedulingRunner`이고 `ScheduledTaskHolder` fixed delay가
  `PT1H`이며, ready bootstrap에서 elected path만 scheduler를 호출한다.

### Task 4: 운영/test profile과 Korean 문서를 동기화

**Files:** `appointment-api/src/main/resources/application.yml`,
`appointment-api/src/test/resources/application-test.yml`,
`appointment-notification/README.md`, `README.ko.md`.

- [ ] **Step 1: 운영 기본 profile에 exact policy를 추가한다.**

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

  `clinic.notification.worker.reminder-recovery-interval`는 scheduler 주기이며
  leader policy의 lease와 혼동하지 않는다는 주석을 둔다.

- [ ] **Step 2: Redis 없는 API test profile을 명시적으로 끈다.**

```yaml
bluetape4k:
  leader:
    scheduling:
      enabled: false
```

  test profile에서는 runner가 생성되지 않는 것이 의도된 안전 상태임을 문서에
  남긴다. production profile에 설정을 덧붙이지 않고 test에서만 덮어쓴다.

- [ ] **Step 3: 두 README의 설정 section을 동일하게 갱신한다.**

  기존 `clinic.notification.worker` 예시 뒤에 `bluetape4k.leader.scheduling`
  block, 필드별 기본값/범위와 보안·cardinality 주의사항, exact selector 규칙,
  profile별 enable/disable, rollback 명령을 Korean으로 추가한다. 다음 경계를
  반드시 명시한다.

  - lock은 tick 중복을 줄이는 실행 경계다.
  - DB claim/fence와 provider idempotency가 최종 상태 변경의 기준이다.
  - policy는 startup-only이며 wildcard·regex·runtime reload를 지원하지 않는다.
  - stable leader 1.0.x 릴리스 후 timestamp override를 제거한다.

### Task 5: 전체 영향 범위 검증과 성능/안정성 확인

**Files:** 변경된 모든 Kotlin/YAML/README/catalog 파일.

- [ ] **Step 1: affected module test를 순서대로 실행한다.**

```bash
./gradlew :appointment-notification:test --no-build-cache
./gradlew :appointment-notification:detekt
```

  Testcontainers/Redis 통합 테스트는 다른 Gradle process와 병렬 실행하지 않는다.
  실패 시 retry로 덮지 말고 raw stack trace에서 lifecycle·backend·timing 원인을
  분리한 뒤 구현 단계로 돌아간다.

- [ ] **Step 2: 정책 startup negative matrix를 확인한다.**

  targeted integration context를 순서대로 실행해 다음이 모두 fail-fast인지
  확인한다: 빈 selector, unmatched selector, 중복 selector, `lease-time=0s`,
  `lease-time < suspendBridgeTimeout`, `min-lease-time > lease-time`, 존재하지
  않는 backend bean, invalid name expression. upstream policy test가 직접 증명하는 항목은 dependency artifact
  source/test evidence로 기록하고 clinic integration은 적용 경계만 검증한다.

- [ ] **Step 3: Kotlin/Spring checklist와 diff를 통과시킨다.**

```bash
git diff --check
rg -n "!!|runCatching|System\.(out|err)|println|@LeaderScheduled|import io\.bluetape4k\.leader\.spring\.scheduling\.LeaderScheduled" \
  appointment-notification/src/main/kotlin appointment-notification/src/test/kotlin
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  appointment-notification/README.md appointment-notification/README.ko.md \
  docs/superpowers/specs/2026-08-25-issue-317-leader-scheduled-policy-design.md \
  docs/superpowers/plans/2026-08-25-issue-317-leader-scheduled-policy-plan.md
```

  `LeaderScheduled` 검색 결과는 변경 전 stale test/comment가 아닌 이상 0이며,
  기존 unrelated `RedisLeaderScheduledLeaseIntegrationTest` 파일이 있으면
  issue scope에 맞춰 plain scheduled policy 용어로만 갱신한다. Kotlin final
  checklist KT-FIN-01~11과 Spring/testing checklist를 evidence table에 기록한다.

- [ ] **Step 4: dependency governance와 module build를 확인한다.**

```bash
./gradlew :appointment-notification:build
./gradlew verifyDependencyGovernance
```

  Expected: build/lock/verification이 동일 timestamp를 사용하고,
  `appointment-notification`의 API consumer fixture와 root governance가
  통과한다.

### Task 6: 최종 inline review와 durable lesson을 커밋

**Files:** `docs/review/2026-08-25-issue-317-leader-scheduled-policy-code-review.md`,
`docs/lessons/2026-08-25-issue-317-leader-scheduled-policy.md`.

- [ ] **Step 1: current diff를 여섯 관점으로 inline review한다.**

  성능(매 tick reflection/scheduler 생성 없음), 안정성(cancellation·backend·close),
  보안(backend/SpEL/secret/cardinality), 운영(rollback/health/release),
  개발/API(Kotlin/Spring 조건/재사용), 사용자/호출자(YAML 오용·예시)를 각각
  파일/라인 evidence로 기록한다. P0/P1은 수정 후 affected test와 관점만
  재실행하며, P2/P3은 수정하거나 후속 Issue로 연결한다.

- [ ] **Step 2: lesson을 Korean으로 작성한다.**

  timestamp dependency를 임시로 고정해야 했던 이유, explicit annotation을
  유지했을 때 property가 무시되는 upstream precedence, policy disabled에서
  unguarded scheduled 실행을 막은 조건, 테스트/rollback 결과와 향후 stable
  release 전환 guard를 기록한다.

- [ ] **Step 3: writer gate와 final commit을 확인한다.**

  설계·계획·review·lesson 각각에 SPW-01~05를 적용하고 Korean terminology
  audit를 재실행한다. P0=0/P1=0, `git diff --check`, targeted/full test,
  dependency governance가 모두 PASS일 때만 다음 Lore commit을 만든다.

```bash
git add gradle/libs.versions.toml gradle.lockfile gradle/verification-metadata.xml \
  appointment-notification/gradle.lockfile appointment-notification/src \
  appointment-api/src/main/resources/application.yml \
  appointment-api/src/test/resources/application-test.yml \
  appointment-notification/README.md appointment-notification/README.ko.md \
  docs/superpowers/plans docs/review docs/lessons
git commit -m "reminder leader 정책을 YAML 설정으로 외부화한다" -m "upstream scheduled policy를 재사용해 reminder 실행 경계와 운영 설정을 단일화한다.\n\nConstraint: stable leader 1.0.x 이전에는 immutable timestamp artifact를 사용해야 한다.\nRejected: @LeaderScheduled 유지와 clinic 전용 policy model 복제는 YAML 우선순위를 보장하지 못한다.\nConfidence: high\nScope-risk: moderate\nDirective: stable release가 나오면 timestamp override와 rollback 문서를 정리한다.\nTested: targeted/full notification tests, detekt, build, dependency governance, diff check.\nNot-tested: exact-head CI는 PR 단계에서 검증한다."
```

## Spec-to-plan traceability

| 설계 acceptance | 구현 계획 |
|---|---|
| YAML만으로 lock/wait/lease/backend/failure mode 적용 | Task 1, 2, 3, 4 |
| default-off/누락/factory 부재에서 unguarded 실행 차단 | Task 2-3, Task 3-2~3, Task 4-2 |
| selector/duration/lease/backend startup 거부 | Task 2-3, Task 3-3, Task 5-2 |
| contention/backend/cancellation/scheduler lifecycle 유지 | Task 2-2, Task 3-4, Task 5-1 |
| DB claim/fence 기준과 profile/rollback 문서 | Task 4-3, Task 6-2 |
| lock/verification/CI와 P0/P1=0 | Task 1-3, Task 5, Task 6 |

## Rollback과 재실행 지점

- Task 1 dependency resolution 실패: catalog/lock을 수정하지 않고 upstream
  stable release 또는 새 immutable timestamp를 다시 확인한다.
- Task 3 startup guard 실패: runner bean 조건을 먼저 복구하고 property
  disabled/factory 부재 negative test를 다시 실행한다.
- Task 5 container/network 실패: Colima/Docker 상태와 raw failure를 분리해
  기록하고 동일 명령을 한 번만 재실행한다. 코드 변경 없이 infrastructure
  failure를 PASS로 바꾸지 않는다.
- PR/CI에서 P0/P1이 발생하면 Task 3~6으로 되돌아가 affected proof를 갱신한다.
- merge 전 rollback은 `bluetape4k.leader.scheduling.enabled=false` 또는
  catalog timestamp를 기존 `0.5.0`으로 되돌리는 방식만 사용한다.

## 계획 self-review

- 설계의 문제·근거·대안·설정 계약·실패 모드·호환성·rollback·DoD가 Task 1~6과
  traceability 표에 모두 연결되어 있다.
- 미완성 표기 없이 실제 파일·심볼·명령·expected 결과를
  적었다.
- `REMINDER_RECOVERY_POLICY_SELECTOR`, policy property key, bean name,
  timestamp와 test selector가 모든 task에서 동일하다.
- API consumer fixture, BOM/catalog, Spring condition, Korean README, KDoc,
  cancellation, backend capability, performance/stability, lesson을 모두
  검증 범위에 넣었다.

## Writer gate 결과

| 항목 | 결과 |
|---|---|
| SPW-01 근거·독자·목적 고정 | PASS |
| SPW-02 plan contract·순서·명령·rollback | PASS |
| SPW-03 Korean technical register·terminology | PASS |
| SPW-04 spec-to-plan traceability·upstream 사실 확인 | PASS |
| SPW-05 rendered read-back·미완성 표기 점검 | PASS |
