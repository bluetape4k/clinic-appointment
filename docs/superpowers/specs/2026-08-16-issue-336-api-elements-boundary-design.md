# Issue #336 공개 Kotlin API와 `apiElements` 경계 설계

## 1. 목적

`appointment-core`, `appointment-messaging`, `appointment-notification`을 각각 단독으로 의존하는 Kotlin 소비자가 공개 API를 컴파일할 수 있게 한다. 기존 공개 클래스, 생성자, 상속 구조와 애플리케이션 실행 동작은 유지하고, 공개 시그니처 해석에 필요한 의존성만 각 모듈의 `apiElements`로 전달한다.

## 2. 문제

루트 빌드는 모든 하위 모듈에 Gradle `java-library`를 적용한다. 이 계약에서 `api` 의존성은 소비자의 compile classpath로 전달되지만 `implementation` 의존성은 전달되지 않는다. 현재 세 모듈은 `implementation`에 둔 외부 타입을 공개 supertype, 생성자 또는 메서드 매개변수로 노출한다.

- `appointment-core`의 `AppointmentRepository`와 다른 JDBC repository는 `io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository`를 공개 supertype으로 사용한다.
- `appointment-messaging`의 공개 계약은 `ConsumerRecord`, `Acknowledgment`, `Database`, `KafkaTemplate`, `KafkaAdmin`, `ConsumerFactory`, `MeterRegistry` 등을 노출한다.
- `appointment-notification`의 공개 계약은 `appointment-messaging` 타입, `ConsumerRecord`, `Acknowledgment`, `Database`, `LeaderGroupElector`, Resilience4j 타입 등을 노출한다.
- `appointment-api`와 같은 통합 모듈은 필요한 라이브러리를 직접 의존하므로 현재 테스트와 애플리케이션 빌드는 이 누락을 가린다.

따라서 생산자 모듈 자체는 빌드되지만 해당 모듈 하나만 선언한 소비자는 공개 선언을 해석하지 못할 수 있다.

## 3. 근거

### 3.1 저장소 근거

- `build.gradle.kts`는 하위 프로젝트에 `JavaLibraryPlugin`을 적용한다.
- `appointment-core/build.gradle.kts`의 `api`에는 Exposed core 계열이 있지만 `libs.exposed.jdbc`와 `libs.jetbrains.exposed.jdbc`는 `implementation`이다.
- `appointment-messaging/build.gradle.kts`의 `api`에는 `project(":appointment-event")`만 있다.
- `appointment-notification/build.gradle.kts`의 `api`에는 core와 event만 있고 messaging, Kafka, Exposed JDBC, leader, Resilience4j는 `implementation`이다.
- 변경 전 기준선에서 다음 명령은 통과했다.

```bash
./gradlew :appointment-core:test :appointment-messaging:test :appointment-notification:test --no-parallel
```

결과는 `BUILD SUCCESSFUL`이다. 이는 기존 동작의 기준선일 뿐 단독 소비자 compile 계약을 증명하지 않는다.

### 3.2 기존 bluetape 패턴

- `bluetape4k-projects`의 Cassandra consumer runtime fixture는 일반 테스트 classpath와 분리된 소비자 classpath로 공개 타입의 전이 의존성을 검증한다.
- `bluetape4k-aws`는 `Usage.JAVA_API`를 요청하는 resolvable configuration과 별도 Kotlin compile task로 외부 소비자 fixture를 컴파일한다.

이번 작업은 별도 테스트 모듈을 추가하지 않고 루트 빌드에 동일한 variant-aware compile fixture 패턴을 적용한다.

### 3.3 Gradle 계약

Gradle Java Library Plugin은 공개 API에 필요한 의존성을 `api`로 선언하고 내부 구현 의존성을 `implementation`으로 선언하도록 정의한다. `java-api`를 요청하는 소비자는 생산자의 `apiElements` variant를 선택한다.

