# Multi-DB 테스트 + Virtual Threads 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **MANDATORY:** Apply `bluetape4k-patterns` skill on ALL Kotlin code. No exceptions.

**Goal:** appointment-core 도메인 테스트를 H2/PostgreSQL/MySQL8 3개 DB에서 실행하고, appointment-api에 Virtual Threads를 활성화하며, CI에서 API 통합 테스트를 3-way matrix로 병렬 실행한다.

**Architecture:** appointment-core는 `AbstractExposedTest` + `@ParameterizedTest` + `withTables()` 패턴(bluetape4k 표준), appointment-api는 Spring Profile + Testcontainers `@DynamicPropertySource` 패턴으로 DB를 전환한다.

**Tech Stack:** Kotlin 2.3, Java 25, Exposed ORM v1 (JDBC), Spring Boot 4, JUnit 5 + Kluent, Testcontainers (PostgreSQL, MySQL8), Virtual Threads

**Spec:** `docs/superpowers/specs/2026-04-20-multi-db-test-virtual-threads-design.md`

---

## 파일 맵

### 신규 생성

| 파일 | 역할 |
|------|------|
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/TestDB.kt` | DB enum + `useFastDB`/`useDB` 시스템 프로퍼티 읽기 |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/AbstractExposedTest.kt` | `enableDialects()`, `ENABLE_DIALECTS_METHOD` |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/WithTables.kt` | `withTables(testDB, *tables) { ... }` 헬퍼 |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/WithDb.kt` | Semaphore 기반 직렬화, DB 캐시 |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/Containers.kt` | PostgreSQL/MySQL8 Testcontainers 싱글톤 |
| `appointment-api/src/main/resources/application-mysql.yml` | MySQL 프로파일 설정 |
| `appointment-api/src/test/resources/application-test-postgresql.yml` | PostgreSQL 테스트 프로파일 |
| `appointment-api/src/test/resources/application-test-mysql.yml` | MySQL 테스트 프로파일 |
| `appointment-api/src/test/kotlin/.../test/AbstractApiIntegrationTest.kt` | `@DynamicPropertySource` Testcontainers 연결 |

### 수정

| 파일 | 변경 내용 |
|------|---------|
| `appointment-core/build.gradle.kts` | R2DBC 의존성 제거, Testcontainers/JDBC 드라이버 추가, `systemProperty` 전달 |
| `appointment-core/src/test/kotlin/.../service/SlotCalculationServiceTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../service/ResolveMaxConcurrentTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../service/EquipmentUnavailabilityServiceTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../service/ClosureRescheduleServiceTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../service/UnavailabilityExpanderTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../service/ConcurrentBookingIntegrationTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../statemachine/AppointmentStateMachineTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../timezone/ClinicTimezoneServiceTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../model/tables/TableSchemaTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../repository/EquipmentUnavailabilityRepositoryTest.kt` | multi-DB 전환 |
| `appointment-core/src/test/kotlin/.../model/service/TimeRangeTest.kt` | multi-DB 전환 (DB 의존 여부 확인 필요) |
| `appointment-api/src/main/resources/application.yml` | `spring.threads.virtual.enabled: true` 추가 |
| `appointment-api/src/main/kotlin/.../config/DatabaseConfig.kt:37-39` | `SchemaInitConfig`에 `@ConditionalOnProperty` 추가 |
| `appointment-api/build.gradle.kts` | `bluetape4k_testcontainers` 의존성 추가 |
| `.github/workflows/ci.yml:177-218` | `test-api` Job matrix strategy 추가 |
| `.github/workflows/ci.yml:265-320` | `coverage-report` artifact 집계 수정 |
| `.github/workflows/ci.yml:391-407` | `ci-status` needs 확인 |

---

## Phase 1: appointment-core Multi-DB 지원

### T1 — exposed-workshop 5개 파일 로컬 복사
- **complexity**: medium
- **대상 파일**:
  - 원본: `/Users/debop/work/bluetape4k/exposed-workshop/00-shared/exposed-shared-tests/src/main/kotlin/exposed/shared/tests/TestDB.kt`
  - 원본: `.../AbstractExposedTest.kt`, `.../WithTables.kt`, `.../WithDb.kt`, `.../Containers.kt`
  - 복사 위치: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/` (5개 파일)
