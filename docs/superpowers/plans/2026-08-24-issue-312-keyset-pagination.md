# Issue #312 keyset cursor pagination 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 page/size API를 보존하면서 의사·장비·진료 유형 목록에 tenant·clinic 범위가 고정된 keyset cursor API를 추가하고, H2·PostgreSQL·API 계약 증거로 Issue #312를 닫는다.

**Architecture:** `appointment-core`가 양수 식별자와 `(clinic_id, id)` exclusive 경계를 가진 `ClinicKeysetCursor`와 bounded page를 소유한다. 세 repository는 기존 `findPage`를 변경하지 않고 `limit + 1` 조회를 수행하며, `appointment-api`는 URL-safe Base64 opaque cursor, 새 응답 DTO, 세 개의 `/cursor` GET 경로를 제공한다. PostgreSQL 실행계획과 offset/cursor 비교는 구현 코드와 분리된 integration test 및 Korean lesson에 기록한다.

**Tech Stack:** Kotlin 2.3, Exposed v1 JDBC, Spring Boot 4 MVC, JUnit 5, bluetape4k singleton PostgreSQL launcher, H2/PostgreSQL dialect parameterized tests.

---

## 범위 고정

- 기존 `findPage`, `page`, `size`, `ExposedPage`, 기존 세 endpoint의 JSON은 변경하지 않는다.
- 새 경로는 다음 세 개뿐이다.
  - `GET /api/{tenantCode}/clinics/{clinicId}/doctors/cursor`
  - `GET /api/{tenantCode}/clinics/{clinicId}/equipments/cursor`
  - `GET /api/{tenantCode}/clinics/{clinicId}/treatment-types/cursor`
- API `limit`은 기존 목록 API와 같은 `1..100` clamp를 적용한다. 숫자가 아닌 query binding과 malformed cursor는 기존 전역 400 경계를 사용한다. repository 직접 호출은 `1..100` 밖의 limit을 거부한다.
- `(clinic_id ASC, id ASC)` 정렬, cursor의 exclusive `id > anchor` 조건, tenant membership predicate, `limit + 1`, `countBy`와 `OFFSET` 부재를 모든 repository에 동일하게 적용한다.
- 새 migration, 새 dependency, Redis/cache, frontend, 기존 offset 호출자 제거는 하지 않는다. PostgreSQL 계획이 composite index를 요구하면 lesson과 별도 후속 Issue로 남기고 이번 구현에는 index migration을 넣지 않는다.

## 파일 책임 지도

| 경로 | 책임 | 변경 |
|---|---|---|
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPagination.kt` | 공통 cursor/page 값 객체와 양수 불변식 | 생성 |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepository.kt` | 의사 keyset SQL 및 record mapping | 수정 |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepository.kt` | 장비 keyset SQL 및 record mapping | 수정 |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepository.kt` | 진료 유형 keyset SQL 및 record mapping | 수정 |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPaginationTest.kt` | 값 객체 검증 | 생성 |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ClinicKeysetPaginationRepositoryTest.kt` | 세 repository의 H2/PostgreSQL 행·scope·SQL 회귀 | 생성 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/KeysetPaginationResponse.kt` | API `items`/`nextCursor` 응답 DTO | 생성 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/ClinicKeysetCursorCodec.kt` | `v1:<clinicId>:<id>` Base64 URL-safe codec | 생성 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/ClinicKeysetCursorCodecTest.kt` | codec round trip 및 malformed 입력 회귀 | 생성 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/DoctorController.kt` | 의사 cursor route와 scope/limit 변환 | 수정 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentController.kt` | 장비 cursor route와 scope/limit 변환 | 수정 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/TreatmentTypeController.kt` | 진료 유형 cursor route와 scope/limit 변환 | 수정 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/DoctorControllerTest.kt` | 의사 cursor HTTP 계약 | 수정 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentControllerTest.kt` | 장비 cursor HTTP 계약 | 수정 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/TreatmentTypeControllerTest.kt` | 진료 유형 cursor HTTP 계약 | 수정 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ClinicKeysetPaginationQueryPlanTest.kt` | 실제 PostgreSQL EXPLAIN 및 비교 증거 | 생성 |
| `docs/lessons/2026-08-24-issue-312-keyset-pagination.md` | 실제 계획·비교 결과와 index 채택/보류 결정 | 생성 |

## 구현 순서와 체크포인트

### Task 1: 공통 core cursor/page 계약을 먼저 고정한다

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPagination.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPaginationTest.kt`

- [ ] **Step 1: 실패하는 값 객체 테스트를 작성한다.**

```kotlin
class KeysetPaginationTest {
    @Test
    fun `cursor는 clinic과 row id가 모두 양수여야 한다`() {
        ClinicKeysetCursor(clinicId = 10L, id = 101L)

        assertFailsWith<IllegalArgumentException> { ClinicKeysetCursor(clinicId = 0L, id = 101L) }
        assertFailsWith<IllegalArgumentException> { ClinicKeysetCursor(clinicId = 10L, id = 0L) }
    }

