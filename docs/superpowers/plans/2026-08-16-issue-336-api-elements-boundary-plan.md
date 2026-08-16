# Issue #336 공개 Kotlin API와 `apiElements` 경계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 Gradle build에서 `appointment-core`, `appointment-messaging`, `appointment-notification`을 각각 단독으로 선언한 Kotlin 소비자 fixture가 생산자의 `apiElements`/`Usage.JAVA_API` classpath에서 공개 API를 컴파일하도록 dependency scope와 Gradle metadata를 정합화한다. 이번 검증은 project variant 계약을 보장하며 외부 Maven publication 소비는 범위 밖으로 명시한다. 기존 production Kotlin 소스, public ABI, runtime 동작은 변경하지 않는다.

**Architecture:** 루트 `build.gradle.kts`에 모듈별 resolvable `Usage.JAVA_API` consumer configuration과 격리된 Kotlin compile task를 추가한다. 각 fixture는 대상 project dependency 하나만 선언하고 공개 supertype·생성자·프로퍼티·메서드·generic·annotation의 외부 type-use를 직접 해석한다. RED 오류로 입증된 좌표만 모듈 `api` 또는 `compileOnlyApi`로 승격하고, report/assertion task가 실제 `apiElements` 선택을 강제한다. 루트 `check`와 기존 compile-only CI 경로에 fixture와 구조화 report를 연결한다.

**Tech Stack:** Kotlin 2.3, Java 25 CI / JVM 21 producer·fixture target, Gradle Kotlin DSL, `JavaLibraryPlugin`, Spring Boot 4, Exposed, existing `kotlinx-benchmark`/Gradle conventions.

---

## 실행 규칙과 변경 경계

- 작업 디렉터리는 `/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/chore/issue-336-api-elements-boundary`로 고정한다. 루트의 사용자 소유 `?? .superpowers/`와 다른 worktree는 건드리지 않는다.
- 구현 전 승인된 설계는 `docs/superpowers/specs/2026-08-16-issue-336-api-elements-boundary-design.md`이며, 이번 문서는 그 설계를 실행 가능한 순서로 쪼갠다.
- 허용되는 최종 변경 경로는 `build.gradle.kts`, `appointment-{core,messaging,notification}/build.gradle.kts`, `src/consumerFixture/**`, `appointment-notification/src/test/**`, `scripts/collect-issue-336-api-elements-performance.mjs`, `.github/workflows/{ci,nightly}.yml`, `docs/verification/2026-08-16-issue-336-api-elements-boundary.md`, `docs/lessons/2026-08-16-issue-336-api-elements-boundary.md`, `docs/review/2026-08-16-issue-336-step-3r-plan-review.md`, 그리고 이슈 관련 설계·계획·리뷰 문서다. `appointment-*/src/main/**` production Kotlin 파일은 변경하지 않는다.
- 각 단계 전에 `git status --short --branch`와 `git diff --name-only`를 읽고, 예상 밖 파일이 생기면 해당 파일을 즉시 원인 조사 후 제거하거나 작업을 중단한다.
- Kotlin fixture를 작성할 때는 `bluetape-kotlin-patterns`의 Kotlin final/testing checklist를 적용한다. fixture는 실행 테스트가 아니라 compile contract이므로 Testcontainers, broker, database, raw `GenericContainer`를 추가하지 않는다.
- 모든 commit은 Lore 형식의 한국어 메시지를 사용한다. 구현 시작 전에는 이 계획 commit만 만들고, 다음 사용자 승인 전에는 source/build mutation을 시작하지 않는다.

## 작업 순서

### Task 0 — 실행 전 스냅샷과 공통 검증 계약 고정