- **내용**:
  1. `exposed-workshop/00-shared/exposed-shared-tests/src/main/kotlin/exposed/shared/tests/` 에서 5개 파일 복사
  2. 패키지 선언을 `exposed.shared.tests` → `io.bluetape4k.clinic.appointment.test` 로 변경
  3. import 경로도 동일하게 변경
  4. `TestDB.kt`의 `useFastDB`는 시스템 프로퍼티 `exposed.test.useFastDB` 읽기 확인
  5. `Containers.kt`의 Testcontainers 싱글톤이 bluetape4k `MySQL8Server.Launcher`, `PostgreSQLServer.Launcher` 패턴 사용하는지 확인 후 필요 시 조정
- **완료 기준**: 5개 파일이 `io.bluetape4k.clinic.appointment.test` 패키지로 컴파일 성공
- **선행 태스크**: 없음

### T2 — appointment-core R2DBC 의존성 제거 + JDBC 테스트 의존성 추가
- **complexity**: medium
- **대상 파일**: `appointment-core/build.gradle.kts:1-25`
- **내용**:
  1. **main에서 제거**: `exposed_r2dbc` (L7), `bluetape4k_exposed_r2dbc` (L12), `kotlinx_coroutines_reactor` (L15)
  2. **test에서 제거**: `bluetape4k_exposed_r2dbc_tests` (L20), `r2dbc_h2` (L23)
  3. **test에 추가**:
     ```kotlin
     testImplementation(Libs.bluetape4k_testcontainers)
     testImplementation(Libs.testcontainers)
     testImplementation(Libs.testcontainers_postgresql)
     testImplementation(Libs.testcontainers_mysql)
     testImplementation(Libs.postgresql_driver)
     testImplementation(Libs.mysql_connector_j)
     ```
  4. `tasks.withType<Test>().configureEach {}` 블록 추가:
     ```kotlin
     tasks.withType<Test>().configureEach {
         val useFastDB = (project.findProperty("useFastDB") as? String)?.toBoolean() ?: false
         systemProperty("exposed.test.useFastDB", useFastDB)
         val useDB = (project.findProperty("useDB") as? String) ?: ""
         if (useDB.isNotBlank()) {
             systemProperty("exposed.test.useDB", useDB)
         }
     }
     ```
- **완료 기준**: `./gradlew :appointment-core:compileKotlin :appointment-core:compileTestKotlin` 성공
- **선행 태스크**: T1