- <https://docs.gradle.org/current/userguide/java_library_plugin.html>
- <https://docs.gradle.org/current/userguide/variant_aware_resolution.html>

## 4. 제약과 범위

### 4.1 필수 제약

- 기존 공개 Kotlin 선언의 visibility, 이름, 생성자, 메서드, 상속 관계를 변경하지 않는다.
- 공개 선언을 해석하고 runtime에도 필요한 의존성은 `api`, optional framework 공개 선언에만 필요한 의존성은 `compileOnlyApi`로 전달한다.
- fixture는 대상 모듈 외의 누락 의존성을 직접 선언하지 않는다.
- Testcontainers나 실제 외부 서버는 필요하지 않다. 이 이슈는 compile metadata 계약을 검증한다.
- 기존 테스트와 애플리케이션 runtime 결과를 유지한다.

### 4.2 포함 범위

- 세 모듈의 Gradle dependency scope
- 루트 빌드의 세 가지 독립 consumer fixture compile configuration/task
- fixture Kotlin source
- 루트 `check` 및 기존 CI build 경로 연결
- 설계, 계획, 리뷰, lesson, PR에서의 호환성 기록

### 4.3 제외 범위

- concrete adapter의 `internal` 전환
- API/SPI와 Kafka·Exposed adapter 모듈 분리
- 공개 API 재설계
- dependency version 변경
- runtime 동작, 데이터베이스 schema, Kafka topic, 알림 처리 로직 변경
- README 사용법 또는 KDoc 의미 변경
- Maven publication과 외부 repository 배포 검증. 현재 저장소에는 해당 모듈의 `maven-publish` 구성이 없으므로 이번 보장은 같은 Gradle build의 project `apiElements` 소비자에 한정한다.

## 5. 대안

### 5.1 선택: 필요한 의존성만 `api` 또는 `compileOnlyApi`로 전달

공개 Kotlin 선언을 유지하고 consumer fixture의 실제 RED 오류가 요구하는 의존성만 전달한다. runtime 구현에도 필요한 좌표는 `api`, optional Spring auto-configuration 공개 선언에만 필요한 좌표는 `compileOnlyApi`를 사용한다.

장점:

- 소스·바이너리 호환성을 유지한다.
- 변경이 Gradle metadata와 검증 fixture에 한정된다.
- 누락이 재발하면 CI compile 단계에서 검출된다.

단점:

- 소비자 compile classpath가 현재보다 커진다.
- 현재 공개 concrete adapter 계약을 계속 지원해야 한다.

### 5.2 제외: concrete adapter를 `internal`로 축소

Kafka, Exposed, leader, Resilience4j 타입을 노출하는 concrete class를 내부 구현으로 바꾸고 repository-owned port만 공개한다.

제외 이유:

- 기존 공개 클래스와 생성자를 사용하는 Kotlin 소비자에게 source compatibility 변경이 생긴다.
- JVM bytecode와 Kotlin metadata 관점의 binary compatibility 검토 범위가 커진다.
- 사용자가 공개 concrete adapter ABI 유지를 승인했다.

### 5.3 제외: API/SPI와 adapter 모듈 분리

도메인 계약과 Kafka·Exposed·leader 구현을 별도 artifact로 분리한다.

제외 이유:

- 모듈 등록, CI matrix, 의존성 그래프, 애플리케이션 wiring과 사용 문서를 함께 바꾸게 된다.
- Issue #336의 metadata 정합성 수정 범위를 넘는다.

## 6. 설계

### 6.1 소비자 fixture classpath

루트 `build.gradle.kts`에 세 resolvable configuration을 둔다.

- `appointmentCoreConsumerFixtureClasspath`
- `appointmentMessagingConsumerFixtureClasspath`
- `appointmentNotificationConsumerFixtureClasspath`

각 configuration은 다음 속성을 가진다.

- `isCanBeConsumed = false`
- `isCanBeResolved = true`
- `Category.LIBRARY`
- `Usage.JAVA_API`
- `Bundling.EXTERNAL`
- `LibraryElements.JAR`
- `TargetJvmVersion = 21`