    @Test
    fun `bounded page는 content와 nullable next cursor를 보존한다`() {
        val cursor = ClinicKeysetCursor(clinicId = 10L, id = 101L)
        val page = ClinicKeysetPage(content = listOf("row-1"), nextCursor = cursor)

        page.content shouldBeEqualTo listOf("row-1")
        page.nextCursor shouldBeEqualTo cursor
        ClinicKeysetPage<String>(content = emptyList(), nextCursor = null).nextCursor.shouldBeNull()
    }
}
```

- [ ] **Step 2: core 단위 테스트가 새 타입 부재로 실패하는지 확인한다.**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.dto.KeysetPaginationTest" --no-daemon`

Expected: `Unresolved reference: ClinicKeysetCursor` 또는 같은 새 계약 부재 컴파일 오류.

- [ ] **Step 3: 최소 공통 모델을 구현한다.**

```kotlin
package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable

/** clinic 목록 정렬의 exclusive 경계를 나타내는 값 객체입니다. */
data class ClinicKeysetCursor(
    val clinicId: Long,
    val id: Long,
) : Serializable {
    init {
        require(clinicId > 0L) { "clinicId must be positive" }
        require(id > 0L) { "id must be positive" }
    }
}

/** 전체 count 없이 다음 경계만 제공하는 bounded page입니다. */
data class ClinicKeysetPage<T>(
    val content: List<T>,
    val nextCursor: ClinicKeysetCursor?,
)
```

- [ ] **Step 4: 단위 테스트가 통과하는지 확인한다.**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.dto.KeysetPaginationTest" --no-daemon`

Expected: `KeysetPaginationTest` 모든 테스트 `PASSED`.

- [ ] **Step 5: 공통 계약만 커밋한다.**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPagination.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPaginationTest.kt
git commit -m $'Issue #312의 core cursor 값 객체 불변식을 고정한다\n\nConstraint: API 호환성을 위해 cursor 계약을 offset 응답과 분리한다.\nRejected: 범용 page 타입을 재사용한다 | total count 의미가 cursor 경계와 맞지 않는다.\nConfidence: high\nScope-risk: narrow\nDirective: cursor 식별자는 양수 clinicId와 row id를 모두 요구한다.\nTested: ./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.dto.KeysetPaginationTest" --no-daemon\nNot-tested: repository SQL과 HTTP 경계는 다음 작업에서 검증한다.'
```

### Task 2: 세 repository의 keyset SQL을 TDD로 추가한다

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepository.kt:97-102`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepository.kt:66-71`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepository.kt:119-124`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ClinicKeysetPaginationRepositoryTest.kt`

- [ ] **Step 1: H2/PostgreSQL 공통 fixture와 실패 테스트를 작성한다.**

테스트 클래스는 `AbstractExposedTest`를 상속하고 `@ParameterizedTest`와 `@MethodSource(ENABLE_DIALECTS_METHOD)`로 `TestDB.enabledDialects()`를 사용한다. 각 테스트는 다음 fixture를 `withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes)` 안에서 만들고 종료 시 helper가 행과 테이블을 정리하게 한다.

```kotlin
private const val TENANT_A = TenantGroups.DEFAULT_TENANT_GROUP_ID
private const val TENANT_B = 2L
private const val CLINIC_A = 10L
private const val CLINIC_B = 20L

private val doctorRepository = DoctorRepository()
private val equipmentRepository = EquipmentRepository()
private val treatmentTypeRepository = TreatmentTypeRepository()

private fun JdbcTransaction.seedFixture() {
    TenantGroups.insert {
        it[id] = EntityID(TENANT_B, TenantGroups)
        it[tenantCode] = "tenant-b"
        it[displayName] = "Tenant B"
        it[active] = true
    }
    insertClinic(CLINIC_A, TENANT_A)
    insertClinic(CLINIC_B, TENANT_B)
    listOf(101L, 105L, 120L).forEach { insertDoctor(it, CLINIC_A) }
    insertDoctor(201L, CLINIC_B)
    listOf(301L, 305L, 320L).forEach { insertEquipment(it, CLINIC_A) }
    insertEquipment(401L, CLINIC_B)
    listOf(501L, 505L, 520L).forEach { insertTreatmentType(it, CLINIC_A) }
    insertTreatmentType(601L, CLINIC_B)
}

private fun JdbcTransaction.insertClinic(clinicId: Long, tenantGroupId: Long) {
    Clinics.insert {
        it[id] = EntityID(clinicId, Clinics)
        it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
        it[name] = "Clinic $clinicId"
        it[slotDurationMinutes] = 30
        it[timezone] = "UTC"
        it[locale] = "ko-KR"
        it[maxConcurrentPatients] = 2
        it[openOnHolidays] = false
    }
}

private fun JdbcTransaction.insertDoctor(doctorId: Long, clinicId: Long) {
    Doctors.insert {
        it[id] = EntityID(doctorId, Doctors)
        it[Doctors.clinicId] = EntityID(clinicId, Clinics)
        it[name] = "Doctor $doctorId"
        it[specialty] = "General"
        it[providerType] = ProviderType.DOCTOR
        it[maxConcurrentPatients] = 1
    }
}

private fun JdbcTransaction.insertEquipment(equipmentId: Long, clinicId: Long) {
    Equipments.insert {
        it[id] = EntityID(equipmentId, Equipments)
        it[Equipments.clinicId] = EntityID(clinicId, Clinics)
        it[name] = "Equipment $equipmentId"
        it[usageDurationMinutes] = 30
        it[quantity] = 1
    }
}