### T3 — 도메인 테스트 11개 클래스 multi-DB 전환 (1차: 서비스 테스트 6개)
- **complexity**: high
- **대상 파일**:
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationServiceTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ResolveMaxConcurrentTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/EquipmentUnavailabilityServiceTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleServiceTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/UnavailabilityExpanderTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ConcurrentBookingIntegrationTest.kt`
- **내용**:
  1. `AbstractExposedTest()` 상속 추가 (`import io.bluetape4k.clinic.appointment.test.AbstractExposedTest`)
  2. `@Test` → `@ParameterizedTest @MethodSource(ENABLE_DIALECTS_METHOD)` 변환 + `testDB: TestDB` 파라미터 추가
  3. `@BeforeAll` / `@BeforeEach`에서 `Database.connect(...)`, `SchemaUtils.create()`, `deleteAll()` 제거
  4. 테스트 본문을 `withTables(testDB, *tables) { ... }` 블록으로 감싸기
  5. `transaction(db) { ... }` 제거 (withTables 내부에서 이미 transaction 컨텍스트)
  6. DB별 쿼리 동작 차이(예: ConcurrentBookingIntegrationTest의 락 동작)가 있으면 `assumeTrue` 또는 조건부 검증 추가
- **완료 기준**: `./gradlew :appointment-core:test -PuseFastDB=true` 에서 6개 테스트 클래스 모두 PASS
- **선행 태스크**: T1, T2

### T4 — 도메인 테스트 multi-DB 전환 (2차: 나머지 5개)
- **complexity**: high
- **대상 파일**:
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/statemachine/AppointmentStateMachineTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/timezone/ClinicTimezoneServiceTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentUnavailabilityRepositoryTest.kt`
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/service/TimeRangeTest.kt`
- **내용**:
  1. T3과 동일한 패턴 적용
  2. `TimeRangeTest.kt`는 DB 의존 여부 확인 — 순수 로직 테스트면 `@ParameterizedTest` 불필요, DB 사용 시만 전환
  3. `TableSchemaTest.kt`는 DDL 생성 검증이므로 DB별 dialect 차이를 명시적으로 테스트
  4. `EquipmentUnavailabilityRepositoryTest.kt`는 Repository 레이어이므로 withTables 패턴 적용
- **완료 기준**: `./gradlew :appointment-core:test -PuseFastDB=true` 에서 전체 11개 테스트 클래스 모두 PASS
- **선행 태스크**: T3

### T5 — appointment-core 3개 DB 전체 테스트 실행 검증
- **complexity**: high
- **대상 파일**: 없음 (실행 검증)
- **내용**:
  1. H2만 실행: `./gradlew :appointment-core:test -PuseFastDB=true` — 빠른 스모크
  2. H2 + PostgreSQL + MySQL8 전체: `./gradlew :appointment-core:test` — Testcontainers 기동 포함
  3. 특정 DB만: `./gradlew :appointment-core:test -PuseDB=PostgreSQL,MySQLV8`
  4. 실패 시 DB dialect별 DDL/쿼리 차이 분석 후 수정
  5. `testlog` 기록: 각 DB별 테스트 수행 결과 (PASS/FAIL 수, 소요 시간)
- **완료 기준**: 3개 DB 모두 전체 테스트 PASS, 각 DB별 테스트 결과 기록
- **선행 태스크**: T4

---

## Phase 2: appointment-api Virtual Threads + Multi-DB

### T6 — Virtual Threads 설정 (application.yml)
- **complexity**: low
- **대상 파일**: `appointment-api/src/main/resources/application.yml:10-12`
- **내용**:
  1. `spring.threads.virtual.enabled: true` 추가
  2. 기존 설정 구조 유지 (datasource, flyway 설정 아래)
  3. 변경 후:
     ```yaml
     spring:
         application:
             name: appointment-api
         datasource:
             url: jdbc:h2:mem:appointment;DB_CLOSE_DELAY=-1
             driver-class-name: org.h2.Driver
         flyway:
             enabled: false
             locations: classpath:db/migration/{vendor}
         threads:
             virtual:
                 enabled: true
     server:
         port: 8080
     ```
- **완료 기준**: `./gradlew :appointment-api:bootRun` 기동 후 요청 처리 스레드가 VirtualThread인지 확인 (`Thread.currentThread().isVirtual` 로그 또는 actuator 엔드포인트)
- **선행 태스크**: 없음

### T7 — SchemaInitConfig `@ConditionalOnProperty` 추가
- **complexity**: medium
- **대상 파일**: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt:37-39`
- **내용**:
  1. `SchemaInitConfig` 클래스에 `@ConditionalOnProperty` 추가:
     ```kotlin
     @Configuration(proxyBeanMethods = false)
     @Profile("dev", "test")
     @ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "false", matchIfMissing = true)
     class SchemaInitConfig {
     ```
  2. import 추가: `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`
  3. 기존 `@Profile("dev", "test")` 유지
  4. `SchemaUtils.create(...)` 목록에 누락된 테이블 추가 확인:
     - `EquipmentUnavailabilities`, `EquipmentUnavailabilityExceptions` 포함 여부 점검
     - 누락되어 있으면 해당 테이블을 목록에 추가 (H2 테스트 실패 방지)
- **완료 기준**: `test` 프로파일 + `flyway.enabled=false` → SchemaInitConfig 활성화, `test-postgresql` + `flyway.enabled=true` → 비활성화. H2 테스트에서 모든 도메인 테이블 생성 확인.
- **선행 태스크**: 없음

### T8 — appointment-api 프로파일 YAML 신규 파일 생성
- **complexity**: low
- **대상 파일**:
  - 신규: `appointment-api/src/main/resources/application-mysql.yml`
  - 신규: `appointment-api/src/test/resources/application-test-postgresql.yml`
  - 신규: `appointment-api/src/test/resources/application-test-mysql.yml`
