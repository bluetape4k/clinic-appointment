# Issue #336 `apiElements` 공개 API 경계 검증

## 판정

`appointment-core`, `appointment-messaging`, `appointment-notification`의 모듈 단독 Kotlin 소비자 fixture가 `Usage.JAVA_API` 요청으로 생산자 `apiElements`를 선택하고 컴파일된다. 공개 선언을 해석하는 데 필요한 의존성만 `api` 또는 `compileOnlyApi`로 전달했다. 외부 Maven publication과 실제 운영 SLO는 이 검증의 범위가 아니다.

최종 상태: **GREEN**

## 실행 조건과 기준선

- 기준 ref: `d1718331f1d418baf455d8046ad6cfc2e1567460`
- 구현 branch의 기준 설계: `7dfb89e1c2f7acd4e7f0bf21413cf7e8f45e4ff3`
- Gradle wrapper: `9.7.0`
- producer/fixture target: JVM 21
- CI JDK: 25
- 기준 명령:

  ```bash
  ./gradlew :appointment-core:test :appointment-messaging:test :appointment-notification:test --no-parallel
  ```

  기준선은 `BUILD SUCCESSFUL`이었다. 이 결과는 기존 모듈 동작만 증명하며 단독 `apiElements` 소비를 증명하지 않는다.

## RED에서 확인한 실제 누락

fixture와 대상 project dependency 하나만 연결한 뒤 scope를 바꾸기 전에 `--rerun-tasks`로 실행했다.

| 모듈 | fixture가 직접 해석한 공개 surface | 변경 전 오류 요약 | 제공 scope |
|---|---|---|---|
| core | 공개 repository 10종의 `LongJdbcRepository` supertype | `LongJdbcRepository`와 `io.bluetape4k.exposed.jdbc.repository`를 `apiElements` compile classpath에서 찾지 못함 | `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc` → `api` |
| messaging | Kafka/Spring Kafka, Exposed `Database`, Micrometer, Spring auto-configuration type-use | `ConsumerRecord`, `Acknowledgment`, `ConsumerFactory`, `KafkaTemplate`, `KafkaAdmin`, `Database`, `MeterRegistry`, Boot/Context type을 찾지 못함 | Kafka·Spring Kafka·Exposed JDBC·Micrometer → `api`; Boot/Context 공개 선언 전용 좌표 → `compileOnlyApi` |
| notification | messaging API, Kafka/Exposed/Micrometer, leader/Redis/Lettuce, Resilience4j | messaging package와 `ConsumerRecord`, `Database`, `MeterRegistry`, `LeaderGroupElector`, Lettuce/Redis, Resilience4j type을 찾지 못함 | messaging·Kafka·Exposed·Micrometer·leader/Redis/Lettuce·Resilience4j → `api`; Boot/Context 공개 선언 전용 좌표 → `compileOnlyApi` |

fixture 자체의 오타와 잘못된 production symbol은 먼저 고쳤고 RED 근거에서 제외했다.

## GREEN compile 계약

루트 `build.gradle.kts`에 다음 resolvable configuration과 Kotlin compile task를 추가했다.

| 모듈 | configuration | compile task | output |
|---|---|---|---|
| core | `appointmentCoreConsumerFixtureClasspath` | `compileAppointmentCoreConsumerFixture` | `build/consumer-fixtures/core/classes` |
| messaging | `appointmentMessagingConsumerFixtureClasspath` | `compileAppointmentMessagingConsumerFixture` | `build/consumer-fixtures/messaging/classes` |
| notification | `appointmentNotificationConsumerFixtureClasspath` | `compileAppointmentNotificationConsumerFixture` | `build/consumer-fixtures/notification/classes` |

각 configuration은 대상 project 하나만 의존하고 `Category.LIBRARY`, `Usage.JAVA_API`, `Bundling.EXTERNAL`, `LibraryElements.JAR`, JVM 21을 요청한다. 각 compile task는 대응 모듈의 `jar` task를 명시적으로 선행한다. fixture source에는 `Class.forName`이나 문자열 기반 우회가 없고 실제 Kotlin import, KClass, 공개 type-use를 사용한다.

검증 명령과 결과:

```bash
./gradlew clean compileModuleConsumerFixtures --no-daemon --no-configuration-cache --console=plain
```

`compileModuleConsumerFixtures`는 세 fixture 모두 `BUILD SUCCESSFUL`이었다. report/assertion task도 같은 실행에서 수행됐다.