- [ ] 승인된 설계 commit `7dfb89e1c2f7acd4e7f0bf21413cf7e8f45e4ff3`과 현재 branch head를 기록하고 `git diff --check`를 실행한다.
- [ ] `./gradlew :appointment-core:test :appointment-messaging:test :appointment-notification:test --no-parallel`를 기준선으로 재실행해 세 모듈의 기존 테스트가 `BUILD SUCCESSFUL`인지 확인한다.
- [ ] `git ls-files 'appointment-*/src/main/**/*.kt'`와 세 모듈 `build.gradle.kts`를 기준선 목록으로 저장한다. production source diff 검사는 이 목록과 `git diff --name-only`의 교집합이 비어 있는지로 판정한다.
- [ ] `docs/verification/2026-08-16-issue-336-api-elements-boundary.md`에 기준 ref, JDK/Gradle 조건, 기준선 명령, 예상 RED/GREEN 명령을 기록할 준비를 한다. 실제 결과와 report 경로는 각 단계 완료 직후 추가한다.

검증 명령:

```bash
git status --short --branch
git diff --check
git diff --name-only 7dfb89e1c2f7acd4e7f0bf21413cf7e8f45e4ff3..HEAD
./gradlew :appointment-core:test :appointment-messaging:test :appointment-notification:test --no-parallel
```

### Task 1 — RED: 모듈 단독 소비자 fixture와 compile task의 최소 계약 추가

- [ ] 다음 디렉터리와 파일을 만든다.

  ```text
  src/consumerFixture/core/kotlin/io/bluetape4k/clinic/appointment/consumer/CoreApiConsumerFixture.kt
  src/consumerFixture/messaging/kotlin/io/bluetape4k/clinic/appointment/consumer/MessagingApiConsumerFixture.kt
  src/consumerFixture/notification/kotlin/io/bluetape4k/clinic/appointment/consumer/NotificationApiConsumerFixture.kt
  ```

- [ ] 각 fixture는 대응 module의 public symbol을 `KClass`, constructor reference, callable reference, 상위 타입 대입, annotation type argument로 사용한다. 단순 `Class.forName`이나 문자열 import는 금지한다. fixture line 주석에는 대응 production source path와 symbol을 적는다.
- [ ] core fixture는 실제 `LongJdbcRepository` 구현체인 `AppointmentRepository`, `ClinicRepository`, `DoctorRepository`, `EquipmentRepository`, `HolidayRepository`, `PatientAccountRepository`, `PatientLoginIdentityRepository`, `RescheduleCandidateRepository`, `TenantGroupRepository`, `TreatmentTypeRepository`를 각각 상위 타입과 generic argument로 해석한다. 각 symbol의 source path를 주석으로 고정하고, 목록에 없는 추가 구현체가 생기면 inventory 검증을 실패시킨다.
- [ ] messaging fixture는 `AppointmentConsumerRuntime`, `JdbcAppointmentConsumerInboxStore`, `AppointmentConsumerRetentionService`, `AppointmentReplayService`, `AppointmentKafkaConsumerListener`, `KafkaAppointmentReplaySource`, `SpringKafkaAppointmentPublisher`, `AppointmentKafkaConsumerConfiguration`, `AppointmentKafkaProducerConfiguration`, `AppointmentMessagingAutoConfiguration`, `AppointmentMessagingHealthIndicator`, `AppointmentOutboxRelayLifecycle`, `AppointmentMessagingStartupValidator`, `AppointmentMessagingReadinessValidator`, `MicrometerAppointmentConsumerMetrics`, `AppointmentConsumerInboxTable`을 해석한다. `ConsumerRecord`, `Acknowledgment`, `Database`, `KafkaTemplate`, `KafkaAdmin`, `ConsumerFactory`, `ConcurrentKafkaListenerContainerFactory`, `ObjectProvider`, `ProducerFactory`, `MeterRegistry`, `HealthIndicator`, `SmartLifecycle`, `SmartInitializingSingleton`, `DataSource`, `Table`, `LongIdTable`가 등장하는 공개 생성자·메서드·supertype·bean method callable을 각각 직접 참조한다.
- [ ] notification fixture는 `NotificationAppointmentEventConsumer`, `NotificationAppointmentEventKafkaListener`, `NotificationSchemaReadiness`, `JdbcNotificationOutboxWorkStore`, `JdbcNotificationOutboxObservationStore`, `NotificationOutboxWorkStore`, `NotificationOutboxMetrics`, `NotificationOutboxSchedulingRunner`, `NotificationObservationSchedulingRunner`, `NotificationRetentionSchedulingRunner`, `NotificationReminderSchedulingRunner`, `ResilientNotificationChannel`, `NotificationAutoConfiguration`을 해석한다. `ConsumerRecord`, `Acknowledgment`, `Database`, `MeterRegistry`, `LeaderGroupElector`, `RedisClient`, `StatefulRedisConnection`, Resilience4j 공개 type-use와 `@ConditionalOnClass` annotation을 직접 참조한다.
- [ ] 루트 `build.gradle.kts`에 대상 project dependency 하나만 갖는 다음 configuration을 만든다. 모두 `isCanBeConsumed=false`, `isCanBeResolved=true`, `Category.LIBRARY`, `Usage.JAVA_API`, `Bundling.EXTERNAL`, `LibraryElements.JAR`, `TargetJvmVersion=21`을 갖는다.

  ```text
  appointmentCoreConsumerFixtureClasspath       -> project(":appointment-core")
  appointmentMessagingConsumerFixtureClasspath  -> project(":appointment-messaging")
  appointmentNotificationConsumerFixtureClasspath -> project(":appointment-notification")
  ```

