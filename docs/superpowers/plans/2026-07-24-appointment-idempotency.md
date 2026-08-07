# 예약 생성 멱등성 구현 계획

> **에이전트 작업자 필수 사항:** 이 계획을 task 단위로 구현할 때 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans` 서브 스킬을 사용합니다. 단계 추적에는 checkbox(`- [ ]`) 문법을 사용합니다.

**목표:** 선택적인 `Idempotency-Key` header로 안전한 tenant 범위 재시도를 허용하면서 예약이 중복 생성되지 않도록 합니다.

**아키텍처:** request key를 예약과 분리해 저장합니다. service result가 새로 commit된 데이터와 replay를 구분하고, database unique constraint가 동시 호출을 직렬화합니다. controller는 tenant authorization을 유지하고 결과를 201/200으로 매핑합니다.

**기술 스택:** Kotlin 2.3, Spring Boot 4 MVC, Exposed JDBC, Flyway (H2/PostgreSQL/MySQL), JUnit 5, MockK.

---

## 파일 구조

| 경로 | 책임 |
|---|---|
| `appointment-core/.../AppointmentIdempotencies.kt` | tenant/clinic/key uniqueness와 expiry index를 가진 Exposed table |
| `appointment-core/.../AppointmentIdempotencyRecord.kt` | 영속화되는 불변 idempotency record |
| `appointment-core/.../AppointmentIdempotencyRepository.kt` | 범위가 지정된 active-key 조회, 저장, 만료 삭제 |
| `appointment-api/.../AppointmentIdempotencyProperties.kt` | 양수인 기본 24시간 TTL |
| `appointment-api/.../AppointmentService.kt` | transaction 기반 create-or-replay와 event-once 동작 |
| `appointment-api/.../AppointmentController.kt` | 선택 header와 HTTP status 계약 |
| `appointment-api/.../GlobalExceptionHandler.kt` | mismatch를 409로 매핑 |
| `appointment-api/src/main/resources/db/migration/*/V7__add_appointment_idempotency.sql` | dialect schema |

### Task 1: 범위가 지정된 key 영속화

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/AppointmentIdempotencies.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentIdempotencyRecord.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentIdempotencyRepository.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentIdempotencyRepositoryTest.kt`

- [ ] **Step 1: RED repository test 작성**

`transaction {}` 안에서 tenant, clinic, appointment fixture를 생성합니다. active 조회,
만료 삭제, tenant/clinic 간 key 격리, 중복 `(tenant, clinic, key)` 거부를 검증합니다.

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepositoryTest"`

예상 결과: type 누락으로 compile이 실패합니다.

- [ ] **Step 3: 최소 Exposed 경계 구현**

`LongIdTable("scheduling_appointment_idempotency")`, `TenantGroups`, `Clinics`,
`Appointments`용 FK column, 255자 key, 64자 SHA-256 fingerprint, timestamp,
`uniqueIndex(tenantGroupId, clinicId, idempotencyKey)`, `expiresAt` index를 사용합니다.
Repository 호출은 caller가 소유한 `transaction {}` 안에 두고 운영 코드에 `!!`를
추가하지 않습니다.

- [ ] **Step 4: GREEN 실행**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepositoryTest"`

예상 결과: PASS.

### Task 2: 모든 schema 소스 일치시키기

**Files:**
- Create: `appointment-api/src/main/resources/db/migration/h2/V7__add_appointment_idempotency.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V7__add_appointment_idempotency.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V7__add_appointment_idempotency.sql`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`

- [ ] **Step 1: RED migration assertion 작성**

모든 Flyway test에서 V7 table, unique scope constraint, expiry index를 요구합니다.

- [ ] **Step 2: H2 RED 실행**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"`

예상 결과: table이 없습니다.

- [ ] **Step 3: V7 SQL과 test-schema 등록 추가**

V6 dialect naming을 따르고 `SchemaInitConfig`에 `AppointmentIdempotencies`를 추가합니다.

- [ ] **Step 4: migration GREEN을 순차 실행**

H2, PostgreSQL, MySQL migration test를 한 번에 하나씩 실행합니다. 예상 결과: 세 dialect 모두 PASS.

### Task 3: 원자적 create-or-replay 구현

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentIdempotencyProperties.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentServiceIdempotencyTest.kt`

- [ ] **Step 1: RED service test 작성**

고정 UTC `Clock`과 기록하는 `ApplicationEventPublisher`를 사용합니다. 최초 생성,
동일 fingerprint replay, mismatch, 만료, 양수가 아닌 TTL 거부, 두 concurrent caller를
검증합니다. replay pair에 대해 appointment, idempotency row, created event가 각각
정확히 하나인지 검증합니다.

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.AppointmentServiceIdempotencyTest"`

예상 결과: result/configuration/protocol type 누락으로 실패합니다.

- [ ] **Step 3: 최소 protocol 구현**

불변 양수 24시간 `Duration` configuration을 추가합니다. typed request field를
고정 순서와 명시적 null marker로 hash하며 raw JSON은 hash하지 않습니다. 하나의
transaction에서 일치하는 만료 key를 삭제하고 scope를 조회한 뒤 appointment와 key를
함께 저장하고 `Created` 또는 `Replayed`를 반환합니다. 확인된 duplicate-key race만
catch한 뒤 다시 읽어 fingerprint를 비교합니다. transaction 성공 후 `Created`일
때만 `AppointmentDomainEvent.Created`를 발행합니다.

- [ ] **Step 4: GREEN 실행**

가능하면 `MultithreadingTester`를 사용하고, 그렇지 않으면 synchronized real-H2
caller를 사용하며 그 이유를 기록합니다. 위 service test를 실행하고 PASS를 확인합니다.

### Task 4: HTTP 계약 공개

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentControllerTest.kt`

- [ ] **Step 1: RED controller test 작성**

`Idempotency-Key: retry-key-1`을 담은 동일한 POST 두 개는 같은 `$.data.id`와
함께 201, 200을 차례로 반환해야 합니다. key와 body가 달라지면 409와
`success=false`를 반환해야 하며, 빈 key는 400이어야 합니다. 기존 no-header
test는 201을 유지합니다. 다른 tenant를 seed하고, 그 caller가 같은 key로 이
tenant의 appointment를 replay할 수 없음을 검증합니다.

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentControllerTest"`

예상 결과: 현재 replay 동작이 요구된 status를 충족하지 못합니다.

- [ ] **Step 3: header, OpenAPI, conflict 매핑 구현**

replay lookup 전에 tenant와 clinic 소유권을 확인하고 생성 시 scheduling-resource
검증을 유지합니다. `@RequestHeader(value = "Idempotency-Key", required = false)`,
OpenAPI 200/409 response, `Created`의 201, `Replayed`의 200, 전용 mismatch
exception handling을 추가합니다.

- [ ] **Step 4: GREEN 실행**

위 controller test를 실행합니다. 예상 결과: PASS.

### Task 5: 수렴 및 commit

**Files:**
- Modify: 현재 API 문서에 appointment creation example이 있을 때만 `docs/requirements/api.md` 수정
- Modify: 리뷰 정정에 한해서만 `docs/superpowers/specs/2026-07-24-appointment-idempotency-design.md` 수정

- [ ] **Step 1: 공개 문서 영향 확인**

Run: `rg -n -i 'POST /api/.*/appointments|Create a new appointment|appointment creation' README* docs appointment-api/src/main/kotlin`

예상 결과: 관련 example을 갱신하거나 OpenAPI-only 범위 증거를 기록합니다.

- [ ] **Step 2: 최종 검증을 순차 실행**

Run these commands in order:

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepositoryTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.AppointmentServiceIdempotencyTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentControllerTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest"
./gradlew :appointment-api:test
./gradlew :appointment-api:build
git diff --check
```

- [ ] **Step 3: 최종 리뷰와 Lore commit**

모든 design acceptance criterion을 code/test에 매핑합니다. production `!!`, 광범위한
exception swallowing, Exposed transaction boundary, secret/PII/key logging, OpenAPI drift를
확인합니다. P0/P1을 해결하고 #174 변경만 stage한 뒤 Lore 형식 commit을 만듭니다.
최신 authority 없이 push, PR 생성, merge를 하지 않습니다.