각 configuration에는 대응하는 project dependency 하나만 추가한다. 예를 들어 core fixture는 `project(":appointment-core")`만 선언한다. Kafka, Spring Kafka, Exposed JDBC, leader, Resilience4j 또는 다른 애플리케이션 모듈을 우회 의존성으로 추가하지 않는다.

전용 report task `generateModuleConsumerFixtureVariantReport`는 세 configuration의 resolution result를 해석하고, module별 selected variant, attributes, resolved artifact 좌표와 `sourceRef`·`gitSha`·resolution fingerprint를 `build/reports/consumer-fixtures/issue-336/variants.json`에 기록한다. task input에 fingerprint를 선언해 dependency scope·variant·build script가 바뀌면 `--rerun-tasks` 없이도 stale report가 재사용되지 않게 한다. resolution 예외도 task 내부에서 module별 `status=failed`, 오류 class, 제한된 cause chain과 정제한 요약으로 기록하고 report·bounded diagnostics 파일 생성을 완료한다. 별도 `generateModuleConsumerFixtureClasspathReport`는 동일 configuration의 artifact count·size·fingerprint를 `classpath.json`에 기록한다. 전용 검증 task `assertModuleConsumerFixtureApiVariants`는 이 JSON을 입력으로 받아 대상 project component의 variant가 `apiElements`이고 `Usage`가 `Usage.JAVA_API`인지 검사하며, 실패 status나 속성 불일치가 있으면 report 생성 뒤 실패한다. `assertModuleConsumerFixtureTaskGraph`는 producer `jar -> report -> assertion -> compile -> check` edge를 기계적으로 검사한다. 이 assertion들은 report task에 의존하고, 세 compile task는 assertion에 의존한다. `compileModuleConsumerFixtures`는 세 compile task를 묶으며 루트 `check`는 이 통합 task에 의존한다. `--dry-run` 출력은 기계 assertion의 보조 증거로 사용한다. 단순 dependency report 출력은 assertion을 대신하지 않는다.

### 6.2 fixture source와 compile task

fixture source는 루트 아래에 모듈별로 분리한다.

```text
src/consumerFixture/core/kotlin/
src/consumerFixture/messaging/kotlin/
src/consumerFixture/notification/kotlin/
```

각 fixture는 대응 모듈의 최소 시작 anchor를 실제로 참조하고, 6.3의 전수 inventory로 확장한다.

- core: 공개 JDBC repository의 생성과 supertype 해석
- messaging: Kafka consumer runtime, inbox store, replay source, Spring Kafka publisher, Micrometer adapter의 공개 생성자·메서드 해석
- notification: messaging 기반 consumer, Kafka listener, JDBC store/readiness, leader-aware runner, Resilience4j channel의 공개 생성자·메서드 해석

세 Kotlin compile task는 각각 대응 configuration만 `libraries`로 사용한다. 각 task는 다음 계약을 명시한다.

- source 입력은 대응하는 모듈별 fixture 디렉터리 하나다.
- `libraries` 입력은 대응하는 consumer fixture configuration 하나다.
- output은 `build/consumer-fixtures/<module>/classes`로 분리한다.
- Kotlin compiler의 `jvmTarget`은 `JVM_21`, Java toolchain은 21로 고정한다.
- 대상 project artifact를 생산하는 task dependency는 각 fixture compile task에 대응하는 project `jar` task를 명시적으로 연결하고, configuration resolution만으로 추론하지 않는다. host JDK에 의존하지 않는다.
- root `clean`은 `build/consumer-fixtures`를 기존 root build directory와 함께 제거한다.

task와 경로의 대응은 다음과 같이 고정한다.