- [ ] 각 configuration에 대응하는 `KotlinJvmCompile` task를 등록한다. task 이름과 source/output은 아래 표를 그대로 사용한다. `libraries`에는 대응 configuration 하나만 넣고, `compilerOptions.jvmTarget`은 `JVM_21`, Java release/toolchain은 21로 고정한다.

  | module | task | source | output |
  |---|---|---|---|
  | core | `compileAppointmentCoreConsumerFixture` | `src/consumerFixture/core/kotlin` | `build/consumer-fixtures/core/classes` |
  | messaging | `compileAppointmentMessagingConsumerFixture` | `src/consumerFixture/messaging/kotlin` | `build/consumer-fixtures/messaging/classes` |
  | notification | `compileAppointmentNotificationConsumerFixture` | `src/consumerFixture/notification/kotlin` | `build/consumer-fixtures/notification/classes` |

- [ ] 각 fixture compile task에 대응 project의 artifact-producing task를 명시적으로 `dependsOn`으로 연결한다(`:appointment-core:jar`, `:appointment-messaging:jar`, `:appointment-notification:jar` 또는 저장소에서 확인한 동일 artifact task). configuration resolution만으로 producer task를 추론하지 않는다. graph assertion은 producer `jar`가 report·assertion·fixture compile보다 먼저 실행되는지 고정하며, `clean --dry-run` 출력과 실제 task graph를 함께 대조한다.

- [ ] compile task가 먼저 변경 전 dependency scope를 대상으로 실행되도록 wiring하고, 실제 누락된 외부 type을 가리키는 compiler error를 모듈별로 보존한다. fixture 오타·잘못된 production API 선택으로 난 오류는 RED 증거에서 제외하고 먼저 fixture를 고친다.

RED 검증:

```bash
./gradlew compileAppointmentCoreConsumerFixture --rerun-tasks
./gradlew compileAppointmentMessagingConsumerFixture --rerun-tasks
./gradlew compileAppointmentNotificationConsumerFixture --rerun-tasks
```

예상 결과는 각 task의 실패와 함께 `apiElements` compile classpath에 없는 외부 public type이 명시되는 것이다. 세 task가 fixture 자체 오류 없이 모두 통과하면 RED가 성립하지 않은 것이므로 scope 변경을 진행하지 않고 fixture surface를 보완한다.

### Task 2 — Variant/classpath report/assertion과 root check wiring 구현