private fun JdbcTransaction.insertTreatmentType(treatmentTypeId: Long, clinicId: Long) {
    TreatmentTypes.insert {
        it[id] = EntityID(treatmentTypeId, TreatmentTypes)
        it[TreatmentTypes.clinicId] = EntityID(clinicId, Clinics)
        it[name] = "Treatment $treatmentTypeId"
        it[category] = TreatmentCategory.TREATMENT
        it[defaultDurationMinutes] = 30
        it[requiredProviderType] = ProviderType.DOCTOR
        it[requiresEquipment] = false
        it[maxConcurrentPatients] = 1
    }
}
```

실패 테스트 이름과 assertion은 다음 계약을 모두 고정한다.

```kotlin
@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `세 resource의 첫 페이지와 다음 페이지는 exclusive cursor로 전진한다`(testDB: TestDB) {
    withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes) {
        seedFixture()
        val scope = TenantClinicScope(TENANT_A, CLINIC_A)

        val doctorFirst = doctorRepository.findKeysetPage(scope, cursor = null, limit = 2)
        doctorFirst.content.map { it.id } shouldBeEqualTo listOf(101L, 105L)
        doctorFirst.nextCursor shouldBeEqualTo ClinicKeysetCursor(CLINIC_A, 105L)
        doctorRepository.findKeysetPage(scope, doctorFirst.nextCursor, 2).content.map { it.id } shouldBeEqualTo listOf(120L)

        val equipmentFirst = equipmentRepository.findKeysetPage(scope, cursor = null, limit = 2)
        equipmentFirst.content.map { it.id } shouldBeEqualTo listOf(301L, 305L)
        equipmentFirst.nextCursor shouldBeEqualTo ClinicKeysetCursor(CLINIC_A, 305L)
        equipmentRepository.findKeysetPage(scope, equipmentFirst.nextCursor, 2).content.map { it.id } shouldBeEqualTo listOf(320L)

        val treatmentFirst = treatmentTypeRepository.findKeysetPage(scope, cursor = null, limit = 2)
        treatmentFirst.content.map { it.id } shouldBeEqualTo listOf(501L, 505L)
        treatmentFirst.nextCursor shouldBeEqualTo ClinicKeysetCursor(CLINIC_A, 505L)
        treatmentTypeRepository.findKeysetPage(scope, treatmentFirst.nextCursor, 2).content.map { it.id } shouldBeEqualTo listOf(520L)
    }
}

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `마지막 페이지와 빈 clinic은 next cursor가 없다`(testDB: TestDB) {
    withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes) {
        seedFixture()
        insertClinic(11L, TENANT_A)
        val scope = TenantClinicScope(TENANT_A, CLINIC_A)

        doctorRepository.findKeysetPage(scope, cursor = null, limit = 3).nextCursor.shouldBeNull()
        equipmentRepository.findKeysetPage(scope, cursor = null, limit = 3).nextCursor.shouldBeNull()
        treatmentTypeRepository.findKeysetPage(scope, cursor = null, limit = 3).nextCursor.shouldBeNull()
        doctorRepository.findKeysetPage(TenantClinicScope(TENANT_A, 11L), cursor = null, limit = 3).content shouldBeEqualTo emptyList()
    }
}

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `anchor 삭제와 이후 insert는 sparse id를 중복 없이 읽는다`(testDB: TestDB) {
    withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes) {
        seedFixture()
        Doctors.deleteWhere { Doctors.id eq 105L }
        insertDoctor(130L, CLINIC_A)

        val result = doctorRepository.findKeysetPage(
            TenantClinicScope(TENANT_A, CLINIC_A),
            ClinicKeysetCursor(CLINIC_A, 101L),
            limit = 2,
        )
        result.content.map { it.id } shouldBeEqualTo listOf(120L, 130L)
        result.nextCursor.shouldBeNull()
    }
}

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `다른 clinic cursor와 tenant row는 scope 밖으로 나오지 않는다`(testDB: TestDB) {
    withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes) {
        seedFixture()
        val scope = TenantClinicScope(TENANT_A, CLINIC_A)

        assertFailsWith<IllegalArgumentException> {
            doctorRepository.findKeysetPage(scope, ClinicKeysetCursor(CLINIC_B, 201L), 2)
        }
        doctorRepository.findKeysetPage(scope, cursor = null, limit = 10).content.map { it.id }
            .none { it == 201L } shouldBeTrue()
        equipmentRepository.findKeysetPage(scope, cursor = null, limit = 10).content.map { it.id }
            .none { it == 401L } shouldBeTrue()
        treatmentTypeRepository.findKeysetPage(scope, cursor = null, limit = 10).content.map { it.id }
            .none { it == 601L } shouldBeTrue()
    }
}

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `cursor SQL은 count와 offset 없이 limit plus one을 사용한다`(testDB: TestDB) {
    withTables(testDB, TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes) {
        seedFixture()
        val statements = mutableListOf<String>()
        val interceptor = SqlStatementCapture(statements)
        registerInterceptor(interceptor)
        try {
            doctorRepository.findKeysetPage(TenantClinicScope(TENANT_A, CLINIC_A), cursor = null, limit = 2)
        } finally {
            unregisterInterceptor(interceptor)
        }
        val query = statements.single { it.contains("scheduling_doctors") && it.contains("select") }
        query.contains("offset") shouldBeFalse()
        query.contains("count(") shouldBeFalse()
        query.contains("limit") shouldBeTrue()
        query.contains("clinic_id") shouldBeTrue()
        query.contains("id") shouldBeTrue()
    }
}
```

위 테스트는 첫 페이지 `101, 105`/`301, 305`/`501, 505`, 다음 페이지 `120`/`320`/`520`, 마지막 cursor `null`, anchor 삭제 후 `120, 130`, wrong clinic 거부, tenant B 행 차단을 직접 검증한다.

SQL capture는 기존 Exposed `StatementInterceptor.afterExecution` 패턴을 그대로 사용한다.

```kotlin
private class SqlStatementCapture(private val statements: MutableList<String>) : StatementInterceptor {
    override fun afterExecution(
        transaction: Transaction,
        contexts: List<StatementContext>,
        executedStatement: PreparedStatementApi,
    ) {
        contexts.firstOrNull()?.let { statements += it.sql(transaction).lowercase() }
    }
}
```

- [ ] **Step 2: 새 repository 메서드 부재로 테스트가 실패하는지 확인한다.**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ClinicKeysetPaginationRepositoryTest" --no-daemon -PuseFastDB=true`