- **내용**:
  1. `application-mysql.yml`:
     ```yaml
     spring:
         datasource:
             url: jdbc:mysql://localhost:3306/appointment?useSSL=false&characterEncoding=UTF-8&serverTimezone=UTC
             driver-class-name: com.mysql.cj.jdbc.Driver
             username: test
             password: test
         flyway:
             enabled: true
             locations: classpath:db/migration/mysql
             baseline-on-migrate: true
     ```
  2. `application-test-postgresql.yml`:
     ```yaml
     spring:
         config:
             import: optional:classpath:application-flyway.yml
         datasource:
             driver-class-name: org.postgresql.Driver
         flyway:
             locations: classpath:db/migration/postgresql
     ```
  3. `application-test-mysql.yml`:
     ```yaml
     spring:
         config:
             import: optional:classpath:application-flyway.yml
         datasource:
             driver-class-name: com.mysql.cj.jdbc.Driver
         flyway:
             locations: classpath:db/migration/mysql
     ```
- **완료 기준**: YAML 구문 유효성, Spring Profile 로딩 확인
- **선행 태스크**: 없음

### T9 — appointment-api build.gradle.kts 의존성 추가
- **complexity**: low
- **대상 파일**: `appointment-api/build.gradle.kts:47-51`
- **내용**:
  1. `bluetape4k_testcontainers` 테스트 의존성 추가:
     ```kotlin
     testImplementation(Libs.bluetape4k_testcontainers)
     ```
  2. 기존 Testcontainers 의존성(`testcontainers`, `testcontainers_junit_jupiter`, `testcontainers_postgresql`, `testcontainers_mysql`)은 이미 존재 — 추가 불필요
  3. `tasks.withType<Test>().configureEach {}` 블록에 `spring.profiles.active` 시스템 프로퍼티 포워딩 추가:
     ```kotlin
     tasks.withType<Test>().configureEach {
         val profile = System.getProperty("spring.profiles.active")
         if (!profile.isNullOrBlank()) {
             systemProperty("spring.profiles.active", profile)
         }
     }
     ```
     → CI에서 `-Dspring.profiles.active=test,test-postgresql` 전달 시 Gradle 테스트 JVM으로 포워딩됨
- **완료 기준**: `./gradlew :appointment-api:dependencies --configuration testCompileClasspath` 에서 `bluetape4k_testcontainers` 확인
- **선행 태스크**: 없음

### T10 — AbstractApiIntegrationTest + TestcontainersDatabaseInitializer
- **complexity**: high
- **대상 파일**: 신규 `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/AbstractApiIntegrationTest.kt`
- **내용**:
  1. `AbstractApiIntegrationTest` 추상 클래스 생성
  2. `@DynamicPropertySource`로 Testcontainers DataSource 연결:
     ```kotlin
     abstract class AbstractApiIntegrationTest {
         companion object {
             @JvmStatic
             @DynamicPropertySource
             fun configureProperties(registry: DynamicPropertyRegistry) {
                 val profile = System.getProperty("spring.profiles.active", "test")
                 when {
                     profile.contains("postgres") -> {
                         val pg = PostgreSQLServer.Launcher.postgres
                         registry.add("spring.datasource.url") { pg.jdbcUrl }
                         registry.add("spring.datasource.username") { pg.username }
                         registry.add("spring.datasource.password") { pg.password }
                     }
                     profile.contains("mysql") -> {
                         val mysql = MySQL8Server.Launcher.mysql
                         registry.add("spring.datasource.url") { mysql.jdbcUrl }
                         registry.add("spring.datasource.username") { mysql.username }
                         registry.add("spring.datasource.password") { mysql.password }
                     }
                 }
             }
         }
     }
     ```
  3. `bluetape4k_testcontainers`의 `PostgreSQLServer.Launcher`, `MySQL8Server.Launcher` 사용
  4. 기존 API 통합 테스트 클래스들이 `AbstractApiIntegrationTest`를 상속하도록 변경
- **완료 기준**: `test`, `test-postgresql`, `test-mysql` 3개 프로파일에서 DataSource 연결 성공
- **선행 태스크**: T7, T8, T9