- [ ] 루트 `build.gradle.kts`에 `generateModuleConsumerFixtureVariantReport`를 추가한다. 세 configuration을 순서대로 resolve하고 `build/reports/consumer-fixtures/issue-336/variants.json`에 module, selected component, selected variant, attributes, resolved artifact의 group/name/version, basename, size, `runId`, `sourceRef`, `gitSha`, Gradle/JDK 정보, 실행 명령, resolution fingerprint를 기록한다. task에는 세 configuration의 resolved files·attributes·dependency graph fingerprint와 `variantContractVersion`을 `inputs`로, 고정 JSON과 bounded diagnostic JSON을 `outputs.file`로 선언해 동일 조건의 warm run에서만 `UP-TO-DATE`가 되게 한다. dependency scope·variant·build script가 바뀌면 `--rerun-tasks` 없이도 fingerprint가 달라져 report가 갱신되는 계약을 둔다.
- [ ] resolution 예외는 task 밖으로 전파하기 전에 module별 `status=failed`, 예외 class의 단순 이름, 500자 이내 정제 요약, 제한된 cause chain(각 class/message 최대 길이·최대 깊이), Gradle/JDK/ref/명령을 구조화해 기록한다. URL, query, credential, runner/home 절대 경로, stack trace와 arbitrary system property는 allowlist 밖이므로 기록하지 않는다. report와 `diagnostics.json`은 실패에서도 생성하고 생성 직후 secret-pattern 검사를 실행한다.
- [ ] fixture source와 report output의 모든 경로는 `followSymlinks=false`로 열고 `toRealPath()`가 repository root 내부인지 확인한다. source 파일이 symlink이거나 `..`·절대 경로가 발견되면 compile/report task를 실패시킨다. report filename은 고정 basename allowlist만 사용한다.
- [ ] `assertModuleConsumerFixtureApiVariants`가 report task에 의존하고, 각 target project component의 selected variant가 정확히 `apiElements`, `Usage`가 `java-api`인지 검사하게 한다. 실패 status, 다른 variant, 다른 usage, 누락 module은 assertion failure로 처리한다. 같은 assertion에서 RED로 승인한 `group:name`만 허용하는 machine-readable API coordinate allowlist를 검사하고, 허용 목록 밖의 전이 artifact가 생기면 실패시킨다. report의 `sourceRef`/`gitSha`와 현재 HEAD가 어긋나거나 input fingerprint가 현재 resolution과 다르면 stale report로 실패시킨다.
- [ ] `generateModuleConsumerFixtureClasspathReport`를 별도 구현한다. 세 resolvable configuration의 group/name/version, artifact count, total file size, file basename, classpath fingerprint를 `build/reports/consumer-fixtures/issue-336/classpath.json`에 고정 schema로 기록하고 variants report와 같은 redaction·symlink·root-boundary 검사를 적용한다. 두 report task의 `inputs`/`outputs.file`과 `diagnostics.json`을 명시한다.
- [ ] `assertModuleConsumerFixtureTaskGraph`를 구현한다. `producer jar -> variant/classpath report -> variant assertion -> fixture compile -> integration -> check` edge와 각 task의 declared inputs/outputs를 기계적으로 확인하고, 세 compile task가 두 assertion task에 의존하게 한다. `compileModuleConsumerFixtures`가 세 compile task를 묶고 root `check`가 통합 task에 의존하게 하며, `clean`은 `build/consumer-fixtures`와 report 디렉터리를 제거한다.
- [ ] `./gradlew check --dry-run`에서 `:appointment-*:jar -> generateModuleConsumerFixture*Report -> assertModuleConsumerFixtureApiVariants -> assertModuleConsumerFixtureTaskGraph -> compileAppointment*ConsumerFixture -> compileModuleConsumerFixtures -> check` 순서를 확인한다. report만 생성되고 assertion·producer artifact task가 빠지는 wiring은 실패로 간주한다. `--rerun-tasks` 없이 dependency scope를 바꾼 뒤 report fingerprint와 `sourceRef`/`gitSha`가 갱신되는 회귀도 수행한다.

검증 명령:

```bash
./gradlew generateModuleConsumerFixtureVariantReport --rerun-tasks
./gradlew assertModuleConsumerFixtureApiVariants --rerun-tasks
./gradlew check --dry-run
```

### Task 3 — GREEN: RED 오류가 증명한 dependency scope만 승격