Expected: 세 repository의 `findKeysetPage` 미정의 컴파일 오류 또는 첫 테스트의 의도된 실패.

- [ ] **Step 3: 의사 repository에 범위 predicate·exclusive boundary·limit+1을 구현한다.**

```kotlin
fun findKeysetPage(
    scope: TenantClinicScope,
    cursor: ClinicKeysetCursor?,
    limit: Int,
): ClinicKeysetPage<DoctorRecord> {
    require(limit in 1..100) { "limit must be between 1 and 100" }
    require(cursor == null || cursor.clinicId == scope.clinicId) {
        "cursor clinicId must match scope clinicId"
    }
    val predicate = (Doctors.clinicId eq scope.clinicId) and
        (Doctors.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
    val after = cursor?.let {
        (Doctors.clinicId greater it.clinicId) or
            ((Doctors.clinicId eq it.clinicId) and (Doctors.id greater it.id))
    }
    val rows = Doctors
        .selectAll()
        .where { if (after == null) predicate else predicate and after }
        .orderBy(Doctors.clinicId to SortOrder.ASC, Doctors.id to SortOrder.ASC)
        .limit(limit + 1)
        .toList()
    val hasNext = rows.size > limit
    val content = rows.take(limit).map { it.toDoctorRecord() }
    return ClinicKeysetPage(
        content = content,
        nextCursor = if (hasNext) {
            content.last().let { record -> ClinicKeysetCursor(record.clinicId, record.id.requireNotNull("id")) }
        } else {
            null
        },
    )
}
```

- [ ] **Step 4: 장비와 진료 유형 repository에 같은 계약을 각각 적용한다.**

`EquipmentRepository`는 `Equipments.clinicId`, `Equipments.id`, `toEquipmentRecord()`를 사용하고, `TreatmentTypeRepository`는 `TreatmentTypes.clinicId`, `TreatmentTypes.id`, `toTreatmentTypeRecord()`를 사용한다. 두 메서드의 검증과 조회 순서는 다음과 같이 동일해야 한다.

장비 method는 `Equipments`와 `toEquipmentRecord()`를 사용한다.

```kotlin
fun findKeysetPage(scope: TenantClinicScope, cursor: ClinicKeysetCursor?, limit: Int): ClinicKeysetPage<EquipmentRecord> {
    require(limit in 1..100) { "limit must be between 1 and 100" }
    require(cursor == null || cursor.clinicId == scope.clinicId) { "cursor clinicId must match scope clinicId" }
    val predicate = (Equipments.clinicId eq scope.clinicId) and
        (Equipments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
    val after = cursor?.let {
        (Equipments.clinicId greater it.clinicId) or
            ((Equipments.clinicId eq it.clinicId) and (Equipments.id greater it.id))
    }
    val rows = Equipments.selectAll()
        .where { if (after == null) predicate else predicate and after }
        .orderBy(Equipments.clinicId to SortOrder.ASC, Equipments.id to SortOrder.ASC)
        .limit(limit + 1)
        .toList()
    val content = rows.take(limit).map { it.toEquipmentRecord() }
    return ClinicKeysetPage(content, if (rows.size > limit) {
        val last = content.last()
        ClinicKeysetCursor(last.clinicId, last.id.requireNotNull("id"))
    } else null)
}
```

진료 유형 method는 `TreatmentTypes`와 `toTreatmentTypeRecord()`를 사용하며, 같은 구현에서 table 이름만 실제 `TreatmentTypes`로 고정한다.