| 모듈 | configuration | source path | compile task | output path |
|---|---|---|---|---|
| core | `appointmentCoreConsumerFixtureClasspath` | `src/consumerFixture/core/kotlin` | `compileAppointmentCoreConsumerFixture` | `build/consumer-fixtures/core/classes` |
| messaging | `appointmentMessagingConsumerFixtureClasspath` | `src/consumerFixture/messaging/kotlin` | `compileAppointmentMessagingConsumerFixture` | `build/consumer-fixtures/messaging/classes` |
| notification | `appointmentNotificationConsumerFixtureClasspath` | `src/consumerFixture/notification/kotlin` | `compileAppointmentNotificationConsumerFixture` | `build/consumer-fixtures/notification/classes` |

통합 task `compileModuleConsumerFixtures`가 variant assertion과 세 compile task를 묶고, 루트 `check`가 이 통합 task에 의존한다. 기존 CI와 Nightly의 `./gradlew build -x test`가 root `check`를 통과하므로 별도 workflow job은 추가하지 않는다. `.github/workflows/ci.yml`과 `.github/workflows/nightly.yml`의 compile-only build job에는 `if: always()` report upload step을 각각 추가한다.

### 6.3 fixture 공개 surface matrix

fixture는 단순히 대표 DTO를 생성하지 않고 외부 dependency별 공개 type-use를 고정한다. 구현 계획에서 다음 source anchor와 실제 callable reference를 1:1로 매핑한다.

| 모듈 | 공개 source anchor | fixture가 해석할 외부 type-use |
|---|---|---|
| core | `AppointmentRepository`를 포함한 공개 JDBC repository | `LongJdbcRepository` 공개 supertype과 repository 생성자 |
| messaging | `AppointmentConsumerRuntime` | `ConsumerRecord`, `Acknowledgment`를 받는 모든 공개 `consume` overload |
| messaging | `JdbcAppointmentConsumerInboxStore`, `AppointmentConsumerRetentionService`, `AppointmentReplayService` | `Database`를 받는 공개 생성자 |
| messaging | `AppointmentKafkaConsumerListener`, `KafkaAppointmentReplaySource`, `AppointmentKafkaConsumerConfiguration` | `Consumer`, `ConsumerFactory`, listener interface가 포함된 공개 class·생성자·factory method |
| messaging | `SpringKafkaAppointmentPublisher`, Kafka producer/consumer configuration | `KafkaTemplate`, `KafkaAdmin`, `ConsumerFactory`가 포함된 공개 생성자·factory method |
| messaging | `MicrometerAppointmentConsumerMetrics`, `MicrometerAppointmentOutboxMetrics` | `MeterRegistry`를 받는 공개 생성자 |
| messaging | `AppointmentMessagingHealthIndicator`, `AppointmentOutboxRelayLifecycle`, `AppointmentMessagingStartupValidator` | `HealthIndicator`, `SmartLifecycle`, `SmartInitializingSingleton` 공개 supertype과 `DataSource` 생성자 type-use |
| messaging | `AppointmentConsumerInboxStore`, `AppointmentConsumerInboxTable` 계열 공개 object | `ConsumerRecord` method type-use와 Exposed `Table`·`LongIdTable` supertype |
| messaging | `AppointmentMessagingAutoConfiguration` | 모든 public bean method의 callable reference와 `ObjectProvider`, `DataSource`, `Database`, `ProducerFactory`, `MeterRegistry` type-use |
| notification | `NotificationAppointmentEventConsumer`, `NotificationAppointmentEventKafkaListener` | messaging 공개 타입, `ConsumerRecord`, `Acknowledgment`가 포함된 생성자·listener method |
| notification | JDBC store와 `NotificationSchemaReadiness` | `Database`를 받는 공개 생성자·method |
| notification | `NotificationOutboxMetrics` | `MeterRegistry`를 받는 공개 생성자 |
| notification | `NotificationOutboxSchedulingRunner`, `NotificationObservationSchedulingRunner`, `NotificationRetentionSchedulingRunner`, `NotificationReminderSchedulingRunner` | 각 공개 생성자와 `LeaderGroupElector`를 받는 reminder 생성자 |
| notification | `ResilientNotificationChannel` | 공개 Resilience4j factory·생성자 type-use |
| notification | `NotificationAutoConfiguration`의 JDBC bean methods | 모든 public bean method의 callable reference와 `Database` type-use |
| notification | `NotificationAutoConfiguration.notificationLeaderElection` | `@ConditionalOnClass(RedisClient::class)` annotation, `RedisClient`, `StatefulRedisConnection`, 반환 `LeaderGroupElector` type-use |