대표 mutation 회귀도 수행했다. core의 `bluetape4k-exposed-jdbc` `api` 한 줄을 원복하면 producer compile 자체가 `LongJdbcRepository` 누락으로 실패했고, messaging의 Kafka client `api` 한 줄과 notification의 messaging project `api` 한 줄을 각각 원복하면 `assertModuleConsumerFixtureApiVariants`가 승인 coordinate 누락으로 실패했다. 세 build script blob hash는 mutation 전후 각각 `a025bb9`, `fb8a1c9`, `52e9512`로 복구됐고 마지막 clean GREEN compile에서 다시 확인했다.

추가 fail-closed 회귀로 messaging의 기존 Jackson `implementation` 한 줄을 임시 `api`로 승격했을 때 exact scope assertion이 `unexpected=[tools.jackson.module:jackson-module-kotlin]`으로 실패했다. 현재 report의 messaging fingerprint를 임시 값으로 바꾼 뒤 generator를 제외하고 assertion만 실행했을 때도 `resolution fingerprint is stale`로 실패했다. 두 mutation 모두 즉시 원복하고 fresh report를 다시 생성했다. detached `HEAD` checkout에서 variant report 생성도 `BUILD SUCCESSFUL`이었고 `sourceRef=HEAD`를 기록했다.

```bash
./gradlew check --dry-run --no-daemon --no-configuration-cache --console=plain
```

관찰된 핵심 순서는 다음과 같다.

```text
:appointment-core:jar
:appointment-messaging:jar
:appointment-notification:jar
:generateModuleConsumerFixtureVariantReport
:assertModuleConsumerFixtureApiVariants
:generateModuleConsumerFixtureClasspathReport
:assertModuleConsumerFixtureTaskGraph
:compileAppointmentCoreConsumerFixtureKotlin
:compileAppointmentCoreConsumerFixture
:compileAppointmentMessagingConsumerFixtureKotlin
:compileAppointmentMessagingConsumerFixture
:compileAppointmentNotificationConsumerFixtureKotlin
:compileAppointmentNotificationConsumerFixture
:compileModuleConsumerFixtures
:check
```

## 구조화 report와 assertion

고정 출력은 다음과 같다.

- `build/reports/consumer-fixtures/issue-336/variants.json`
- `build/reports/consumer-fixtures/issue-336/classpath.json`
- `build/reports/consumer-fixtures/issue-336/diagnostics.json`
- `build/reports/consumer-fixtures/issue-336/performance.json` (성능 collector 실행 시)

`variants.json`은 contract version, run/ref/SHA, Gradle/JDK, selected component·variant·attributes, artifact basename/size, direct API root와 전체 resolved coordinate, resolution fingerprint를 기록한다. 현재 세 module은 모두 `selectedVariant=apiElements`, `usage=java-api`이다. `assertModuleConsumerFixtureApiVariants`는 detached-safe ref/SHA, 실패 status, 다른 variant/usage, direct `api`·`compileOnlyApi` exact scope, API root allowlist, 현재 configuration 재계산 fingerprint 불일치를 실패시킨다.

`classpath.json`은 module별 artifact count/size, coordinate와 classpath fingerprint를 기록한다. resolution 실패는 module별 status와 제한된 원인 chain으로 `diagnostics.json`에 남기며 URL, 경로, credential 패턴을 정제한다. fixture source와 report 경로는 repository root 내부의 non-symlink 경로인지 검사한다.

`assertModuleConsumerFixtureTaskGraph`는 producer `jar → report → assertion → fixture compile → compileModuleConsumerFixtures → check` 연결을 선언 dependency로 확인한다. root `clean`은 fixture class와 report 디렉터리를 함께 제거한다.

## 공개 surface inventory

fixture line 주석과 다음 production source anchor를 1:1로 유지한다.

루트 `build.gradle.kts`의 `apiConsumerFixtureInventory` manifest가 각 fixture source에 이 symbol 목록이 실제 문자열 import/type-use로 존재하는지 report 단계에서 검사한다. 목록에서 symbol을 삭제하거나 fixture를 누락하면 report/assertion 전에 실패한다.

### core

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
- 같은 디렉터리의 `ClinicRepository`, `DoctorRepository`, `EquipmentRepository`, `HolidayRepository`, `PatientAccountRepository`, `PatientLoginIdentityRepository`, `RescheduleCandidateRepository`, `TenantGroupRepository`, `TreatmentTypeRepository`
- 외부 type-use: `LongJdbcRepository` 공개 supertype와 repository generic

### messaging