```kotlin
fun findKeysetPage(scope: TenantClinicScope, cursor: ClinicKeysetCursor?, limit: Int): ClinicKeysetPage<TreatmentTypeRecord> {
    require(limit in 1..100) { "limit must be between 1 and 100" }
    require(cursor == null || cursor.clinicId == scope.clinicId) { "cursor clinicId must match scope clinicId" }
    val predicate = (TreatmentTypes.clinicId eq scope.clinicId) and
        (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
    val after = cursor?.let {
        (TreatmentTypes.clinicId greater it.clinicId) or
            ((TreatmentTypes.clinicId eq it.clinicId) and (TreatmentTypes.id greater it.id))
    }
    val rows = TreatmentTypes.selectAll()
        .where { if (after == null) predicate else predicate and after }
        .orderBy(TreatmentTypes.clinicId to SortOrder.ASC, TreatmentTypes.id to SortOrder.ASC)
        .limit(limit + 1)
        .toList()
    val content = rows.take(limit).map { it.toTreatmentTypeRecord() }
    return ClinicKeysetPage(content, if (rows.size > limit) {
        val last = content.last()
        ClinicKeysetCursor(last.clinicId, last.id.requireNotNull("id"))
    } else null)
}
```

세 구현 모두 결과를 `take(limit)`한 뒤 초과 행이 있을 때만 마지막 record의 `clinicId`와 non-null `id`를 `ClinicKeysetCursor`로 만든다. `findPage`와 기존 import/메서드에는 손대지 않는다.

- [ ] **Step 5: repository 회귀 테스트를 H2에서 통과시킨다.**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ClinicKeysetPaginationRepositoryTest" --no-daemon -PuseFastDB=true`

Expected: 첫/다음/마지막/빈 결과, sparse anchor, wrong-clinic, tenant isolation, no-offset SQL 테스트가 모두 `PASSED`.

- [ ] **Step 6: repository 변경을 독립 커밋한다.**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPagination.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepository.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepository.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepository.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ClinicKeysetPaginationRepositoryTest.kt
git commit -m $'Issue #312의 세 목록 repository에 offset 없는 경계를 추가한다\n\nConstraint: 기존 findPage 호출자와 schema migration을 보존한다.\nRejected: LongJdbcRepository.findPage를 수정한다 | 전체 offset consumer와 다른 모듈에 영향이 퍼진다.\nConfidence: high\nScope-risk: moderate\nDirective: 모든 새 조회는 tenant predicate, clinic 경계, limit plus one을 함께 유지한다.\nTested: ./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ClinicKeysetPaginationRepositoryTest" --no-daemon -PuseFastDB=true\nNot-tested: API codec과 PostgreSQL EXPLAIN은 다음 작업에서 검증한다.'
```

### Task 3: API cursor codec과 response DTO를 TDD로 추가한다

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/KeysetPaginationResponse.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/ClinicKeysetCursorCodec.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/ClinicKeysetCursorCodecTest.kt`

- [ ] **Step 1: codec/response 실패 테스트를 작성한다.**

```kotlin
class ClinicKeysetCursorCodecTest {
    private val cursor = ClinicKeysetCursor(clinicId = 10L, id = 101L)

    @Test
    fun `v1 cursor는 URL-safe 무패딩 Base64로 round trip한다`() {
        val encoded = ClinicKeysetCursorCodec.encode(cursor)

        encoded shouldBeEqualTo "djE6MTA6MTAx"
        encoded.contains("=") shouldBeFalse()
        ClinicKeysetCursorCodec.decode(encoded) shouldBeEqualTo cursor
    }

    @Test
    fun `버전 문법 숫자 길이와 패딩이 틀리면 거부한다`() {
        listOf("", "bad", "djI6MTA6MTAx", "djE6MTA6MA", "====", "a".repeat(129))
            .forEach { token -> assertFailsWith<IllegalArgumentException> { ClinicKeysetCursorCodec.decode(token) } }
    }
}
```

- [ ] **Step 2: codec 타입 부재로 API 단위 테스트가 실패하는지 확인한다.**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.ClinicKeysetCursorCodecTest" --no-daemon -PuseFastDB=true`

Expected: `ClinicKeysetCursorCodec`와 `KeysetPageResponse` 미정의 컴파일 오류.

- [ ] **Step 3: 응답 DTO와 codec을 구현한다.**

```kotlin
package io.bluetape4k.clinic.appointment.api.dto

import java.io.Serializable

/** cursor 목록은 total count 대신 다음 opaque 경계만 반환합니다. */
data class KeysetPageResponse<T : Serializable>(
    val items: List<T>,
    val nextCursor: String?,
) : Serializable
```

```kotlin
package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.model.dto.ClinicKeysetCursor
import java.nio.charset.StandardCharsets
import java.util.Base64

/** clinic scope와 row id를 opaque URL-safe v1 cursor로 변환합니다. */
object ClinicKeysetCursorCodec {
    private const val VERSION = "v1"
    private const val MAX_TOKEN_LENGTH = 128
    private val TOKEN = Regex("[A-Za-z0-9_-]+")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(cursor: ClinicKeysetCursor): String =
        encoder.encodeToString("$VERSION:${cursor.clinicId}:${cursor.id}".toByteArray(StandardCharsets.UTF_8))

    fun decode(token: String): ClinicKeysetCursor {
        require(token.length in 1..MAX_TOKEN_LENGTH && TOKEN.matches(token)) { "cursor is malformed" }
        val payload = runCatching { decoder.decode(token).toString(StandardCharsets.UTF_8) }
            .getOrElse { throw IllegalArgumentException("cursor is malformed", it) }
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == VERSION) { "cursor is malformed" }
        val clinicId = parts[1].toLongOrNull()
        val id = parts[2].toLongOrNull()
        require(clinicId != null && id != null && clinicId > 0L && id > 0L) { "cursor is malformed" }
        return ClinicKeysetCursor(clinicId, id)
    }
}
```