### T11 — appointment-api Flyway 통합 테스트 (PostgreSQL, MySQL)
- **complexity**: medium
- **대상 파일**: 기존 Flyway 마이그레이션 테스트 확인
- **내용**:
  1. 기존 `FlywayPostgreSQLMigrationTest`, `FlywayMySQLMigrationTest`는 Flyway + Testcontainers를 직접 구성하는 독립 테스트이므로 `AbstractApiIntegrationTest` 상속으로 변경하지 않는다
  2. `test-postgresql` 프로파일: Flyway migration + 통합 테스트 실행 확인
  3. `test-mysql` 프로파일: Flyway migration + 통합 테스트 실행 확인
  4. 각 프로파일에서 API 엔드포인트 호출 테스트가 정상 동작하는지 검증
- **완료 기준**:
  - `./gradlew :appointment-api:test -Dspring.profiles.active=test` PASS
  - `./gradlew :appointment-api:test -Dspring.profiles.active=test,test-postgresql` PASS
  - `./gradlew :appointment-api:test -Dspring.profiles.active=test,test-mysql` PASS
- **선행 태스크**: T10

---

## Phase 3: GitHub CI 변경

### T12 — CI test-api Job matrix strategy 추가
- **complexity**: high
- **대상 파일**: `.github/workflows/ci.yml:177-218`
- **내용**:
  1. 기존 `test-api` Job을 matrix strategy로 변환:
     ```yaml
     test-api:
         name: Test / API (${{ matrix.db }})
         runs-on: ubuntu-latest
         needs: build
         strategy:
           fail-fast: false
           matrix:
             include:
               - db: h2
                 spring-profile: test
               - db: postgresql
                 spring-profile: test,test-postgresql
               - db: mysql
                 spring-profile: test,test-mysql
     ```
  2. 테스트 실행 명령에 `-Dspring.profiles.active=${{ matrix.spring-profile }}` 추가
  3. `TESTCONTAINERS_RYUK_DISABLED: "true"` 환경변수 추가
  4. Artifact 이름에 `${{ matrix.db }}` suffix 추가:
     - `test-results-api-${{ matrix.db }}`
     - `coverage-api-${{ matrix.db }}`
- **완료 기준**: CI YAML 구문 유효, matrix 3개 Job 정의 확인
- **선행 태스크**: T11

### T13 — CI test-core Job Testcontainers 환경변수 추가
- **complexity**: low
- **대상 파일**: `.github/workflows/ci.yml:80-131`
- **내용**:
  1. `test-core` Job의 "Test core & event modules" step에 `TESTCONTAINERS_RYUK_DISABLED: "true"` 추가
  2. 기존 단일 Job 구조 유지 (matrix 불필요 — `@ParameterizedTest`로 단일 JVM 내 3 DB 순차 실행)
- **완료 기준**: test-core Job에서 TESTCONTAINERS_RYUK_DISABLED 환경변수 존재
- **선행 태스크**: T5

### T14 — CI coverage-report Job `needs` 목록 확인
- **complexity**: low
- **대상 파일**: `.github/workflows/ci.yml:265-320`
- **내용**:
  1. 실제 `coverage-report` Job은 artifact를 다운로드하지 않고 `./gradlew jacocoTestReport`를 직접 재실행함 — artifact 다운로드 로직 변경 불필요
  2. `needs` 목록에 matrix `test-api`가 포함되어 있는지만 확인 (GitHub Actions는 matrix 전체 완료를 자동 대기)
  3. 필요 시 `needs: [build, test-core, test-api]` 형태로 확인
- **완료 기준**: `coverage-report` Job이 matrix `test-api` 3개 전부 완료 후 실행됨 확인
- **선행 태스크**: T12

### T15 — CI ci-status Job needs 확인
- **complexity**: low
- **대상 파일**: `.github/workflows/ci.yml:391-407`
- **내용**:
  1. `ci-status` Job의 `needs` 목록에서 `test-api`가 matrix로 변경되어도 GitHub Actions가 자동으로 모든 matrix 조합 완료를 기다림
  2. 추가 변경 불필요 확인
  3. `RESULTS` 검증 스크립트가 matrix 결과를 올바르게 처리하는지 확인
- **완료 기준**: CI YAML 유효성 검증, matrix test-api 전체 완료 대기 확인
- **선행 태스크**: T12

---

## Phase 4: 검증 및 문서화