- `AppointmentConsumerRuntime`, `JdbcAppointmentConsumerInboxStore`, `AppointmentConsumerRetentionService`, `AppointmentReplayService`
- `AppointmentKafkaConsumerListener`, `KafkaAppointmentReplaySource`, `SpringKafkaAppointmentPublisher`
- `AppointmentKafkaConsumerConfiguration`, `AppointmentKafkaProducerConfiguration`, `AppointmentMessagingAutoConfiguration`
- `AppointmentMessagingHealthIndicator`, `AppointmentOutboxRelayLifecycle`, `AppointmentMessagingStartupValidator`, `AppointmentMessagingReadinessValidator`
- `MicrometerAppointmentConsumerMetrics`, `MicrometerAppointmentOutboxMetrics`, `AppointmentConsumerInboxTable`, `AppointmentConsumerQuarantineTable`
- 외부 type-use: `ConsumerRecord`, `Acknowledgment`, `Consumer`, `ConsumerFactory`, `ConcurrentKafkaListenerContainerFactory`, `KafkaTemplate`, `KafkaAdmin`, `ProducerFactory`, `Database`, `Table`, `LongIdTable`, `MeterRegistry`, `ObjectProvider`, `DataSource`, `HealthIndicator`, `SmartLifecycle`

### notification

- `NotificationAppointmentEventConsumer`, `NotificationAppointmentEventKafkaListener`
- `NotificationSchemaReadiness`, `JdbcNotificationOutboxWorkStore`, `JdbcNotificationOutboxObservationStore`, `NotificationOutboxWorkStore`, `NotificationOutboxObservationStore`, `NotificationOutboxRepository`, `NotificationOutboxMetrics`
- `NotificationOutboxSchedulingRunner`, `NotificationObservationSchedulingRunner`, `NotificationRetentionSchedulingRunner`, `NotificationReminderSchedulingRunner`, `NotificationRetentionRunner`, `AppointmentReminderScheduler`
- `ResilientNotificationChannel`, `NotificationAutoConfiguration`, `NotificationRuntimeHealthSignals`, `NotificationDirectDeliveryPort`
- 외부 type-use: messaging 공개 type, `ConsumerRecord`, `Acknowledgment`, `Database`, `MeterRegistry`, `LeaderGroupElector`, `RedisClient`, `StatefulRedisConnection`, Resilience4j `CircuitBreaker`·`Retry`·`Bulkhead`, `@ConditionalOnClass`

## runtime optional classpath 회귀

`appointment-notification/src/test/.../NotificationAutoConfigurationTest.kt`에 `ApplicationContextRunner`와 `FilteredClassLoader`를 사용한 회귀를 추가했다. Redis/Lettuce classpath가 있으면 기존 leader elector 생성 테스트가 통과하고, 두 classpath를 숨기면 auto-configuration이 실패하지 않고 leader bean을 건너뛴다. Testcontainers, 실제 Redis, broker 또는 외부 서버는 사용하지 않는다.

## 성능 증거 수집

`scripts/collect-issue-336-api-elements-performance.mjs`는 `--ref`, `--mode`, `--runs`(기본 3), `--gradle-args`를 받아 매 실행의 `/usr/bin/time -p` real milliseconds와 Gradle task outcome을 `performance.json`에 기록한다. 실행마다 임시 `GRADLE_USER_HOME`, `--no-build-cache`, `--max-workers=2`, daemon 비활성화와 Kotlin in-process compiler를 사용한다. 기준 SHA에는 fixture task가 없으므로 신규 fixture cold 비용과 기존 compile-only build 회귀를 별도 series로 기록해야 하며, 이 문서의 GREEN은 성능 SLO 판정을 의미하지 않는다.

## CI 보존

`.github/workflows/ci.yml`과 `.github/workflows/nightly.yml`의 build job은 항상 `build/reports/consumer-fixtures/issue-336/`를 `api-consumer-fixture-report` artifact로 보존한다. CI의 `--refresh-dependencies` series와 Nightly의 no-refresh series는 서로 비교하지 않는다.

## 범위 및 안전성

- `appointment-*/src/main/**` production Kotlin diff: 없음
- public class, constructor, method, visibility, binary ABI: 변경하지 않음
- dependency version 또는 publication: 변경하지 않음
- 실제 운영/배포 SLO 증거: 생성하지 않음
- Testcontainers: compile metadata 이슈이므로 사용하지 않음

최종 변경 전에는 `git diff --check`, workflow `actionlint`, 세 module test, fixture clean compile, variant assertion, dry-run task graph를 다시 실행한다.