- [ ] **Step 4: codec 단위 테스트를 통과시킨다.**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.ClinicKeysetCursorCodecTest" --no-daemon -PuseFastDB=true`

Expected: canonical token, round trip, malformed version/segment/ID/length/padding cases 모두 `PASSED`.

- [ ] **Step 5: codec 변경을 독립 커밋한다.**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/KeysetPaginationResponse.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/ClinicKeysetCursorCodec.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/ClinicKeysetCursorCodecTest.kt
git commit -m $'Issue #312의 opaque cursor와 API 응답 계약을 추가한다\n\nConstraint: cursor 값은 URL-safe 무패딩 Base64 v1 형식을 유지한다.\nRejected: raw clinicId와 row id를 query에 노출한다 | scope 경계와 consumer 계약이 흐려진다.\nConfidence: high\nScope-risk: narrow\nDirective: decode는 버전, 세그먼트, 양수 ID, 입력 길이를 모두 검증한다.\nTested: ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.ClinicKeysetCursorCodecTest" --no-daemon -PuseFastDB=true\nNot-tested: 세 controller 경로는 다음 작업에서 검증한다.'
```

### Task 4: 세 controller에 additive cursor 경로를 연결한다

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/DoctorController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/TreatmentTypeController.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/DoctorControllerTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentControllerTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/TreatmentTypeControllerTest.kt`

- [ ] **Step 1: 세 통합 테스트에 first/next/malformed/wrong-clinic HTTP 계약을 추가한다.**

각 기존 `@BeforeEach` fixture에 같은 clinic의 record를 두 개 이상 추가하고, 다음 테스트 흐름을 resource별로 작성한다.

```kotlin
@Test
fun `GET - cursor path returns bounded items and next cursor`() {
    val first = client.get()
        .uri("$CLINICS_BASE_URL/{clinicId}/doctors/cursor?limit=1", clinicId)
        .execute()
    first.statusCode shouldBeEqualTo HttpStatus.OK
    first.jsonPath<List<*>>("$.data.items").shouldHaveSize(1)
    val nextCursor = first.jsonPath<String>("$.data.nextCursor").shouldNotBeNull()

    val second = client.get()
        .uri("$CLINICS_BASE_URL/{clinicId}/doctors/cursor?limit=1&cursor={cursor}", clinicId, nextCursor)
        .execute()
    second.statusCode shouldBeEqualTo HttpStatus.OK
    second.jsonPath<List<*>>("$.data.items").shouldHaveSize(1)
    second.jsonPath<String>("$.data.totalCount").shouldBeNull()
}

@Test
fun `GET - malformed and wrong clinic cursor return bad request`() {
    client.get().uri("$CLINICS_BASE_URL/{clinicId}/doctors/cursor?cursor=bad", clinicId)
        .exchange().statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    val wrongClinicCursor = ClinicKeysetCursorCodec.encode(ClinicKeysetCursor(clinicId = clinicId + 1L, id = 1L))
    client.get().uri("$CLINICS_BASE_URL/{clinicId}/doctors/cursor?cursor={cursor}", clinicId, wrongClinicCursor)
        .exchange().statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
}
```

장비와 진료 유형 테스트는 경로와 record 이름만 실제 controller에 맞추되 `$.data.items`, `$.data.nextCursor`, offset 필드 부재, 400 경계를 동일하게 확인한다. 기존 `$.data.content`, `totalCount`, `pageNumber` 테스트는 수정하지 않는다.

- [ ] **Step 2: 새 route 부재로 HTTP 테스트가 실패하는지 확인한다.**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.DoctorControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.EquipmentControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.TreatmentTypeControllerTest" --no-daemon -PuseFastDB=true`

Expected: 새 `/cursor` 요청이 404이거나 `items` JSON assertion이 실패한다.

- [ ] **Step 3: 의사 controller에 scope 검증 선행, decode, transaction, encode를 연결한다.**

```kotlin
@GetMapping("/clinics/{clinicId}/doctors/cursor")
fun getByClinicCursor(
    @PathVariable tenantCode: String,
    @PathVariable clinicId: Long,
    @RequestParam(required = false) cursor: String?,
    @RequestParam(defaultValue = "20") limit: Int,
): ResponseEntity<ApiResponse<KeysetPageResponse<DoctorRecord>>> {
    clinicId.requirePositiveNumber("clinicId")
    val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
    val keysetCursor = cursor?.let(ClinicKeysetCursorCodec::decode)?.also {
        require(it.clinicId == clinicId) { "cursor clinicId must match path clinicId" }
    }
    val pageLimit = limit.coerceIn(1, PaginationDefaults.MAX_PAGE_SIZE)
    val result = transaction {
        doctorRepository.findKeysetPage(TenantClinicScope(tenant.id, clinicId), keysetCursor, pageLimit)
    }
    return ResponseEntity.ok(
        ApiResponse.ok(
            KeysetPageResponse(
                items = result.content,
                nextCursor = result.nextCursor?.let(ClinicKeysetCursorCodec::encode),
            ),
        ),
    )
}
```