- [ ] Task 1의 실제 compiler error와 public source inventory를 짝지어 `appointment-core/build.gradle.kts`, `appointment-messaging/build.gradle.kts`, `appointment-notification/build.gradle.kts`의 후보 좌표를 한 줄씩 판정한다.
- [ ] runtime에도 필요한 public type 제공 좌표는 `api`, optional framework auto-configuration 선언 해석에만 필요한 좌표는 `compileOnlyApi`로 바꾼다. production source, dependency version, module graph는 변경하지 않는다. `NotificationAutoConfiguration`이 runtime bean 생성에 사용하는 Redis/Lettuce/leader/Resilience4j 좌표는 compile-only compile 성공만으로 낮추지 않고, runtime-required `api` allowlist 또는 명시적 standalone/ApplicationContextRunner smoke 근거가 있어야 한다.
- [ ] 우선 확인할 후보는 다음과 같다. 최종 목록은 실제 RED 결과에 없는 좌표를 추가하지 않으며, 확정된 group:name은 Task 2 API coordinate allowlist에도 같은 순서로 반영한다.

  | module | 공개 type-use | 우선 확인 좌표 | scope |
  |---|---|---|---|
  | core | `LongJdbcRepository`, Exposed JDBC repository | `libs.exposed.jdbc`, `libs.jetbrains.exposed.jdbc` | `api` |
  | messaging | Kafka client/Spring Kafka 공개 생성자·메서드 | `libs.kafka4.clients`, `libs.spring.kafka4` | `api` |
  | messaging | `Database`, Exposed JDBC 공개 생성자 | `libs.jetbrains.exposed.jdbc`, `libs.exposed.jdbc` | `api` |
  | messaging | `MeterRegistry` | `libs.micrometer.core` | `api` |
  | messaging | Spring Boot/Context/SQL auto-configuration type-use | 기존 Spring Boot/Context `compileOnly` 좌표 | `compileOnlyApi` |
  | notification | messaging 공개 API | `project(":appointment-messaging")` | `api` |
  | notification | Kafka/Exposed/Micrometer public type-use | 현재 사용 중인 Kafka·Exposed·Micrometer 좌표 | `api` |
  | notification | leader/Redis/Lettuce 공개 type-use | `libs.bluetape4k.leader`, `libs.bluetape4k.leader.micrometer`, `libs.bluetape4k.lettuce`, `libs.lettuce.core` | RED와 runtime 확인에 따라 `api` 또는 `compileOnlyApi` |
  | notification | Resilience4j public factory/type-use | `libs.resilience4j.circuitbreaker`, `libs.resilience4j.retry`, `libs.resilience4j.bulkhead`, `libs.resilience4j.kotlin` | `api` |

- [ ] 한 좌표씩 변경하고 해당 fixture task를 `clean --rerun-tasks`로 다시 실행한다. 예상 밖 artifact 승격이나 새 누락이 생기면 마지막 한 줄만 원복하고 source anchor·제공 artifact를 다시 확인한다.
- [ ] 세 fixture가 모두 통과한 뒤 `compileModuleConsumerFixtures`를 실행하고 `api` dependency report, `outgoingVariants`, root configuration resolution을 대조한다. `NotificationAutoConfiguration`에서 Redis/Lettuce가 실제 bean 생성 경로에 필요한지 `ApplicationContextRunner`의 클래스 존재/부재 두 경우로 검증한 뒤 `compileOnlyApi`로 낮출 좌표가 없음을 확인하거나 근거를 기록한다.

GREEN 검증:

```bash
./gradlew clean compileModuleConsumerFixtures --no-daemon --rerun-tasks
./gradlew compileModuleConsumerFixtures --no-daemon
./gradlew assertModuleConsumerFixtureApiVariants --refresh-dependencies --no-daemon
./gradlew :appointment-core:dependencies --configuration api
./gradlew :appointment-messaging:dependencies --configuration api
./gradlew :appointment-notification:dependencies --configuration api
./gradlew :appointment-core:outgoingVariants --variant apiElements
./gradlew :appointment-messaging:outgoingVariants --variant apiElements
./gradlew :appointment-notification:outgoingVariants --variant apiElements
./gradlew dependencies --configuration appointmentNotificationConsumerFixtureClasspath
./gradlew dependencyInsight --configuration appointmentNotificationConsumerFixtureClasspath --dependency lettuce
./gradlew dependencyInsight --configuration appointmentNotificationConsumerFixtureClasspath --dependency redis
```

### Task 4 — 공개 surface inventory와 RED/GREEN/mutation 증거 문서화