fixture는 이 표의 대표 항목만 검사하지 않는다. 외부 package를 import하는 public production file의 public class·object·interface를 전수 inventory로 고정한다. 각 선언의 공개 supertype, constructor, property, method, generic argument, annotation type-use를 포함하고, auto-configuration은 모든 public bean method의 callable reference를 포함한다. inventory는 `docs/verification/2026-08-16-issue-336-api-elements-boundary.md`에 source path, symbol, type-use 종류, 외부 type, fixture line을 기록한다. fixture source에는 같은 symbol 이름을 주석으로도 남긴다. GREEN 뒤에는 후보 `api` 또는 `compileOnlyApi` dependency 하나를 원래 scope로 내렸을 때 해당 모듈 fixture가 실패하는 mutation check를 dependency 계열마다 한 번 수행하고 원복한다.

### 6.4 RED/GREEN dependency 결정

1. fixture와 compile task만 추가한다.
2. 세 task를 변경 전 dependency scope로 실행해 예상한 compile 실패를 기록한다.
3. 오류가 지목한 외부 타입의 제공 dependency를 한 번에 하나씩 `api` 또는 `compileOnlyApi`로 변경한다.
4. 같은 fixture task를 다시 실행한다.
5. 세 fixture가 모두 통과할 때 중단한다.
6. 각 모듈의 `api` dependency report를 검토해 fixture가 요구하지 않은 dependency 승격이 없는지 확인한다.
7. 승격 하나가 예상 밖 dependency graph 또는 compile 실패를 만들면 해당 한 줄만 원복하고 RED를 다시 확인한 뒤, 제공 artifact와 source anchor를 재조사한다. 여러 후보를 한꺼번에 원복하지 않는다.
8. mutation 전에는 대상 `build.gradle.kts`의 blob hash와 `git diff --name-only`를 기록한다. mutation 뒤에는 `clean <fixture-task> --rerun-tasks`로 stale output을 배제한다. 원복 후 blob hash와 diff scope가 mutation 전과 같아야 다음 검증으로 진행한다.

후보 dependency는 다음과 같지만 최종 승격 목록은 RED 결과로 확정한다.

| 모듈 | 공개 타입 근거 | 후보 dependency | 후보 scope |
|---|---|---|---|
| core | `LongJdbcRepository` supertype | `libs.exposed.jdbc`, 필요한 경우 `libs.jetbrains.exposed.jdbc` | `api` |
| messaging | Kafka/Spring Kafka 공개 매개변수 | `libs.kafka4.clients`, `libs.spring.kafka4` | `api` |
| messaging | Exposed JDBC 공개 생성자 | `libs.jetbrains.exposed.jdbc`, 필요한 경우 `libs.exposed.jdbc` | `api` |
| messaging | `MeterRegistry` 공개 생성자 | `libs.micrometer.core` | `api` |
| messaging | public auto-configuration의 Spring Boot/Context/SQL 타입 | 기존 Spring Boot/Context `compileOnly` 좌표 | `compileOnlyApi` |
| notification | 공개 messaging 타입 | `project(":appointment-messaging")` | `api` |
| notification | Kafka listener 공개 매개변수 | `libs.spring.kafka4` 또는 그 API가 전달하는 Kafka client | `api` |
| notification | Exposed JDBC 공개 생성자 | `libs.jetbrains.exposed.jdbc`, 필요한 경우 `libs.exposed.jdbc` | `api` |
| notification | `MeterRegistry` 공개 생성자 | `libs.micrometer.core` | `api` |
| notification | leader-aware runner와 public auto-configuration | `libs.bluetape4k.leader`, `libs.bluetape4k.leader.micrometer`, `libs.bluetape4k.lettuce`, `libs.lettuce.core` 중 RED가 요구한 좌표 | runtime 필수 type-use는 명시적 `api` allowlist로 고정하고, `compileOnlyApi`는 optional declaration-only 좌표에 한정한다. `ApplicationContextRunner`의 classpath 존재/부재 검증 없이는 runtime 좌표를 낮추지 않는다. |
| notification | Resilience4j 공개 생성자 | 필요한 Resilience4j API artifact | `api` |
| notification | public auto-configuration과 configuration properties의 Spring 타입 | Spring Boot autoconfigure와 Spring Context 직접 좌표 | `compileOnlyApi` |