- [ ] **Step 4: 장비와 진료 유형 controller에 각각 같은 경계를 연결한다.**

장비 method는 `equipmentRepository.findKeysetPage`와 `KeysetPageResponse<EquipmentRecord>`, 진료 유형 method는 `treatmentTypeRepository.findKeysetPage`와 `KeysetPageResponse<TreatmentTypeRecord>`를 사용한다. 두 method 모두 `verifyClinic`을 `decode`보다 먼저 실행하고, repository 호출을 `transaction {}`으로 감싼다. 기존 offset method의 annotation/path/signature는 변경하지 않는다.

- [ ] **Step 5: API 통합 테스트와 기존 offset 회귀를 통과시킨다.**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.DoctorControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.EquipmentControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.TreatmentTypeControllerTest" --no-daemon -PuseFastDB=true`

Expected: 세 cursor contract와 기존 offset contract가 모두 `PASSED`.

- [ ] **Step 6: API 경로 변경을 독립 커밋한다.**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/DoctorController.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentController.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/TreatmentTypeController.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/DoctorControllerTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentControllerTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/TreatmentTypeControllerTest.kt
git commit -m $'Issue #312의 세 clinic 목록에 additive cursor 경로를 연결한다\n\nConstraint: tenant clinic 검증과 기존 offset JSON 호환성을 유지한다.\nRejected: 기존 endpoint를 cursor 응답으로 분기한다 | 기존 consumer의 totalCount 계약을 깨뜨린다.\nConfidence: high\nScope-risk: moderate\nDirective: path scope 검증은 cursor decode와 repository transaction보다 먼저 실행한다.\nTested: 세 controller targeted test 명령\nNot-tested: PostgreSQL 실행계획과 성능 비교는 다음 작업에서 검증한다.'
```

### Task 5: 실제 PostgreSQL 실행계획과 비교 증거를 남긴다

**Files:**
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ClinicKeysetPaginationQueryPlanTest.kt`
- Create: `docs/lessons/2026-08-24-issue-312-keyset-pagination.md`

- [ ] **Step 1: singleton launcher 기반 PostgreSQL 증거 테스트를 작성한다.**

테스트는 `ClinicSpringDataProjectionPilotTest`와 같은 `API_INTEGRATION_RESOURCE` read/write lock, `Containers.Postgres`, Flyway PostgreSQL migration을 사용한다. `@Testcontainers`와 raw container 생성을 사용하지 않는다. 세 table 각각에 대해 다음 조건의 cursor SQL과 offset SQL을 준비한다.

```sql
WHERE t.clinic_id = ?
  AND t.clinic_id IN (SELECT c.id FROM scheduling_clinics c WHERE c.tenant_group_id = ?)
  AND (t.clinic_id > ? OR (t.clinic_id = ? AND t.id > ?))
ORDER BY t.clinic_id ASC, t.id ASC
LIMIT 51
```

offset 비교 SQL은 같은 predicate와 order를 사용하되 `LIMIT 50 OFFSET 5000`을 사용한다. fixture는 각 resource에 최소 2000건을 넣고 `ANALYZE scheduling_doctors`, `scheduling_equipments`, `scheduling_treatment_types`를 실행한다. `EXPLAIN (FORMAT JSON)` 결과에 `Offset` 노드가 없고 cursor SQL text에 `offset`이 없음을 assertion으로 고정한다. timing은 warm-up 3회 후 각 경로 10회 `measureNanoTime`의 median을 report에 기록하되 작은 fixture의 결과를 성능 승리로 단정하지 않는다.

- [ ] **Step 2: Docker 상태를 확인하고 PostgreSQL 테스트를 실행한다.**

Run: `colima status`, `docker context show`, `docker info`

Then: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.ClinicKeysetPaginationQueryPlanTest" --no-daemon -Dspring.profiles.active=test,test-postgresql`

Expected: active Colima context와 Docker info가 확인되고, EXPLAIN assertion이 통과한다. Docker 초기화가 실패하면 해당 오류를 환경 gap으로 기록하고 성공으로 표시하지 않는다.

- [ ] **Step 3: report와 lesson에 실제 결과를 기록한다.**

테스트는 `appointment-api/build/reports/performance/issue-312-keyset-pagination-postgresql-explain.txt`에 table별 plan 요약, `offsetNode=false`, cursor/offset median, fixture cardinality를 쓴다. lesson에는 실제 실행 결과를 근거로 cursor SQL의 `OFFSET` 부재, 세 resource의 tenant·clinic predicate, PostgreSQL 핵심 plan node, 동일 fixture의 cursor/offset median, 현재 index 유지 또는 후속 composite-index Issue 결정, 작은 fixture의 한계를 기록한다. 재현 명령은 `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.ClinicKeysetPaginationQueryPlanTest" --no-daemon -Dspring.profiles.active=test,test-postgresql`로 고정한다.

- [ ] **Step 4: lesson의 한국어 문체와 diff를 검증한다.**

Run: `git diff --check` and `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --series clinic-appointment --json docs/lessons/2026-08-24-issue-312-keyset-pagination.md`

Expected: whitespace 오류가 없고 Korean audit `findings: []`.