- [ ] `docs/verification/2026-08-16-issue-336-api-elements-boundary.md`에 모듈별 production file, public symbol, 외부 type-use 종류, fixture line을 표로 기록한다. inventory는 public class/object/interface의 supertype, constructor, property, method, generic argument, annotation type-use와 auto-configuration의 모든 public bean method를 포함한다. `AppointmentConsumerInboxStore`/`AppointmentConsumerInboxTable`, 두 JDBC notification store, `AppointmentKafkaConsumerConfiguration`·`AppointmentMessagingStartupValidator`, 네 notification scheduling runner를 실제 선언 단위로 1:1 매핑하고, 고정 manifest와 fixture line assertion으로 목록 누락을 기계적으로 실패시킨다.
- [ ] 문서에는 변경 전 RED command/error 요약, 변경 후 GREEN command, `variants.json`·`classpath.json`·`performance.json` 경로, `check --dry-run` task order, production source diff가 없다는 `git diff --name-only` 결과를 기록한다. 절대 경로와 raw console 전체는 넣지 않는다.
- [ ] variants/classpath/performance JSON은 allowlist schema만 허용하고 synthetic credential·runner path를 입력한 redaction regression check를 통과해야 한다. 실패 report에는 bounded cause chain과 실행 메타데이터만 남고 stack trace·절대 경로·비밀값은 남지 않는지 `gitleaks`가 저장소에 제공되면 `gitleaks detect --no-banner --redact --source .`로, 아니면 동일 secret-pattern helper로 검사한다.
- [ ] GREEN 이후 dependency 후보를 하나씩 원래 scope로 되돌리는 mutation을 수행한다. mutation 직전에 세 `build.gradle.kts` blob hash, `git status --porcelain`, tracked diff와 untracked 목록을 checkpoint 파일에 기록한다. 후보 변경과 검증 shell에는 `trap` 기반 원복을 두고, 중단·실패·신호 수신 시에도 원래 한 줄과 fixture/report outputs를 복구한 뒤 blob hash, `git diff --check`, `git status --porcelain`, clean compile을 재검증한다. `git diff --name-only`만으로 복구 완료를 판단하지 않는다.
- [ ] mutation 결과가 fixture cache 때문에 통과하지 않도록 항상 `clean`과 `--rerun-tasks`를 함께 사용한다. 원복 후 `compileModuleConsumerFixtures`가 다시 통과해야 한다.

### Task 5 — 기존 CI/Nightly compile-only 경로에 구조화 report 보존 연결

- [ ] `.github/workflows/ci.yml` build job의 Gradle compile-only build 직후에 다음 upload step을 추가한다.

  ```yaml
  - name: Upload consumer fixture report
    if: always()
    uses: actions/upload-artifact@v7
    with:
      name: consumer-fixture-report
      path: build/reports/consumer-fixtures/issue-336/
      if-no-files-found: error
      retention-days: 7
  ```

- [ ] `.github/workflows/nightly.yml`에도 동일 step을 추가한다. `if: always()`로 Gradle 실패 시에도 report를 업로드하며, 별도 job이나 Docker/Testcontainers service는 추가하지 않는다. 기존 저장소가 사용하는 `actions/upload-artifact@v7`와 job permission 정책을 유지하고, immutable SHA 전환은 이 이슈 범위 밖의 별도 보안 변경으로 기록한다.
- [ ] root build가 항상 실행되는 현재 workflow path-filter 구조에는 `src/consumerFixture/**` 전용 filter를 추가하지 않는다. 이 판단을 verification 문서에 `root build always-run` 근거와 함께 기록한다.
- [ ] workflow YAML parse/lint 가능한 저장소 helper가 있으면 사용하고, 최소한 `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml`, `git diff --check`, `./gradlew check --dry-run`을 필수 검증으로 실행한다. CI는 기존 compile-only command와 동일한 `--refresh-dependencies` 조건의 baseline/candidate 회귀 증거를 업로드하고, Nightly는 refresh 없는 별도 실행의 fixture 비용·report 보존만 기록한다. 두 workflow의 수치를 서로 같은 baseline으로 비교하지 않는다.

### Task 6 — 회귀·성능·Kotlin final gate