전이 의존성이 이미 필요한 타입을 제공하면 같은 좌표를 중복 승격하지 않는다. 반대로 fixture가 다른 공개 타입 누락을 검출하면 해당 source anchor와 제공 artifact를 확인한 뒤 후보 목록을 보완한다.

`LeaderGroupElector` 자체는 `bluetape4k-leader-core`가 제공하지만, 현재 공개 `NotificationAutoConfiguration.notificationLeaderElection`은 `StatefulRedisConnection`과 Lettuce 기반 구현 타입도 공개 선언에서 사용한다. 따라서 core artifact만 `api`로 올리고 Redis/Lettuce artifact를 무조건 `implementation`에 남기는 방안은 이 ABI 유지 설계와 맞지 않는다. 최종 RED가 Redis/Lettuce 좌표를 요구하면 이를 명시적 예외로 인정하되, notification consumer fixture configuration이 해석한 artifact 목록을 승인 목록으로 기록하고 source anchor 없이 추가된 Redis/Lettuce 전이가 없는지 `dependencyInsight`로 검증한다.

## 7. 실패 모드와 대응

### 7.1 fixture가 `runtimeElements`를 선택해 거짓으로 통과

원인: consumer configuration의 usage가 없거나 runtime usage로 설정됐다.

대응: `Usage.JAVA_API`와 라이브러리 variant 속성을 명시하고 dependency insight 또는 configuration report에서 선택 variant를 확인한다.

### 7.2 애플리케이션 의존성이 누락을 가림

원인: fixture가 `appointment-api` 또는 여러 대상 모듈을 함께 의존한다.

대응: fixture configuration마다 대상 project dependency 하나만 허용한다. fixture source도 모듈별 디렉터리와 compile task로 분리한다.

### 7.3 사용하지 않는 dependency까지 `api` 또는 `compileOnlyApi`로 전달

원인: source import 또는 runtime 사용만 보고 일괄 승격한다.

대응: RED 오류와 공개 시그니처 source anchor를 모두 확인한 dependency만 scope를 변경한다. 최종 `api`·`compileOnlyApi` dependency report를 설계 후보표와 대조한다.

### 7.4 일부 공개 선언만 검사해 누락이 남음

원인: fixture가 단순 DTO만 생성하고 외부 타입을 포함한 생성자와 메서드를 참조하지 않는다.

대응: Issue #336에서 확인한 supertype, constructor parameter, method parameter를 모듈별 fixture에 포함하고 compile 오류가 사라질 때까지 공개 surface 목록을 보완한다.

### 7.5 production 코드나 binary ABI가 함께 변경됨

원인: dependency scope 수정과 adapter visibility 정리를 한 변경에 섞는다.

대응: production Kotlin source 변경을 금지하고 branch diff에서 `build.gradle.kts`, fixture, 설계·계획·리뷰·lesson 문서만 허용한다. 예상 밖 production diff가 생기면 되돌리고 다시 검증한다.

## 8. 호환성과 운영 영향

### 8.1 source·binary compatibility