### Task 6: 전체 검증, inline review, delivery evidence를 수집한다

**Files:**
- Review all changed Kotlin and test files from Tasks 1–5.
- Update only evidence-bearing docs under `docs/superpowers`, `docs/lessons`, and the Issue #312 PR body during delivery.

- [ ] **Step 1: 변경 파일과 기존 계약을 read-back한다.**

Run: `git diff origin/develop...HEAD --stat`, `git diff --check`, `rg -n "findPage|/cursor|nextCursor|OFFSET|offset|ClinicKeysetCursor" appointment-core appointment-api docs/lessons`

Expected: 기존 `findPage`/offset route 변경은 없고, 새 경로·cursor·SQL evidence가 지정된 파일에만 존재한다.

- [ ] **Step 2: Kotlin formatting, compile, targeted tests를 순서대로 실행한다.**

Run:

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.dto.KeysetPaginationTest" --tests "io.bluetape4k.clinic.appointment.repository.ClinicKeysetPaginationRepositoryTest" --no-daemon -PuseFastDB=true
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.ClinicKeysetCursorCodecTest" --tests "io.bluetape4k.clinic.appointment.api.controller.DoctorControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.EquipmentControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.TreatmentTypeControllerTest" --no-daemon -PuseFastDB=true
./gradlew :appointment-core:build :appointment-api:build --no-daemon -x test
```

Expected: targeted tests and both module builds `BUILD SUCCESSFUL`. Docker-backed PostgreSQL test는 Task 5의 별도 결과를 사용하며 skip/실패를 green으로 취급하지 않는다.

- [ ] **Step 3: inline review를 수행하고 결과를 기록한다.**

다음 관점으로 현재 diff를 직접 검토한다.

| 관점 | 확인할 불변식 |
|---|---|
| 보안 | `verifyClinic`이 decode보다 먼저 실행되고 repository tenant predicate가 유지된다. |
| 정확성 | exclusive boundary, sparse id, 삭제 anchor, last page, wrong clinic이 테스트된다. |
| 성능 | `countBy`, `OFFSET`, 전체 materialization이 없고 `limit + 1`만 읽는다. |
| 호환성 | 기존 offset route/response와 migration/dependency가 바뀌지 않는다. |
| Kotlin/Exposed | 모든 DB 호출이 caller transaction 안에 있고 `SortOrder.ASC`가 명시된다. |
| 운영성 | malformed input은 400, DB 오류는 기존 전역 경계, PostgreSQL evidence가 재현 가능하다. |

리뷰 결과는 P0/P1/P2 발견 수, 파일·line, 조치 또는 허용 근거로 남긴다. P0/P1은 0이어야 delivery로 이동한다. 독립 subagent review는 사용하지 않고 이 계획의 inline review로 대체한다.

- [ ] **Step 4: Lore commit와 PR DoD를 준비한다.**

각 commit은 Korean intent line과 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailer를 포함한다. PR 본문은 Korean으로 작성하고 Issue #312 link, 변경 파일, targeted/PG test 결과, inline review P0/P1=0, known Docker gap, `## DoD Status`를 포함한다. 이 단계에서는 PR을 만들기 전에 live `AGENTS.md` hierarchy와 Issue #312 metadata를 다시 읽는다.

- [ ] **Step 5: fresh exact-head merge approval 전에는 merge하지 않는다.**

PR 생성 후 head SHA, required CI, review/thread, mergeability, PR body의 `## DoD Status`를 live read-back한다. 사용자의 새 `승인`이 exact live head에 묶여 확인되기 전까지 merge, branch 삭제, worktree 삭제를 수행하지 않는다.

## 검증 명령 요약

| 단계 | 명령 | 성공 기준 |
|---|---|---|
| Core 값 객체 | `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.dto.KeysetPaginationTest" --no-daemon` | 계약 테스트 통과 |
| Core repository | `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ClinicKeysetPaginationRepositoryTest" --no-daemon` | H2 및 활성 PG dialect 통과 |
| API codec/controller | `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.ClinicKeysetCursorCodecTest" --tests "io.bluetape4k.clinic.appointment.api.controller.DoctorControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.EquipmentControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.TreatmentTypeControllerTest" --no-daemon` | 세 cursor와 기존 offset 통과 |
| PG plan | `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.ClinicKeysetPaginationQueryPlanTest" --no-daemon -Dspring.profiles.active=test,test-postgresql` | EXPLAIN/offset absence/report 생성 |
| Build | `./gradlew :appointment-core:build :appointment-api:build --no-daemon -x test` | 두 module build 성공 |

## 완료 판정

- [ ] 사양의 core/API/repository 계약이 모두 구현되었다.
- [ ] 기존 page/size API와 response가 변경되지 않았다.
- [ ] H2 및 활성 PostgreSQL 기능 테스트가 통과했다.
- [ ] PostgreSQL EXPLAIN·비교 report와 Korean lesson이 실제 결과를 담고 있다.
- [ ] inline review에서 P0/P1이 0이다.
- [ ] PR body에 Korean `## DoD Status`와 Issue #312 closure link가 live read-back되었다.
- [ ] fresh exact-head merge approval을 받은 뒤 merge/local sync/worktree 정리를 완료했다.