- [ ] 다음 순서로 검증한다. Testcontainers나 외부 서버를 요구하지 않는 compile metadata 변경이므로 Docker를 기동하지 않는다.

  ```bash
  ./gradlew :appointment-core:test :appointment-messaging:test :appointment-notification:test --no-parallel
  ./gradlew clean compileModuleConsumerFixtures --no-daemon --rerun-tasks
  ./gradlew compileModuleConsumerFixtures --no-daemon
  ./gradlew build -x test -x :frontend:appointment-frontend:build --parallel --refresh-dependencies --no-daemon
  ./gradlew detekt --parallel --no-daemon
  git diff --check
  ```

- [ ] clean 후 첫 fixture compile은 세 task를 실행하고, 같은 명령의 두 번째 실행에서는 세 task가 `UP-TO-DATE`인지 확인한다. output은 `build/consumer-fixtures/<module>/classes` 외부로 쓰지 않는다.
- [ ] `scripts/collect-issue-336-api-elements-performance.mjs`를 추가한다. 이 helper는 `--ref`, `--mode`, `--runs 3`, `--gradle-args`를 받고 `/usr/bin/time -p`의 real milliseconds와 Gradle task outcome을 실행별로 수집해 `build/reports/consumer-fixtures/issue-336/performance.json`에 고정 schema로 기록한다. 입력 command에는 `GRADLE_USER_HOME`을 run 전용 임시 디렉터리로 고정하고 `GRADLE_OPTS=-Dorg.gradle.daemon=false -Dkotlin.compiler.execution.strategy=in-process`, `--no-build-cache`, `--max-workers=2`를 반드시 포함한다.
- [ ] 기준 SHA `d1718331f1d418baf455d8046ad6cfc2e1567460`와 구현 직전 SHA를 별도 clean worktree에서 같은 JDK 25, Gradle wrapper, dependency cache 정책으로 비교한다. 기준에는 fixture task 시간이 없으므로 구현의 cold 3-run median은 신규 비용으로 별도 기록하고, `:appointment-api:compileKotlin`과 CI 동일 compile-only build만 기준/구현 3-run median으로 비교한다. cold는 `clean ... --no-build-cache --rerun-tasks`를 3회, warm은 clean 없이 같은 fixture task를 3회 실행하며 세 회차의 `UP-TO-DATE`와 elapsed 값을 저장한다.
- [ ] CI 동일 build 회귀 예산은 기준 대비 `max(baseline * 1.10, baseline + 30초)`, `:appointment-api:compileKotlin`은 `max(baseline * 1.10, baseline + 5초)`로 판정한다. 각 series에 run values, median, min, max, spread, coefficient of variation을 기록하고 CV가 0.20을 넘으면 판정을 보류하고 동일 조건으로 재측정한다. 세 consumer classpath의 artifact count와 total file size delta가 RED source anchor로 설명되는지 `classpath.json`에 기록한다. CI `--refresh-dependencies` series와 Nightly no-refresh series는 각각 별도 schema/label로 보존하며, Nightly는 회귀 gate가 아니라 운영 관찰 증거로만 사용한다.
- [ ] Kotlin final checklist KT-FIN-01..11과 testing checklist KT-TEST-01..05를 증거 파일에 PASS/N/A로 표시한다. 이번 변경은 production lifecycle/Exposed transaction/API validation을 건드리지 않으므로 해당 항목은 source diff 확인 후 구체적 N/A 근거를 적는다.
- [ ] `appointment-notification/src/test/**`에 optional Redis/Lettuce classpath의 존재·부재를 분리하는 `ApplicationContextRunner` regression test를 추가하고, `compileOnlyApi` 후보를 runtime-required API로 잘못 낮추면 실패하도록 고정한다. Testcontainers나 실제 Redis는 사용하지 않는다.

### Task 7 — Step 3-R 계획 검토와 계획 승인 게이트