- 기존 Kotlin/Java 선언을 변경하지 않으므로 공개 source API와 JVM binary ABI는 유지한다.
- 같은 Gradle build에서 project dependency가 선택하는 `apiElements` compile dependency 범위는 넓어진다. 이는 누락된 공개 계약을 보완하는 의도한 metadata 변경이다.
- 이 저장소에는 대상 모듈의 publication 구성이 없으므로 생성 POM이나 외부 repository 소비를 이번 변경의 검증 결과로 주장하지 않는다.
- 소비자가 직접 선언하던 동일 dependency는 Gradle resolution에서 중복 좌표로 합쳐진다.

### 8.2 runtime

- `implementation`에서 `api`로 옮겨도 해당 dependency는 이전부터 runtime classpath에 있었다.
- `compileOnly`에서 `compileOnlyApi`로 옮기는 optional framework dependency는 consumer runtime classpath에 추가하지 않는다.
- 애플리케이션 bean wiring, 데이터베이스, Kafka, 알림 처리와 resource lifecycle은 변경하지 않는다.
- 따라서 runtime 행위 변경은 예상하지 않으며 기존 세 모듈 테스트로 회귀를 확인한다.

### 8.3 CI와 운영

- fixture는 compile-only 검증이므로 Docker, Testcontainers, Kafka broker 또는 데이터베이스가 필요하지 않다.
- 루트 build 시간이 세 개의 작은 Kotlin compile task만큼 증가한다.
- 실패 시 어떤 모듈의 공개 dependency contract가 깨졌는지 task 이름으로 식별할 수 있다.

성능 회귀는 구현 전 기준 branch와 구현 branch를 같은 JDK, Gradle daemon 조건, dependency cache 상태에서 비교한다.

- 기준 ref는 worktree base SHA `d1718331f1d418baf455d8046ad6cfc2e1567460`, 구현 ref는 검증 직전 `git rev-parse HEAD`로 고정하고 `performance.json`에 기록한다.
- 두 ref는 별도 clean worktree에서 JDK 25와 동일 Gradle wrapper를 사용한다. `GRADLE_OPTS=-Dorg.gradle.daemon=false`를 적용하고 첫 측정 전에 같은 dependency cache로 `--refresh-dependencies` 준비 실행을 한 번 수행한다.
- fixture task가 없는 기준 ref에는 fixture 시간을 만들지 않는다. 구현 ref에서만 `clean compileModuleConsumerFixtures --profile` cold compile 3회 중앙값을 신규 검증 비용으로 별도 기록하며 before/after 회귀 수치로 표현하지 않는다.
- 구현 ref의 fixture warm compile은 같은 task를 연속 실행해 모든 compile task가 `UP-TO-DATE`인지 확인하고 3회 중앙값을 기록한다.
- `:appointment-api:compileKotlin`과 CI 동일 compile-only build는 기준/구현 branch 각각 3회 중앙값을 기록한다.
- CI 동일 build의 구현 branch 중앙값은 기준보다 `max(10%, 30초)`를 초과하지 않아야 한다.
- `:appointment-api:compileKotlin` 중앙값은 기준보다 `max(10%, 5초)`를 초과하지 않아야 한다.
- 세 `apiElements` resolved classpath의 artifact 수와 파일 크기 합계 delta를 기록하고, RED source anchor로 설명되지 않는 artifact가 있으면 불필요한 승격으로 처리한다.

구조화된 증거는 `build/reports/consumer-fixtures/issue-336/` 아래에 둔다.

- `variants.json`: module별 선택 variant, attributes, resolved artifact 좌표
- `classpath.json`: 기준/구현 branch의 artifact 수와 파일 크기 합계
- `performance.json`: 명령, JDK/Gradle 조건, 세 실행값, 중앙값, threshold 판정

RED/GREEN 오류와 mutation 결과는 tracked 문서 `docs/verification/2026-08-16-issue-336-api-elements-boundary.md`에 명령·요약·관련 JSON 경로로 보존한다. CI와 Nightly compile-only build job은 consumer fixture report 디렉터리를 `if: always()` artifact로 업로드한다. raw console 전체나 절대 경로는 tracked 문서와 JSON에 넣지 않는다.