### T16 — 전체 테스트 실행 + testlog 기록
- **complexity**: medium
- **대상 파일**: 없음 (실행 검증)
- **내용**:
  1. appointment-core 3 DB 전체 실행: `./gradlew :appointment-core:test`
  2. appointment-api 3개 프로파일 실행:
     - `./gradlew :appointment-api:test` (H2)
     - `./gradlew :appointment-api:test -Dspring.profiles.active=test,test-postgresql`
     - `./gradlew :appointment-api:test -Dspring.profiles.active=test,test-mysql`
  3. Virtual Threads 활성화 검증: `./gradlew :appointment-api:bootRun` 기동 후 요청 처리 스레드가 VirtualThread인지 로그로 확인 (`Thread.currentThread().isVirtual` 또는 actuator `/actuator/info` 확인)
  4. 각 실행 결과를 testlog로 기록 (PASS/FAIL 수, 소요 시간, DB별 특이사항)
- **완료 기준**: 모든 테스트 PASS, 요청 처리 스레드 isVirtual=true 확인
- **선행 태스크**: T5, T11

### T17 — README / CLAUDE.md / superpowers index 업데이트
- **complexity**: low
- **대상 파일**:
  - `README.md`: Multi-DB 테스트 실행 방법, Virtual Threads 설정 안내 추가
  - `README.ko.md`: README.md와 동기화 (한국어)
  - `CLAUDE.md`: Build 섹션에 multi-DB 테스트 실행 커맨드 추가, Module Gotchas에 Testcontainers 관련 노트 추가
  - `docs/superpowers/index/2026-04.md`: 파일이 없으면 신규 생성 후 엔트리 추가
  - `docs/superpowers/INDEX.md`: 파일이 없으면 신규 생성 후 카운트 기록
- **내용**:
  1. README.md + README.ko.md에 추가:
     - `./gradlew :appointment-core:test -PuseFastDB=true` (H2만 빠르게)
     - `./gradlew :appointment-core:test` (3 DB 전체)
     - `./gradlew :appointment-api:test -Dspring.profiles.active=test,test-postgresql`
     - Virtual Threads 활성화 상태 안내
  2. CLAUDE.md Build 섹션 업데이트
  3. `docs/superpowers/index/` 디렉토리가 없으면 생성, `2026-04.md` 신규 생성 후 Evolution Event 형식으로 엔트리 추가 (what / why / verification)
  4. `docs/superpowers/INDEX.md` 신규 생성 또는 업데이트 (✅/⏳ 건수 반영)
- **완료 기준**: 문서 내용 정확성, 실행 가능한 커맨드 기술, README.md와 README.ko.md 동기화 확인
- **선행 태스크**: T16

---

## 태스크 의존 관계 요약

```
T1 (테스트 지원 파일 복사)
 └→ T2 (build.gradle.kts 정리)
     └→ T3 (서비스 테스트 6개 전환)
         └→ T4 (나머지 테스트 5개 전환)
             └→ T5 (core 3-DB 검증)
                 └→ T13 (CI test-core 수정)
                 └→ T16 (전체 검증)

T6 (Virtual Threads 설정)   ──┐
T7 (SchemaInitConfig)        ├→ T10 (AbstractApiIntegrationTest)
T8 (프로파일 YAML)           │   └→ T11 (API 통합 테스트 검증)
T9 (API build.gradle.kts)  ──┘       └→ T12 (CI test-api matrix)
                                          ├→ T14 (coverage-report 수정)
                                          └→ T15 (ci-status 확인)

T5 + T11 → T16 (전체 검증) → T17 (문서 업데이트)
```

---

## 검증 기준

| 항목 | 기준 |
|------|------|
| Core 테스트 | H2, PostgreSQL, MySQL8 모두 전체 테스트 통과 |
| API 테스트 | `test`, `test-postgresql`, `test-mysql` 3개 프로파일 모두 통합 테스트 통과 |
| CI | test-core 단일 Job (3 DB 내부 순차) + test-api 3-way matrix 모두 녹색 |
| Virtual Threads | `spring.threads.virtual.enabled=true` 상태에서 서버 기동 및 요청 처리 정상 |
| 기존 테스트 호환 | H2 기본 실행 시 기존과 동일한 테스트 결과 |
| R2DBC 제거 | `appointment-core`에 R2DBC 관련 의존성 0개 |
