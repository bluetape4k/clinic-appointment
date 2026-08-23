# Issue #315 조회 전용 Spring Data projection pilot 설계

## 결정 대상

현재 `appointment-core`의 `ClinicRepository`는 `Clinics` Table DSL과
`ClinicRecord` 매핑을 직접 조합한다. 이슈 #315는 쓰기 모델과 기존
Composite repository를 바꾸지 않고, 조회 전용 projection에
`bluetape4k-exposed-spring-boot-jdbc`의 `ExposedJdbcRepository`를 적용할 수
있는지 검증한 뒤 채택 또는 보류를 결정하는 작업이다.

이 문서는 Type A prototype의 구현 경계와 검증 계약을 고정한다. API 경로를
전환하거나 `appointment-core`의 저장소를 교체하는 설계가 아니다.

## 근거와 미확정 사항

| 근거 | 현재 확인 결과 | 설계에 미치는 영향 |
|---|---|---|
| GitHub Issue [#315](https://github.com/bluetape4k/clinic-appointment/issues/315) | 후보 aggregate 1~2개, 결과·정렬·tenant 격리·transaction 경계·생성 SQL·변환 비용 비교를 요구한다. | `Clinics` 한 aggregate만 pilot으로 고정하고 기존 API를 유지한다. |
| `appointment-core/.../ClinicRepository.kt` | `LongJdbcRepository<ClinicRecord>`와 `findByTenant()`의 ID 오름차순 Table DSL 조회를 사용한다. | 비교 기준은 기존 `ClinicRecord` 목록으로 삼는다. |
| `appointment-core/.../model/tables/Clinics.kt` | `LongIdTable("scheduling_clinics")`이며 `tenantGroupId`를 포함한다. | DAO Entity가 같은 `Clinics` table을 사용하고 tenant predicate를 SQL에 포함해야 한다. |
| `appointment-api/build.gradle.kts` | `bluetape4k-exposed-spring-boot-jdbc`가 이미 test scope에 있다. | pilot은 test source set에 한정해 runtime 의존성과 ABI를 늘리지 않는다. |
| `ExposedAutoConfiguration` source | 애플리케이션이 이미 `springTransactionManager`를 등록한다. | 본 애플리케이션에 `@EnableExposedJdbcRepositories`를 바로 추가하지 않는다. 별도 test context에서 해당 자동 구성을 제외하고 Spring Data 자동 구성을 검증한다. |
| resolved artifact `bluetape4k-exposed-spring-boot-jdbc:1.12.1` | `ExposedJdbcRepository`, `@EnableExposedJdbcRepositories`, `@ExposedEntity`, PartTree query가 제공된다. `@Query`는 raw SQL로 ID를 읽은 뒤 row별 `findById()`를 수행한다. | N+1이 발생하는 `@Query`를 쓰지 않고, `findByTenantGroupIdOrderByIdAsc` PartTree 경로를 사용한다. |
| upstream `DeclaredExposedQuery.kt` / `PartTreeExposedQuery.kt` | raw `@Query`는 row별 `findById()`를 수행하고, PartTree는 `EntityClass.find { op }` 후 sort를 적용한다. | generated SQL statement 수를 acceptance에 포함하고 PartTree 경로만 측정한다. |
| 기준 검증 | `./gradlew :appointment-api:test`에서 829 passing, 3 pending, BUILD SUCCESSFUL. | baseline은 새 pilot 결과와 분리해 기록한다. |

다음 사항은 구현 테스트로 확인한다.

- PartTree의 `EntityID<Long>` tenant 인자가 `reference` column에 올바르게
  바인딩되는가.
- DAO Entity 조회가 현재 Spring transaction의 Exposed `Transaction`과 같은
  `DataSource` 경계를 사용하는가.
- Spring Data 자동 구성과 JetBrains `ExposedAutoConfiguration`을 분리한
  test context가 현재 Spring Boot 4 조합에서 안정적으로 시작하는가.

## 목표와 제외 범위

### 목표

1. `Clinics`를 읽기 전용 DAO Entity로 표현하고
   `ExposedJdbcRepository`에서 tenant 조건과 `id ASC` 정렬을 SQL로 실행한다.
2. 같은 fixture와 같은 Spring transaction 안에서 기존 `ClinicRepository`와
   pilot 결과가 `ClinicRecord` 기준으로 일치함을 검증한다.
3. 다른 tenant의 row가 결과에 섞이지 않는지 검증한다.
4. Spring transaction manager, Exposed current transaction, application
   `DataSource`가 같은 경계를 사용하는지 검증한다. 테스트 adapter가
   `Database`를 직접 주입하지 않는다는 점도 함께 고정한다.
5. 생성 SQL의 tenant predicate와 `ORDER BY id ASC`, DAO Entity→DTO 변환
   비용, 대표 실행 시간과 SQL statement 수를 evidence로 남긴다.
6. 결과에 따라 “조회 전용 소규모 projection에는 적용 가능” 또는 “현재는
   보류”를 선택하고 적용·비적용 대상을 문서화한다.

### 제외 범위

- `/api/{tenantCode}/clinics`를 pilot repository로 전환하지 않는다.
- `appointment-core`의 `ClinicRepository` 또는 다른 Composite repository를
  교체하지 않는다.
- `Clinics` 외 aggregate, 쓰기 경로, DAO Entity를 production source에
  추가하지 않는다.
- cursor/keyset API, pagination 계약, 새로운 dependency version, schema
  migration을 도입하지 않는다.
- 운영 환경의 `springTransactionManager` 자동 구성 순서를 변경하지 않는다.

## 선택지와 결정

### A. 테스트 전용 pilot — 채택

`appointment-api/src/test`에 `Clinics` DAO Entity, Spring Data repository,
DTO adapter, 전용 Spring context를 둔다. 전용 context는
`org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration`
과 Spring Data JDBC 기본 repository 자동 구성을 제외하고,
`@EnableExposedJdbcRepositories`가 만드는 Spring transaction manager와
repository proxy만 검증한다.

이 선택은 이슈가 요구한 “작은 prototype”에 맞고, 현재 애플리케이션의 기존
transaction wiring과 public ABI를 건드리지 않는다. 실제 adoption을 결정할
때 필요한 SQL·tenant·transaction·변환 비용 evidence도 얻을 수 있다.

### B. 비활성화된 production pilot — 보류

`appointment.clinic.spring-data-projection.enabled=false` 조건부 설정과
runtime dependency를 추가하는 방법이다. 기본 경로는 보존할 수 있지만,
활성화 시 이미 존재하는 JetBrains `springTransactionManager`와 Spring Data
자동 구성의 ordering/name 충돌을 별도로 운영 검증해야 한다. 현재 issue의
판단 목적에 비해 운영 wiring 변경 면적이 크므로 이번 pilot에서는 선택하지
않는다.

### C. 기존 repository 교체 — 폐기

Table DSL 모델 전체를 DAO Entity로 바꾸면 쓰기 경로와 Composite repository의
책임 경계가 흔들리고, issue의 제외 범위를 위반한다. 결과 비교를 위해 필요한
최소 projection 검증보다 범위가 크므로 폐기한다.

## 구성 요소와 데이터 흐름

### 테스트 전용 구성 요소

| 구성 요소 | 위치/책임 |
|---|---|
| `ClinicProjectionEntity` | `Clinics`를 읽는 `@ExposedEntity`/`LongEntity`; `tenantGroupId`, 이름, 슬롯·지역 설정을 읽기 전용 adapter에 제공한다. |
| `ClinicProjectionRepository` | 전용 `projection` test package의 `internal` `ExposedJdbcRepository<ClinicProjectionEntity, Long>`; `table = Clinics`와 `extractId(entity) = entity.id.value.takeIf { it != 0L }` 계약을 명시한다. `findByTenantGroupIdOrderByIdAsc(EntityID<Long>)` PartTree 메서드는 test-internal 계약으로 숨기며 tenant predicate와 ID 오름차순을 한 번의 EntityClass query로 실행한다. Spring Data가 노출하는 `save`/`delete` surface는 adapter에서 호출하지 않고 production 경계로 전달하지 않는다. 기존 `ClinicRepository`의 drop-in replacement가 아니다. |
| `ClinicProjectionAdapter` | 전용 `projection` test package의 `internal` caller-facing test adapter `findByTenant(tenantGroupId: Long)`를 제공하고 내부에서 `EntityID<Long>`를 `TenantGroups` table에 결합한다. `tenantGroupId <= 0`은 `IllegalArgumentException`으로 거부하고, 존재하지 않는 tenant는 기존 조회와 같이 빈 목록으로 반환한다. DAO Entity나 Spring Data 타입을 production API로 노출하지 않는다. 한국어 KDoc과 `findByTenant(tenantGroupId = 42L)` 호출 예를 둔다. |
| 전용 Spring test context | `ApplicationContextRunner.withUserConfiguration(PilotTestConfiguration::class)`의 명시적 allow-list로만 구성한다. `@EnableExposedJdbcRepositories(basePackageClasses = [ClinicProjectionRepository::class], transactionManagerRef = "springTransactionManager")`로 전용 package 하나만 스캔하고, `AppointmentApiApplication`과 broad component scan, JetBrains `ExposedAutoConfiguration`은 사용하지 않는다. H2 또는 `test-postgresql` `DataSource`를 구성하고 context 종료 때 생성된 Exposed `Database` registration을 해제한다. 전역 registry 감시 helper가 refresh 성공·실패 모두에서 manager가 등록한 handle을 식별한다. |
| 비교 fixture | 두 tenant와 각 tenant의 여러 clinic을 `SchemaUtils.createMissingTablesAndColumns` 및 `Table.deleteAll()` 규칙으로 초기화한다. |

호출 흐름은 다음과 같다.

```text
TransactionTemplate(springTransactionManager)
  ├─ ClinicRepository.findByTenant(tenantId)       -> ClinicRecord 목록
  └─ ClinicProjectionAdapter.findByTenant(tenantId)
       -> ClinicProjectionRepository.findByTenantGroupIdOrderByIdAsc(EntityID<Long>)
       -> ClinicProjectionEntity 목록
       -> ClinicProjectionAdapter                    -> ClinicRecord 목록
```

두 결과는 같은 tenant, 같은 fixture에서 비교하지만 caller contract는 다르다.
기존 `ClinicRepository`는 `Exposed transaction {}` 경계에서 호출하고,
candidate adapter는 `TransactionTemplate(springTransactionManager)` 안의
Spring-managed transaction만 지원한다. 따라서 candidate는 기존 repository의
drop-in replacement나 `/api/{tenantCode}/clinics` pagination 계약이 아니다.
Spring Data repository의 PartTree SQL에는 tenant 조건과 `ORDER BY id ASC`를
명시해 결과 정렬을 JVM 순서에 의존하지 않는다. upstream
`DeclaredExposedQuery`의 raw SQL 경로는 ID를 읽은 뒤 row마다 `findById()`를
호출해 N+1이 되므로 이 pilot에서 금지한다.

## 오류·경계 처리

- adapter의 `tenantGroupId: Long`은 필수 non-null 입력이며 `tenantGroupId <= 0`은
  `IllegalArgumentException`으로 거부한다. 존재하지 않는 양수 tenant는 기존
  `ClinicRepository.findByTenant()`와 같은 빈 목록 결과로 고정한다. 다른
  tenant row를 반환하면 테스트를 실패시키고, 조회 결과를 메모리에서 tenant로
  걸러서 통과시키지 않는다.
- SQL의 tenant predicate는 데이터 격리일 뿐 인증·인가가 아니다. 향후 route에
  채택하더라도 `TenantClinicAccessChecker.requireTenant()`와 기존 principal
  경계를 먼저 유지해야 하며, 이번 test-only pilot에는 인증 principal이 없다.
- `ExposedJdbcRepository`의 `save`·`delete` 상속 surface와
  `ClinicProjectionRepository` 자체를 adapter 밖에서 호출하지 않는다. 읽기
  전용 의미는 test-internal package와 adapter 경계로 제한하고, production
  read-only API로 과장하지 않는다.
- `ClinicProjectionRepository.table`은 `Clinics`와 동일 객체여야 하며,
  `extractId(entity)`는 새 Entity에서 `null`을 허용하도록
  `entity.id.value.takeIf { it != 0L }` 계약을 고정한다. 이 계약이 upstream
  factory 기대와 다르면 context를 통과시키지 않는다.
- PartTree가 `tenant_group_id` reference를 올바르게 바인딩하지 못하거나
  generated SQL에 predicate/order가 없으면 pilot을 보류하고 raw `@Query`
  fallback으로 변환하지 않는다.
- `SimpleExposedJdbcRepository`의 proxy-managed `@Transactional(readOnly =
  true)`는 library 동작으로 인정한다. pilot adapter는 `TransactionTemplate`로
  caller-owned Spring transaction을 명시해 관련 조회를 한 경계에 묶고,
  transaction 밖 호출을 별도 production 계약으로 만들지 않는다.
- `DataSource` 또는 Exposed current transaction이 분리되면 결과 비교보다
  먼저 실패시킨다. 별도 `Database`를 주입해 우회하지 않는다.
- DAO Entity mapping이 nullable/default semantics를 잃으면
  `ClinicRecord` equality 비교가 실패한다. 필드별 매핑과 serializable DTO
  계약을 그대로 검증한다.
- 성능 측정은 adoption의 단독 근거로 사용하지 않는다. SQL 수, 결과 동일성,
  tenant 격리, transaction 경계가 우선이며, 측정값은 환경과 반복 수를 함께
  기록한다.
- DAO Entity는 table row를 로드하므로 현재 artifact가 제공하는 경로는
  논리적 projection adapter이지 column-level `SELECT` projection이 아니다.
  test-only 경계에서는 synthetic fixture만 사용하고, 민감 column이 추가된
  production table에 이 경로를 채택하지 않는다. column-level projection은
  upstream closed projection 계약 또는 별도 후속 이슈로 분리한다.

## 호환성과 되돌리기

- production source, endpoint, `appointment-core` API, schema, dependency
  graph는 변경하지 않는다. 따라서 pilot 제거는 test fixture와 문서 삭제로
  끝난다.
- test context가 시작되지 않거나 결과가 불일치하면 새 production 경로를
  추가하지 않고 fixture를 revert한다.
- `SpringTransactionManager`가 내부적으로 만든 `Database`는 첫 실제
  transaction의 `TransactionManager.current().db`로 capture한다. startup 전에
  전용 JUnit resource write lock을 획득하고, test runner는 이전
  `TransactionManager.defaultDatabase`와 `TransactionManager.primaryDatabase`를
  함께 보존한 뒤 context를 만드는 동안 default를 일시적으로 `null`로 둔다.
  따라서 refresh 중 repository scan 또는 DataSource 초기화가 실패해도
  `primaryDatabase`의 registration diff로 새 manager handle을 식별할 수 있다.
  refresh 성공 시에는 첫 `TransactionTemplate`에서 capture한 `current().db`가
  그 diff와 같은지 확인하고, 둘 중 하나라도 다르면 즉시 실패시킨다. 이
  추적은 narrow context 하나만 생성하고 lock으로 병렬 registration을 막는
  조건에서만 허용한다.
- context close와 transaction callback은 항상 먼저 commit/rollback을 끝내고
  Spring `TransactionSynchronizationManager` unbind 및
  `TransactionManager.currentOrNull()` 해제를 확인한다. 그 다음 새로
  식별한 handle만 `closeAndUnregister`하고, 마지막에 이전 default database를
  복원한다. context startup·test assertion·context close·cleanup 중 어느
  단계에서 예외가 나도 이 순서를 `try/finally`에서 보장하며, cleanup 예외는
  원래 예외에 suppressed exception으로 붙인다. Spring `DataSource` pool은
  Spring context가 소유하므로 Exposed cleanup이 pool을 닫지 않는다.
- 향후 채택 시 별도 이슈에서 production auto-configuration 충돌,
  repository package 경계, transaction manager 이름, pagination/projection
  계약을 다시 설계한다. 이번 pilot의 결과를 그 작업의 승인으로 간주하지
  않는다.

## 검증 계약과 완료 조건

### 필수 테스트

1. **동일 결과·정렬** — 기존 `findByTenant()`와
   `ClinicProjectionAdapter.findByTenant(Long)` 결과가 모든 필드와 `id ASC`
   순서에서 같다.
2. **tenant 격리·입력 계약** — tenant A 조회가 tenant B row를 반환하지
   않는다. 양수 unknown tenant는 빈 목록이고, 0/음수 입력은
   `IllegalArgumentException`인지 함께 확인한다.
3. **transaction/DataSource 경계** — `TransactionTemplate` 안에서 Spring
   synchronization resource와 Exposed current transaction이 활성화되고,
   `DataSourceUtils.getConnection(dataSource)`와
   `TransactionManager.current().connection.connection`의 물리 connection
   identity가 일치하며, repository factory의 `transactionManagerRef`가
   `springTransactionManager`임을 확인한다. `@EnableExposedJdbcRepositories`
   annotation과 생성된 repository factory bean definition의
   `transactionManagerRef`/`transactionManager` property를 모두 read-back해
   다른 manager fallback이 없음을 확인한다.
4. **SQL evidence** — `StatementInterceptor`로 captured SQL에
   `tenant_group_id` predicate와 `order by id asc`가 포함되고, clinic row당
   추가 `findById` SQL 없이 대표 조회가 한 SELECT로 끝나는지 확인한다.
5. **비활성 production 경계** — 기존 `appointment-api` context에 pilot
   repository bean이나 route 변경이 없음을 확인한다. `runtimeClasspath`와
   `bootJar`에는 `bluetape4k-exposed-spring-boot-jdbc`와 test-only pilot
   class가 포함되지 않는지 dependency/artifact read-back으로 확인한다.
6. **변환 비용/대표 성능** — tenant별 clinic row를 4·32·128건으로 구성하고,
   각 cardinality에서 5회 warm-up 후 30회 측정한다. legacy 조회와 Spring
   Data Entity 조회의 측정값은 각각 하나의 `TransactionTemplate` 실행에
   transaction begin/commit과 repository 호출을 포함하고, 같은 종류의
   `DataSource`/pool 조건으로 분리 실행한다. 각 sample에서 legacy total
   (transaction + Table DSL 조회 + `ResultRow→ClinicRecord`)와 candidate total
   (transaction + PartTree Entity 조회 + Entity→`ClinicRecord` 변환)을 반드시
   같은 측정 단위로 기록하고, query-only/conversion-only 값은 원인 분석용
   component timing으로 별도 남긴다. median/p95(ns)와 statement count를
   기록한다. H2와 `test-postgresql` singleton 실행을
   구분하고, JVM/DB/dialect/pool 조건과 raw Markdown evidence 경로를 함께
   남긴다. SQL interceptor 자체는 timed loop 밖에서 실행한다.
7. **context/fixture cleanup** — 전용 context를 만들기 전에
   `@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = READ_WRITE)`와
   `@Execution(SAME_THREAD)`를 적용하고 `TransactionManager.defaultDatabase`,
   `TransactionManager.primaryDatabase`를 보존한다. context를 만드는 동안
   default를 `null`로 두고, refresh 실패를 포함해 registration diff를
   수집한다. 성공한 context의 첫 `TransactionTemplate` 실행에서 capture한
   `TransactionManager.current().db`가 diff의 새 handle인지 확인한다.
   callback의 commit/rollback과 Spring resource unbind가 끝난 뒤에만
   context를 닫고, `TransactionSynchronizationManager` resource와
   `TransactionManager.currentOrNull()`이 비어 있는지 확인한 다음 새 handle을
   `closeAndUnregister`하고 기존 default를 복원한다. context startup·test
   assertion·context close 어느 단계에서 예외가 나도 `try/finally`가 이
   순서를 수행하며, cleanup 예외는 원래 예외에 suppressed exception으로
   붙인다. context 생성 직후 실패해 첫 transaction이 없더라도 diff로 찾은
   handle을 정리한다. fixture reset은 FK 순서에 따라 `Clinics` 먼저,
   `TenantGroups` 나중에 실행하고 seed는 역순으로 실행한다.

8. **narrow context 경계** — `PilotTestConfiguration`을 명시적인 test
   configuration으로 만들고 `@SpringBootTest`의 application scan이나
   `AppointmentApiApplication`을 사용하지 않는다. repository package는
   전용 `projection` package와
   `basePackageClasses = [ClinicProjectionRepository::class]`로 고정한다.
   context 시작 뒤 repository bean이 정확히 하나이고 그 bean의 interface가
   `ClinicProjectionRepository`인지, 다른 `CrudRepository` 후보가 scan되지
   않았는지 확인한다. H2 `EmbeddedDatabaseBuilder`와 `test-postgresql`의
   `Containers.Postgres` singleton JDBC URL/계정은 기존
   `AbstractApiIntegrationTest.configureTestContainers`와 같은 profile
   규칙으로만 주입한다. PostgreSQL readiness와 첫 connection에는 bounded
   timeout을 적용하고, launcher가 준비되지 않으면 H2 성공으로 대체하지
   않고 해당 dialect 증거를 `PENDING`으로 남긴다.

9. **SQL interceptor 수명** — 대표 SQL evidence transaction 안에서만
   interceptor를 등록하고 `finally`에서 `unregisterInterceptor`한다.
   schema/fixture setup SQL과 대상 조회 SQL을 다른 transaction으로 분리해
   statement count에 setup 작업을 포함하지 않는다.

### 채택/보류 판정

다음 다섯 항목이 모두 통과하면 `Clinics`와 같은 단순 read-only projection에
한정해 “조건부 채택 가능”으로 기록한다.

- 결과와 정렬 동일
- tenant 격리 통과
- Spring transaction/DataSource 경계 통과
- row당 추가 query가 없는 단일 조회
- SQL과 변환 비용이 기존 경로를 대체할 수 있는 수준으로 설명 가능

PostgreSQL singleton 실행을 하지 못했거나, DAO Entity의 full-row load가
column-level projection 요구를 충족하지 못하면 production adoption은
“보류”로 기록한다. H2 결과만으로 운영 dialect의 채택을 선언하지 않는다.

제한된 connection pool에서의 동시 조회·대기·정리와 production query-plan
검증을 이번 단일-thread pilot에서 수행하지 못하면 역시 “보류”로 기록한다.
PostgreSQL 실행 시에는 같은 fixture와 같은 tenant predicate/order로
`EXPLAIN` 결과를 수집해 tenant index 사용과 불필요한 sort/full scan 여부를
확인한다. 이 증거가 없으면 SQL 문자열만으로 채택을 판정하지 않는다.

하나라도 실패하면 이번 repository 재사용은 “보류”로 기록하고 기존 Table DSL
repository를 유지한다. 어느 경우에도 기존 `/clinics` API를 전환하지 않는다.

## DoD 매핑

| 이슈 완료 조건 | 산출물/증거 |
|---|---|
| read-only 결과·정렬·tenant 격리 동일 | `ClinicSpringDataProjectionPilotTest`의 adapter 동일성·격리·입력 계약·SQL 테스트 |
| transaction 경계 검증, 기존 Composite 보존 | 전용 Spring context 테스트, 물리 connection identity, 전역 Database cleanup, 변경하지 않은 `ClinicRepository` |
| 적용/비적용 대상 문서화 | 이 설계 문서, 구현 plan, 결과 lesson/Issue read-back |
| 성능·생성 SQL·ABI·가독성 평가 | captured SQL·반복 측정값·test-only ABI 범위와 결과 문서 |

## Writer gate 기록

- `SPW-01`: 이 문서의 독자, 결정 대상, source ledger, 미확정 사항을 고정했다.
- `SPW-02`: spec 계약(경계, 구성, 오류, 호환성, acceptance, DoD)을 채웠다.
- `SPW-03`: 한국어 기술 문체와 API/명령어/식별자 보존을 적용한다.
- `SPW-04`: 현재 `develop` 소스와 resolved artifact, Issue #315를 대조했다.
- `SPW-05`: 구현 전 최종 read-back에서 placeholder·범위 모순·미결정 문장을
  제거한 뒤 plan으로 넘긴다.