## 9. 검증

### 9.1 RED

```bash
./gradlew compileAppointmentCoreConsumerFixture
./gradlew compileAppointmentMessagingConsumerFixture
./gradlew compileAppointmentNotificationConsumerFixture
```

변경 전 dependency scope에서 각 task는 대상 공개 외부 타입의 접근 또는 해석 오류로 실패해야 한다. fixture 자체의 오타나 잘못된 API 사용으로 실패하면 RED 증거로 인정하지 않는다.

### 9.2 GREEN

```bash
./gradlew compileModuleConsumerFixtures
./gradlew assertModuleConsumerFixtureApiVariants --refresh-dependencies
./gradlew :appointment-core:test :appointment-messaging:test :appointment-notification:test --no-parallel
./gradlew clean compileModuleConsumerFixtures --no-daemon
./gradlew compileModuleConsumerFixtures --no-daemon
./gradlew build -x test -x :frontend:appointment-frontend:build --parallel --refresh-dependencies --no-daemon
./gradlew detekt --parallel --no-daemon
git diff --check
```

첫 번째 clean compile 뒤 같은 fixture compile 명령을 다시 실행했을 때 세 compile task는 `UP-TO-DATE`여야 한다. CI는 JDK 25에서 실행하지만 fixture compiler와 producer module target은 모두 JVM 21로 고정한다.

### 9.3 metadata 확인

```bash
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

`apiElements`는 consumable variant이므로 producer의 `dependencies --configuration apiElements`를 resolved graph 증거로 사용하지 않는다. `outgoingVariants`로 생산자 속성을 확인하고, 실제 resolved graph와 artifact 수·크기는 root consumer fixture configuration에서 수집한다. `./gradlew check --dry-run`으로 report, assertion, module compile, integration, root check 순서도 확인한다. 최종 dependency 목록을 RED 오류와 공개 source anchor에 대조하고 `:appointment-api:compileKotlin`, CI 동일 build의 3회 중앙값도 함께 기록한다.

## 10. 인수 기준

- 세 fixture는 각각 대상 모듈 하나만 의존한다.
- 변경 전 scope에서 세 fixture의 계약 위반이 재현된다.
- 공개 API 해석에 필요한 모든 외부 타입이 소비자 compile classpath에 존재한다.
- 불필요한 runtime-only 구현 dependency는 `implementation`에 남는다.
- production Kotlin source와 JVM binary ABI는 변경되지 않는다.
- 기존 core, messaging, notification 테스트가 통과한다.
- 루트 build와 Detekt가 통과한다.
- 기존 CI build 경로가 세 fixture compile task를 실행한다.
- CI가 consumer fixture 구조화 report를 성공·실패와 관계없이 업로드한다.
- variant assertion이 세 project dependency의 `apiElements`/`JAVA_API` 선택을 강제한다.
- clean 뒤 fixture output이 격리되고, 연속 실행에서 compile task가 `UP-TO-DATE`가 된다.
- 기준 branch 대비 compile 성능이 정한 회귀 예산을 넘지 않는다.
- `apiElements` 전이 graph의 증가는 공개 source anchor 또는 명시적 Redis/Lettuce 예외로 설명된다.
- 호환성 영향과 최종 승격 목록이 review, lesson, PR에 기록된다.

## 11. 완료 조건

- Type-A 설계·계획·리뷰 게이트에서 P0=0, P1=0이다.
- consumer fixture RED/GREEN 증거가 보존된다.
- 모듈 테스트, build, Detekt, `git diff --check`가 모두 통과한다.
- 공개 API와 `apiElements` dependency scope가 일치한다.
- PR CI가 정확한 head에서 성공하고 미해결 review blocker가 없다.
- 별도 머지 승인 전에는 PR을 머지하지 않는다.