- [ ] 성능, 보안, SRE/운영, 개발자 경험·공개 API, 사용자·제품·호출자, 아키텍처 여섯 관점을 독립적으로 검토한다. 각 관점은 실제 계획 line, source/Gradle/CI 근거, P0/P1/P2/P3, 반영 또는 수용 이유를 기록하며, P1은 구현 전 모두 해소한다.
- [ ] `docs/review/2026-08-16-issue-336-step-3r-plan-review.md`에 정확한 fixture symbol·producer `jar` ordering·runtime `api`/`compileOnlyApi` 판정·report freshness/schema·redaction·mutation recovery·untracked fail-closed·CI/Nightly evidence separation을 포함해 최종 `P0=0/P1=0`을 기록한다. 계획에서 의도적으로 보류한 P2/P3는 구현 task와 검증 증거에 소유자를 지정한다.
- [ ] 통합 검토 뒤 계획 문서와 Step 3-R review 문서의 파일·명령·경로를 `git diff --check`, Markdown fence 검사, 허용 경로 검사로 확인한다. source/build/CI mutation은 이 계획 commit 뒤 별도 구현 승인 전까지 시작하지 않는다.

### Task 8 — lesson, 최종 diff/PR 전 검토, 구현 commit

- [ ] `docs/lessons/2026-08-16-issue-336-api-elements-boundary.md`에 한국어로 문제 원인, `apiElements`/`Usage.JAVA_API` fixture 패턴, RED/GREEN/mutation 보존 방식, 향후 새 public external type 추가 시 fixture와 scope를 함께 갱신해야 한다는 지침을 기록한다.
- [ ] `git diff --name-only`, `git diff --stat`, `git diff --check`, production Kotlin source diff 검사로 허용 경계를 재확인한다. 문서·workflow를 제외한 무관한 변경이 있으면 분리하거나 제거한다.
- [ ] `git show --check HEAD`와 fresh targeted tests/build/detekt 결과를 읽은 뒤, P0=0/P1=0인 독립 code review를 완료한다.
- [ ] 구현 완료 뒤 `docs/review/2026-08-16-issue-336-step-3r-implementation-review.md`에 여섯 관점별 P0/P1/P2/P3, 반영·보류 판단, 전체 P0=0/P1=0, 실제 검증 결과를 기록한다. 계획 단계 review와 구현 단계 review를 같은 파일에 덮어쓰지 않는다.
- [ ] production diff 검사는 tracked diff뿐 아니라 `git status --porcelain`와 `git ls-files --others --exclude-standard`를 허용 목록과 fail-closed로 대조한다. 허용 목록 밖의 untracked production source, build output, report, credential-like file이 하나라도 있으면 commit 전에 실패시킨다.
- [ ] 구현 commit은 다음 Lore 메시지 구조를 따른다.

  ```text
  공개 API 소비자 컴파일 계약을 apiElements에 맞춘다

  공개 시그니처를 유지하면서 모듈 단독 소비자 검증을 루트 check와 CI에 연결한다.

  Constraint: 기존 production Kotlin ABI와 runtime 동작을 변경하지 않는다
  Rejected: adapter visibility 축소 및 모듈 분리는 Issue #336 범위를 넘으므로 제외했다
  Confidence: high
  Scope-risk: moderate
  Directive: 새 공개 외부 type-use는 해당 consumer fixture와 dependency scope를 함께 갱신한다
  Tested: fixture RED/GREEN, module tests, build, detekt, metadata, mutation, performance
  Not-tested: 외부 Maven publication 소비는 저장소에 publication 구성이 없어 제외했다
  ```

## 완료 후 승인 게이트

계획 단계 완료 조건은 다음과 같다.

- [ ] 이 계획 문서가 실제 파일·task·명령·예상 증거를 모두 고정했다.
- [ ] Type-A Step 3-R 여섯 관점(성능, 보안, SRE/운영, 개발자 경험, 사용자/제품, 아키텍처)을 독립적으로 검토해 P0=0/P1=0으로 수렴했다.
- [ ] 계획 commit과 `git diff --check`가 통과했다.
- [ ] 다음 사용자 승인 전에는 Task 1 이후의 source/build/CI mutation을 시작하지 않는다.

구현 완료 DoD는 구현 단계에서 별도로 보고하며, PR merge는 fresh CI/review와 별도 사용자의 명시적 승인 후 rebase merge로만 수행한다.
