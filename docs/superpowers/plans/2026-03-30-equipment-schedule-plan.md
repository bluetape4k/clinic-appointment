# 장비 스케줄 관리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **MANDATORY:** Apply `bluetape4k-patterns` skill on ALL Kotlin code. No exceptions.

**Goal:** 의료 장비 사용불가 기간(일회성+반복+예외)을 관리하고, 슬롯 계산과 Timefold Solver에 반영하여 장비 충돌 예약을 방지한다.

**Architecture:** `appointment-core`에 ORM 테이블·DTO·Repository·Service를 추가하고, `appointment-solver`의 ProblemFact·HardConstraint·SolutionConverter를 확장하며, `appointment-api`에 Flyway 마이그레이션과 REST Controller를 추가한다.

**Tech Stack:** Kotlin 2.3, Exposed ORM v1, Timefold Solver, Spring Boot 4, JUnit 5 + Kotest + MockK

---

## 파일 맵

### 신규 생성

| 파일 | 역할 |
|------|------|
| `appointment-api/src/main/resources/db/migration/V2__add_equipment_unavailabilities.sql` | 장비 사용불가 테이블 DDL |
| `appointment-core/.../model/tables/EquipmentUnavailabilities.kt` | Exposed ORM 테이블 정의 |
| `appointment-core/.../model/tables/EquipmentUnavailabilityExceptions.kt` | 반복 예외 테이블 정의 |
| `appointment-core/.../model/dto/EquipmentUnavailabilityRecord.kt` | 장비 사용불가 DTO |
| `appointment-core/.../model/dto/EquipmentUnavailabilityExceptionRecord.kt` | 예외 DTO |
| `appointment-core/.../model/dto/UnavailablePeriod.kt` | 전개된 사용불가 기간 DTO |
| `appointment-core/.../repository/EquipmentUnavailabilityRepository.kt` | DB CRUD |
| `appointment-core/.../service/UnavailabilityExpander.kt` | 반복 규칙 전개 로직 |
| `appointment-core/.../service/EquipmentUnavailabilityService.kt` | 비즈니스 로직 + 충돌 감지 |
| `appointment-solver/.../solver/domain/EquipmentUnavailabilityFact.kt` | Solver ProblemFact |
| `appointment-api/.../api/dto/request/CreateEquipmentUnavailabilityRequest.kt` | API 요청 DTO |
| `appointment-api/.../api/dto/request/UpdateEquipmentUnavailabilityRequest.kt` | API 수정 DTO |
| `appointment-api/.../api/dto/request/UnavailabilityExceptionRequest.kt` | 예외 등록 DTO |
| `appointment-api/.../api/dto/response/UnavailabilityConflictResponse.kt` | 충돌 응답 DTO |
| `appointment-api/.../api/controller/EquipmentUnavailabilityController.kt` | REST Controller |
| 각 Task별 테스트 파일 | JUnit 5 + Kotest |

### 수정

| 파일 | 변경 내용 |
|------|---------|
| `appointment-core/.../service/SlotCalculationService.kt` | 장비 사용불가 체크 단계 추가 (7번과 8번 사이) |
| `appointment-solver/.../solver/domain/ScheduleSolution.kt` | `equipmentUnavailabilities` ProblemFact 추가 |
| `appointment-solver/.../solver/constraint/HardConstraints.kt` | H11 `equipmentUnavailabilityConflict` 추가 |
| `appointment-solver/.../solver/constraint/AppointmentConstraintProvider.kt` | H11 등록 |
| `appointment-solver/.../solver/converter/SolutionConverter.kt` | `EquipmentUnavailabilityFact` 목록 populate |

---

## Task 1: Flyway 마이그레이션 SQL

**Files:**
- Create: `appointment-api/src/main/resources/db/migration/V2__add_equipment_unavailabilities.sql`

- [ ] **Step 1: SQL 파일 작성**

```sql
-- V2__add_equipment_unavailabilities.sql

CREATE TABLE scheduling_equipment_unavailabilities (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    equipment_id         BIGINT       NOT NULL,
    clinic_id            BIGINT       NOT NULL,
    unavailable_date     DATE,
    is_recurring         BOOLEAN      NOT NULL DEFAULT FALSE,
    recurring_day_of_week VARCHAR(10),
    effective_from       DATE         NOT NULL,
    effective_until      DATE,
    start_time           TIME         NOT NULL,
    end_time             TIME         NOT NULL,
    reason               VARCHAR(500),
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_equ_unavail_equipment FOREIGN KEY (equipment_id) REFERENCES scheduling_equipments(id) ON DELETE CASCADE,
    CONSTRAINT fk_equ_unavail_clinic    FOREIGN KEY (clinic_id)    REFERENCES scheduling_clinics(id)    ON DELETE CASCADE
);

CREATE INDEX idx_equ_unavail_equipment_from ON scheduling_equipment_unavailabilities (equipment_id, effective_from);
CREATE INDEX idx_equ_unavail_clinic_from    ON scheduling_equipment_unavailabilities (clinic_id, effective_from);
CREATE INDEX idx_equ_unavail_equipment_dow  ON scheduling_equipment_unavailabilities (equipment_id, recurring_day_of_week);

CREATE TABLE scheduling_equipment_unavailability_exceptions (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    unavailability_id       BIGINT       NOT NULL,
    original_date           DATE         NOT NULL,
    exception_type          VARCHAR(20)  NOT NULL,
    rescheduled_date        DATE,
    rescheduled_start_time  TIME,
    rescheduled_end_time    TIME,
    reason                  VARCHAR(500),
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_equ_unavail_exc_parent FOREIGN KEY (unavailability_id)
        REFERENCES scheduling_equipment_unavailabilities(id) ON DELETE CASCADE
);

CREATE INDEX idx_equ_unavail_exc_parent_date
    ON scheduling_equipment_unavailability_exceptions (unavailability_id, original_date);
```

- [ ] **Step 2: 빌드로 마이그레이션 검증**

```bash
./gradlew :appointment-api:test --tests "*.TableSchemaTest" -i
```
Expected: `PASS` (Flyway가 V2 적용)

- [ ] **Step 3: 커밋**

```bash
git add appointment-api/src/main/resources/db/migration/V2__add_equipment_unavailabilities.sql
git commit -m "chore: 장비 사용불가 스케줄 Flyway 마이그레이션 추가"
```

---

## Task 2: Exposed ORM 테이블 정의

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/EquipmentUnavailabilities.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/EquipmentUnavailabilityExceptions.kt`

- [ ] **Step 1: `EquipmentUnavailabilities.kt` 작성**

```kotlin
package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time

object EquipmentUnavailabilities : LongIdTable("scheduling_equipment_unavailabilities") {
    val equipmentId = reference("equipment_id", Equipments, onDelete = ReferenceOption.CASCADE)
    val clinicId    = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)

    val unavailableDate    = date("unavailable_date").nullable()
    val isRecurring        = bool("is_recurring").default(false)
    val recurringDayOfWeek = enumerationByName<java.time.DayOfWeek>("recurring_day_of_week", 10).nullable()
    val effectiveFrom      = date("effective_from")
    val effectiveUntil     = date("effective_until").nullable()
    val startTime          = time("start_time")
    val endTime            = time("end_time")
    val reason             = varchar("reason", 500).nullable()

    init {
        index("idx_equ_unavail_equipment_from", false, equipmentId, effectiveFrom)
        index("idx_equ_unavail_clinic_from", false, clinicId, effectiveFrom)
        index("idx_equ_unavail_equipment_dow", false, equipmentId, recurringDayOfWeek)
    }
}
```

- [ ] **Step 2: `EquipmentUnavailabilityExceptions.kt` 작성**

```kotlin
package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time

enum class ExceptionType { SKIP, RESCHEDULE }

object EquipmentUnavailabilityExceptions : LongIdTable("scheduling_equipment_unavailability_exceptions") {
    val unavailabilityId       = reference("unavailability_id", EquipmentUnavailabilities, onDelete = ReferenceOption.CASCADE)
    val originalDate           = date("original_date")
    val exceptionType          = enumerationByName<ExceptionType>("exception_type", 20)
    val rescheduledDate        = date("rescheduled_date").nullable()
    val rescheduledStartTime   = time("rescheduled_start_time").nullable()
    val rescheduledEndTime     = time("rescheduled_end_time").nullable()
    val reason                 = varchar("reason", 500).nullable()

    init {
        index("idx_equ_unavail_exc_parent_date", false, unavailabilityId, originalDate)
    }
}
```

- [ ] **Step 3: 스키마 테스트 추가**

파일: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
기존 테스트 파일에 두 테이블을 추가한다.

```kotlin
// TableSchemaTest.kt 내 기존 allTables 리스트에 추가
EquipmentUnavailabilities,
EquipmentUnavailabilityExceptions,
```

- [ ] **Step 4: 테스트 실행**

```bash
./gradlew :appointment-core:test --tests "*.TableSchemaTest" -i
```
Expected: `PASS`

- [ ] **Step 5: 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/EquipmentUnavailabilities.kt
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/EquipmentUnavailabilityExceptions.kt
git add appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt
git commit -m "feat: EquipmentUnavailabilities, EquipmentUnavailabilityExceptions Exposed 테이블 추가"
```

---

## Task 3: DTO 정의

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/EquipmentUnavailabilityRecord.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/EquipmentUnavailabilityExceptionRecord.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/UnavailablePeriod.kt`

- [ ] **Step 1: `EquipmentUnavailabilityRecord.kt` 작성**

```kotlin
package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class EquipmentUnavailabilityRecord(
    val id: Long,
    val equipmentId: Long,
    val clinicId: Long,
    val unavailableDate: LocalDate?,
    val isRecurring: Boolean,
    val recurringDayOfWeek: DayOfWeek?,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val reason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class EquipmentUnavailabilityExceptionRecord(
    val id: Long,
    val unavailabilityId: Long,
    val originalDate: LocalDate,
    val exceptionType: ExceptionType,
    val rescheduledDate: LocalDate?,
    val rescheduledStartTime: LocalTime?,
    val rescheduledEndTime: LocalTime?,
    val reason: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

- [ ] **Step 2: `UnavailablePeriod.kt` 작성**

```kotlin
package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/** 반복 규칙에서 전개된 실제 사용불가 기간 */
data class UnavailablePeriod(
    val equipmentId: Long,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

- [ ] **Step 3: 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/EquipmentUnavailabilityRecord.kt
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/UnavailablePeriod.kt
git commit -m "feat: EquipmentUnavailability DTO 추가 (Serializable)"
```

---

## Task 4: Repository

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentUnavailabilityRepository.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentUnavailabilityRepositoryTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.v1.core.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EquipmentUnavailabilityRepositoryTest {
    private val repo = EquipmentUnavailabilityRepository()

    @BeforeEach
    fun setup() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                EquipmentUnavailabilities,
                EquipmentUnavailabilityExceptions,
            )
            EquipmentUnavailabilityExceptions.deleteAll()
            EquipmentUnavailabilities.deleteAll()
        }
    }

    @Test
    fun `일회성 사용불가 저장 및 날짜 범위 조회`() {
        val record = transaction {
            repo.create(
                equipmentId = 1L,
                clinicId = 1L,
                unavailableDate = LocalDate.of(2026, 4, 1),
                isRecurring = false,
                recurringDayOfWeek = null,
                effectiveFrom = LocalDate.of(2026, 4, 1),
                effectiveUntil = LocalDate.of(2026, 4, 1),
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(12, 0),
                reason = "유지보수",
            )
        }

        val found = transaction {
            repo.findByEquipment(
                equipmentId = 1L,
                from = LocalDate.of(2026, 4, 1),
                to = LocalDate.of(2026, 4, 30),
            )
        }

        found shouldHaveSize 1
        found[0].reason shouldBeEqualTo "유지보수"
    }

    @Test
    fun `반복 스케줄 + 예외 조회`() {
        val ruleId = transaction {
            repo.create(
                equipmentId = 1L, clinicId = 1L,
                unavailableDate = null,
                isRecurring = true,
                recurringDayOfWeek = DayOfWeek.TUESDAY,
                effectiveFrom = LocalDate.of(2026, 4, 1),
                effectiveUntil = null,
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(10, 0),
                reason = "정기 점검",
            ).id
        }

        transaction {
            repo.addException(
                unavailabilityId = ruleId,
                originalDate = LocalDate.of(2026, 4, 7),
                exceptionType = ExceptionType.SKIP,
                rescheduledDate = null,
                rescheduledStartTime = null,
                rescheduledEndTime = null,
                reason = "이번 주 건너뜀",
            )
        }

        val exceptions = transaction { repo.findExceptions(ruleId) }
        exceptions shouldHaveSize 1
        exceptions[0].exceptionType shouldBeEqualTo ExceptionType.SKIP
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :appointment-core:test --tests "*.EquipmentUnavailabilityRepositoryTest" -i
```
Expected: `FAIL` — `EquipmentUnavailabilityRepository` 미정의

- [ ] **Step 3: Repository 구현**

```kotlin
package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EquipmentUnavailabilityRepository {

    companion object : KLogging()

    fun create(
        equipmentId: Long,
        clinicId: Long,
        unavailableDate: LocalDate?,
        isRecurring: Boolean,
        recurringDayOfWeek: DayOfWeek?,
        effectiveFrom: LocalDate,
        effectiveUntil: LocalDate?,
        startTime: LocalTime,
        endTime: LocalTime,
        reason: String?,
    ): EquipmentUnavailabilityRecord {
        equipmentId.requirePositiveNumber("equipmentId")
        clinicId.requirePositiveNumber("clinicId")

        val id = EquipmentUnavailabilities.insert {
            it[EquipmentUnavailabilities.equipmentId] = equipmentId
            it[EquipmentUnavailabilities.clinicId]    = clinicId
            it[EquipmentUnavailabilities.unavailableDate] = unavailableDate
            it[EquipmentUnavailabilities.isRecurring]     = isRecurring
            it[EquipmentUnavailabilities.recurringDayOfWeek] = recurringDayOfWeek
            it[EquipmentUnavailabilities.effectiveFrom]   = effectiveFrom
            it[EquipmentUnavailabilities.effectiveUntil]  = effectiveUntil
            it[EquipmentUnavailabilities.startTime]       = startTime
            it[EquipmentUnavailabilities.endTime]         = endTime
            it[EquipmentUnavailabilities.reason]          = reason
        }[EquipmentUnavailabilities.id].value

        return EquipmentUnavailabilityRecord(
            id = id, equipmentId = equipmentId, clinicId = clinicId,
            unavailableDate = unavailableDate, isRecurring = isRecurring,
            recurringDayOfWeek = recurringDayOfWeek, effectiveFrom = effectiveFrom,
            effectiveUntil = effectiveUntil, startTime = startTime,
            endTime = endTime, reason = reason,
        )
    }

    fun findByEquipment(equipmentId: Long, from: LocalDate, to: LocalDate): List<EquipmentUnavailabilityRecord> =
        EquipmentUnavailabilities.selectAll()
            .where {
                (EquipmentUnavailabilities.equipmentId eq equipmentId) and
                (EquipmentUnavailabilities.effectiveFrom lessEq to) and
                (
                    EquipmentUnavailabilities.effectiveUntil.isNull() or
                    (EquipmentUnavailabilities.effectiveUntil greaterEq from)
                )
            }
            .map { it.toEquipmentUnavailabilityRecord() }

    fun findByClinicOnDate(clinicId: Long, date: LocalDate): List<EquipmentUnavailabilityRecord> =
        EquipmentUnavailabilities.selectAll()
            .where {
                (EquipmentUnavailabilities.clinicId eq clinicId) and
                (EquipmentUnavailabilities.effectiveFrom lessEq date) and
                (
                    EquipmentUnavailabilities.effectiveUntil.isNull() or
                    (EquipmentUnavailabilities.effectiveUntil greaterEq date)
                )
            }
            .map { it.toEquipmentUnavailabilityRecord() }

    fun findById(id: Long): EquipmentUnavailabilityRecord? =
        EquipmentUnavailabilities.selectAll()
            .where { EquipmentUnavailabilities.id eq id }
            .singleOrNull()
            ?.toEquipmentUnavailabilityRecord()

    fun delete(id: Long) {
        EquipmentUnavailabilities.deleteWhere { EquipmentUnavailabilities.id eq id }
    }

    fun addException(
        unavailabilityId: Long,
        originalDate: LocalDate,
        exceptionType: ExceptionType,
        rescheduledDate: LocalDate?,
        rescheduledStartTime: LocalTime?,
        rescheduledEndTime: LocalTime?,
        reason: String?,
    ): EquipmentUnavailabilityExceptionRecord {
        unavailabilityId.requirePositiveNumber("unavailabilityId")

        val id = EquipmentUnavailabilityExceptions.insert {
            it[EquipmentUnavailabilityExceptions.unavailabilityId]    = unavailabilityId
            it[EquipmentUnavailabilityExceptions.originalDate]        = originalDate
            it[EquipmentUnavailabilityExceptions.exceptionType]       = exceptionType
            it[EquipmentUnavailabilityExceptions.rescheduledDate]     = rescheduledDate
            it[EquipmentUnavailabilityExceptions.rescheduledStartTime] = rescheduledStartTime
            it[EquipmentUnavailabilityExceptions.rescheduledEndTime]  = rescheduledEndTime
            it[EquipmentUnavailabilityExceptions.reason]              = reason
        }[EquipmentUnavailabilityExceptions.id].value

        return EquipmentUnavailabilityExceptionRecord(
            id = id, unavailabilityId = unavailabilityId, originalDate = originalDate,
            exceptionType = exceptionType, rescheduledDate = rescheduledDate,
            rescheduledStartTime = rescheduledStartTime, rescheduledEndTime = rescheduledEndTime,
            reason = reason,
        )
    }

    fun findExceptions(unavailabilityId: Long): List<EquipmentUnavailabilityExceptionRecord> =
        EquipmentUnavailabilityExceptions.selectAll()
            .where { EquipmentUnavailabilityExceptions.unavailabilityId eq unavailabilityId }
            .map { it.toEquipmentUnavailabilityExceptionRecord() }

    fun deleteException(exceptionId: Long) {
        EquipmentUnavailabilityExceptions.deleteWhere { EquipmentUnavailabilityExceptions.id eq exceptionId }
    }

    // --- Result Row 확장 함수 (private) ---

    private fun org.jetbrains.exposed.v1.core.ResultRow.toEquipmentUnavailabilityRecord() =
        EquipmentUnavailabilityRecord(
            id             = this[EquipmentUnavailabilities.id].value,
            equipmentId    = this[EquipmentUnavailabilities.equipmentId].value,
            clinicId       = this[EquipmentUnavailabilities.clinicId].value,
            unavailableDate = this[EquipmentUnavailabilities.unavailableDate],
            isRecurring    = this[EquipmentUnavailabilities.isRecurring],
            recurringDayOfWeek = this[EquipmentUnavailabilities.recurringDayOfWeek],
            effectiveFrom  = this[EquipmentUnavailabilities.effectiveFrom],
            effectiveUntil = this[EquipmentUnavailabilities.effectiveUntil],
            startTime      = this[EquipmentUnavailabilities.startTime],
            endTime        = this[EquipmentUnavailabilities.endTime],
            reason         = this[EquipmentUnavailabilities.reason],
        )

    private fun org.jetbrains.exposed.v1.core.ResultRow.toEquipmentUnavailabilityExceptionRecord() =
        EquipmentUnavailabilityExceptionRecord(
            id                = this[EquipmentUnavailabilityExceptions.id].value,
            unavailabilityId  = this[EquipmentUnavailabilityExceptions.unavailabilityId].value,
            originalDate      = this[EquipmentUnavailabilityExceptions.originalDate],
            exceptionType     = this[EquipmentUnavailabilityExceptions.exceptionType],
            rescheduledDate   = this[EquipmentUnavailabilityExceptions.rescheduledDate],
            rescheduledStartTime = this[EquipmentUnavailabilityExceptions.rescheduledStartTime],
            rescheduledEndTime   = this[EquipmentUnavailabilityExceptions.rescheduledEndTime],
            reason            = this[EquipmentUnavailabilityExceptions.reason],
        )
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew :appointment-core:test --tests "*.EquipmentUnavailabilityRepositoryTest" -i
```
Expected: `PASS`

- [ ] **Step 5: 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentUnavailabilityRepository.kt
git add appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentUnavailabilityRepositoryTest.kt
git commit -m "feat: EquipmentUnavailabilityRepository 추가 (CRUD + 예외 관리)"
```

---

## Task 5: UnavailabilityExpander — 반복 규칙 전개

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/UnavailabilityExpander.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/UnavailabilityExpanderTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class UnavailabilityExpanderTest {

    private val baseRecurring = EquipmentUnavailabilityRecord(
        id = 1L, equipmentId = 10L, clinicId = 1L,
        unavailableDate = null,
        isRecurring = true,
        recurringDayOfWeek = DayOfWeek.TUESDAY,
        effectiveFrom = LocalDate.of(2026, 4, 1),
        effectiveUntil = null,
        startTime = LocalTime.of(8, 0),
        endTime = LocalTime.of(10, 0),
        reason = "정기 점검",
    )

    @Test
    fun `반복 규칙 — 예외 없이 해당 요일만 전개`() {
        // 2026-04-01 ~ 2026-04-30: 화요일은 7, 14, 21, 28일
        val periods = UnavailabilityExpander.expand(
            rule = baseRecurring,
            exceptions = emptyList(),
            range = LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 4, 30),
        )

        periods shouldHaveSize 4
        periods.map { it.date } shouldBeEqualTo listOf(
            LocalDate.of(2026, 4, 7),
            LocalDate.of(2026, 4, 14),
            LocalDate.of(2026, 4, 21),
            LocalDate.of(2026, 4, 28),
        )
    }

    @Test
    fun `SKIP 예외 — 해당 날짜 제외`() {
        val skipException = EquipmentUnavailabilityExceptionRecord(
            id = 1L, unavailabilityId = 1L,
            originalDate = LocalDate.of(2026, 4, 14),
            exceptionType = ExceptionType.SKIP,
            rescheduledDate = null, rescheduledStartTime = null, rescheduledEndTime = null,
            reason = "이번 주 건너뜀",
        )

        val periods = UnavailabilityExpander.expand(
            rule = baseRecurring,
            exceptions = listOf(skipException),
            range = LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 4, 30),
        )

        periods shouldHaveSize 3
        periods.none { it.date == LocalDate.of(2026, 4, 14) }.let { assert(it) }
    }

    @Test
    fun `RESCHEDULE 예외 — 날짜와 시간 변경`() {
        val rescheduleException = EquipmentUnavailabilityExceptionRecord(
            id = 1L, unavailabilityId = 1L,
            originalDate = LocalDate.of(2026, 4, 7),
            exceptionType = ExceptionType.RESCHEDULE,
            rescheduledDate = LocalDate.of(2026, 4, 8),
            rescheduledStartTime = LocalTime.of(9, 0),
            rescheduledEndTime = LocalTime.of(11, 0),
            reason = "수요일로 변경",
        )

        val periods = UnavailabilityExpander.expand(
            rule = baseRecurring,
            exceptions = listOf(rescheduleException),
            range = LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 4, 30),
        )

        periods shouldHaveSize 4
        val changed = periods.first { it.date == LocalDate.of(2026, 4, 8) }
        changed.startTime shouldBeEqualTo LocalTime.of(9, 0)
    }

    @Test
    fun `일회성 규칙 — 해당 날짜만 반환`() {
        val oneTime = EquipmentUnavailabilityRecord(
            id = 2L, equipmentId = 10L, clinicId = 1L,
            unavailableDate = LocalDate.of(2026, 4, 15),
            isRecurring = false,
            recurringDayOfWeek = null,
            effectiveFrom = LocalDate.of(2026, 4, 15),
            effectiveUntil = LocalDate.of(2026, 4, 15),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(12, 0),
            reason = "긴급 수리",
        )

        val periods = UnavailabilityExpander.expand(
            rule = oneTime,
            exceptions = emptyList(),
            range = LocalDate.of(2026, 4, 1)..LocalDate.of(2026, 4, 30),
        )

        periods shouldHaveSize 1
        periods[0].date shouldBeEqualTo LocalDate.of(2026, 4, 15)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :appointment-core:test --tests "*.UnavailabilityExpanderTest" -i
```
Expected: `FAIL`

- [ ] **Step 3: UnavailabilityExpander 구현**

```kotlin
package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType

object UnavailabilityExpander {

    /**
     * 반복/일회성 규칙을 주어진 날짜 범위 내 [UnavailablePeriod] 목록으로 전개합니다.
     *
     * - 일회성: [EquipmentUnavailabilityRecord.unavailableDate]가 범위 내에 있으면 단건 반환
     * - 반복: [EquipmentUnavailabilityRecord.recurringDayOfWeek] 요일에 해당하는 모든 날짜 열거
     * - SKIP 예외: 해당 날짜 제외
     * - RESCHEDULE 예외: 날짜/시간을 변경된 값으로 대체
     */
    fun expand(
        rule: EquipmentUnavailabilityRecord,
        exceptions: List<EquipmentUnavailabilityExceptionRecord>,
        range: ClosedRange<LocalDate>,
    ): List<UnavailablePeriod> {
        val skipDates = exceptions
            .filter { it.exceptionType == ExceptionType.SKIP }
            .map { it.originalDate }
            .toSet()

        val rescheduleMap = exceptions
            .filter { it.exceptionType == ExceptionType.RESCHEDULE }
            .associateBy { it.originalDate }

        return if (!rule.isRecurring) {
            expandOneTime(rule, range, skipDates, rescheduleMap)
        } else {
            expandRecurring(rule, range, skipDates, rescheduleMap)
        }
    }

    private fun expandOneTime(
        rule: EquipmentUnavailabilityRecord,
        range: ClosedRange<LocalDate>,
        skipDates: Set<LocalDate>,
        rescheduleMap: Map<LocalDate, EquipmentUnavailabilityExceptionRecord>,
    ): List<UnavailablePeriod> {
        val date = rule.unavailableDate ?: return emptyList()
        if (date !in range || date in skipDates) return emptyList()

        val rescheduled = rescheduleMap[date]
        return listOf(
            UnavailablePeriod(
                equipmentId = rule.equipmentId,
                date        = rescheduled?.rescheduledDate ?: date,
                startTime   = rescheduled?.rescheduledStartTime ?: rule.startTime,
                endTime     = rescheduled?.rescheduledEndTime ?: rule.endTime,
            )
        )
    }

    private fun expandRecurring(
        rule: EquipmentUnavailabilityRecord,
        range: ClosedRange<LocalDate>,
        skipDates: Set<LocalDate>,
        rescheduleMap: Map<LocalDate, EquipmentUnavailabilityExceptionRecord>,
    ): List<UnavailablePeriod> {
        val dow = rule.recurringDayOfWeek ?: return emptyList()
        val effectiveStart = maxOf(rule.effectiveFrom, range.start)
        val effectiveEnd   = rule.effectiveUntil?.let { minOf(it, range.endInclusive) } ?: range.endInclusive

        val result = mutableListOf<UnavailablePeriod>()
        var current = effectiveStart.with(java.time.temporal.TemporalAdjusters.nextOrSame(dow))

        while (!current.isAfter(effectiveEnd)) {
            if (current !in skipDates) {
                val rescheduled = rescheduleMap[current]
                result.add(
                    UnavailablePeriod(
                        equipmentId = rule.equipmentId,
                        date        = rescheduled?.rescheduledDate ?: current,
                        startTime   = rescheduled?.rescheduledStartTime ?: rule.startTime,
                        endTime     = rescheduled?.rescheduledEndTime ?: rule.endTime,
                    )
                )
            }
            current = current.plusWeeks(1)
        }
        return result
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew :appointment-core:test --tests "*.UnavailabilityExpanderTest" -i
```
Expected: `PASS`

- [ ] **Step 5: 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/UnavailabilityExpander.kt
git add appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/UnavailabilityExpanderTest.kt
git commit -m "feat: UnavailabilityExpander — 반복 규칙 전개 (SKIP/RESCHEDULE 예외 지원)"
```

---

## Task 6: EquipmentUnavailabilityService — CRUD + 충돌 감지

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/EquipmentUnavailabilityService.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/EquipmentUnavailabilityServiceTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.repository.EquipmentUnavailabilityRepository
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.v1.core.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EquipmentUnavailabilityServiceTest {
    private val repo = EquipmentUnavailabilityRepository()
    private val service = EquipmentUnavailabilityService(repo)

    @BeforeEach
    fun setup() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                EquipmentUnavailabilities,
                EquipmentUnavailabilityExceptions,
            )
            EquipmentUnavailabilityExceptions.deleteAll()
            EquipmentUnavailabilities.deleteAll()
        }
    }

    @Test
    fun `특정 날짜의 장비 사용불가 기간 전개`() {
        transaction {
            repo.create(
                equipmentId = 1L, clinicId = 1L,
                unavailableDate = null,
                isRecurring = true,
                recurringDayOfWeek = DayOfWeek.TUESDAY,
                effectiveFrom = LocalDate.of(2026, 4, 1),
                effectiveUntil = null,
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(10, 0),
                reason = "정기 점검",
            )
        }

        val periods = transaction {
            service.findUnavailablePeriodsInRange(
                equipmentId = 1L,
                from = LocalDate.of(2026, 4, 1),
                to = LocalDate.of(2026, 4, 30),
            )
        }

        periods shouldHaveSize 4  // 화요일 4회
        periods.all { it.startTime == LocalTime.of(8, 0) }.let { assert(it) }
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :appointment-core:test --tests "*.EquipmentUnavailabilityServiceTest" -i
```
Expected: `FAIL`

- [ ] **Step 3: Service 구현**

```kotlin
package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.clinic.appointment.repository.EquipmentUnavailabilityRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EquipmentUnavailabilityService(
    private val repo: EquipmentUnavailabilityRepository = EquipmentUnavailabilityRepository(),
) {
    companion object : KLogging()

    /**
     * 장비의 특정 기간 내 사용불가 기간 전개 (반복 예외 포함).
     */
    fun findUnavailablePeriodsInRange(
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<UnavailablePeriod> {
        equipmentId.requirePositiveNumber("equipmentId")
        val rules = repo.findByEquipment(equipmentId, from, to)
        return rules.flatMap { rule ->
            val exceptions = repo.findExceptions(rule.id)
            UnavailabilityExpander.expand(rule, exceptions, from..to)
        }
    }

    /**
     * 특정 날짜에 사용불가인 장비 ID → UnavailablePeriod 맵 반환.
     */
    fun findUnavailableOnDate(clinicId: Long, date: LocalDate): Map<Long, List<UnavailablePeriod>> {
        clinicId.requirePositiveNumber("clinicId")
        val rules = repo.findByClinicOnDate(clinicId, date)
        return rules
            .groupBy { it.equipmentId }
            .mapValues { (_, ruleList) ->
                ruleList.flatMap { rule ->
                    val exceptions = repo.findExceptions(rule.id)
                    UnavailabilityExpander.expand(rule, exceptions, date..date)
                }
            }
    }

    fun create(
        equipmentId: Long, clinicId: Long,
        unavailableDate: LocalDate?,
        isRecurring: Boolean,
        recurringDayOfWeek: DayOfWeek?,
        effectiveFrom: LocalDate,
        effectiveUntil: LocalDate?,
        startTime: LocalTime, endTime: LocalTime,
        reason: String?,
    ): EquipmentUnavailabilityRecord {
        log.debug { "장비 사용불가 기간 등록: equipmentId=$equipmentId, isRecurring=$isRecurring" }
        return repo.create(
            equipmentId, clinicId, unavailableDate, isRecurring,
            recurringDayOfWeek, effectiveFrom, effectiveUntil, startTime, endTime, reason,
        )
    }

    fun delete(id: Long) {
        id.requirePositiveNumber("id")
        repo.delete(id)
    }

    fun addException(
        unavailabilityId: Long,
        originalDate: LocalDate,
        exceptionType: ExceptionType,
        rescheduledDate: LocalDate?,
        rescheduledStartTime: LocalTime?,
        rescheduledEndTime: LocalTime?,
        reason: String?,
    ): EquipmentUnavailabilityExceptionRecord {
        unavailabilityId.requirePositiveNumber("unavailabilityId")
        return repo.addException(
            unavailabilityId, originalDate, exceptionType,
            rescheduledDate, rescheduledStartTime, rescheduledEndTime, reason,
        )
    }

    fun deleteException(exceptionId: Long) {
        exceptionId.requirePositiveNumber("exceptionId")
        repo.deleteException(exceptionId)
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew :appointment-core:test --tests "*.EquipmentUnavailabilityServiceTest" -i
```
Expected: `PASS`

- [ ] **Step 5: 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/EquipmentUnavailabilityService.kt
git add appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/EquipmentUnavailabilityServiceTest.kt
git commit -m "feat: EquipmentUnavailabilityService 추가 (CRUD + 기간 전개)"
```

---

## Task 7: SlotCalculationService — 장비 사용불가 단계 추가

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationService.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationServiceTest.kt`

- [ ] **Step 1: 실패 테스트 추가**

`SlotCalculationServiceTest.kt`에 아래 케이스 추가:

```kotlin
@Test
fun `장비 사용불가 기간에는 해당 슬롯 제외`() {
    // given: 장비 10번이 2026-04-07 08:00~12:00 사용불가 등록됨
    transaction {
        equipmentUnavailabilityRepo.create(
            equipmentId = 10L, clinicId = clinicId,
            unavailableDate = LocalDate.of(2026, 4, 7),
            isRecurring = false,
            recurringDayOfWeek = null,
            effectiveFrom = LocalDate.of(2026, 4, 7),
            effectiveUntil = LocalDate.of(2026, 4, 7),
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(12, 0),
            reason = "긴급 수리",
        )
    }

    // when: 2026-04-07 슬롯 조회 (장비 10 필요한 진료 유형)
    val slots = service.findAvailableSlots(
        SlotQuery(
            clinicId = clinicId,
            doctorId = doctorId,
            treatmentTypeId = treatmentTypeRequiringEquipment10,
            date = LocalDate.of(2026, 4, 7),
        )
    )

    // then: 08:00~12:00 사이 슬롯 없음
    slots.none { it.startTime < LocalTime.of(12, 0) && it.endTime > LocalTime.of(8, 0) }
        .let { assert(it) { "장비 사용불가 시간에 슬롯이 반환되었습니다: $slots" } }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
./gradlew :appointment-core:test --tests "*.SlotCalculationServiceTest.장비 사용불가*" -i
```
Expected: `FAIL`

- [ ] **Step 3: SlotCalculationService 수정**

생성자에 `equipmentUnavailabilityService` 추가, 단계 8 삽입:

```kotlin
class SlotCalculationService(
    private val clinicRepository: ClinicRepository = ClinicRepository(),
    private val doctorRepository: DoctorRepository = DoctorRepository(),
    private val treatmentTypeRepository: TreatmentTypeRepository = TreatmentTypeRepository(),
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val holidayRepository: HolidayRepository = HolidayRepository(),
    private val equipmentUnavailabilityService: EquipmentUnavailabilityService = EquipmentUnavailabilityService(),
) {
```

`findAvailableSlots` 내부, 기존 step 8 (computeEffectiveRanges) 직전에 추가:

```kotlin
// 8. 장비 사용불가 기간 조회 (진료 유형이 장비를 필요로 하는 경우)
val equipmentUnavailablePeriods: Map<Long, List<UnavailablePeriod>> =
    if (treatment.requiresEquipment && requiredEquipment.isNotEmpty()) {
        equipmentUnavailabilityService.findUnavailableOnDate(query.clinicId, query.date)
            .filterKeys { it in requiredEquipment }
    } else emptyMap()
```

슬롯 필터링 단계에서 장비 충돌 제거:

```kotlin
// 장비 사용불가 시간과 겹치는 슬롯 제외
val blockedByEquipment = equipmentUnavailablePeriods.values.flatten()
    .any { unavail ->
        unavail.equipmentId in requiredEquipment &&
        candidate.start < unavail.endTime &&
        unavail.startTime < candidate.end
    }
if (blockedByEquipment) continue
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
./gradlew :appointment-core:test --tests "*.SlotCalculationServiceTest" -i
```
Expected: `PASS` (전체)

- [ ] **Step 5: 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationService.kt
git add appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationServiceTest.kt
git commit -m "feat: SlotCalculationService — 장비 사용불가 체크 단계 추가"
```

---

## Task 8: Solver — EquipmentUnavailabilityFact + ScheduleSolution 수정

**Files:**
- Create: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/EquipmentUnavailabilityFact.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/ScheduleSolution.kt`

- [ ] **Step 1: `EquipmentUnavailabilityFact.kt` 작성**

```kotlin
package io.bluetape4k.clinic.appointment.solver.domain

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 장비 사용불가 기간을 나타내는 Solver ProblemFact.
 *
 * [ScheduleSolution]에 [ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty]로 등록되어
 * Hard Constraint H11에서 참조됩니다.
 */
data class EquipmentUnavailabilityFact(
    val equipmentId: Long,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

- [ ] **Step 2: `ScheduleSolution.kt` — 필드 추가**

기존 `treatmentEquipments` 필드 다음에 추가:

```kotlin
@field:ProblemFactCollectionProperty
val equipmentUnavailabilities: List<EquipmentUnavailabilityFact> = emptyList(),
```

no-arg 생성자는 기존 `constructor()` 패턴을 유지 (변경 불필요).

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :appointment-solver:compileKotlin -i
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/EquipmentUnavailabilityFact.kt
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/ScheduleSolution.kt
git commit -m "feat: EquipmentUnavailabilityFact ProblemFact 추가 및 ScheduleSolution 등록"
```

---

## Task 9: Solver — H11 Hard Constraint 추가

**Files:**
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/AppointmentConstraintProvider.kt`

- [ ] **Step 1: `HardConstraints.kt` — H11 함수 추가**

파일 하단에 추가:

```kotlin
// ----------------------------------------------------------------
// H11: 장비 사용불가 기간 중 예약 배정 금지
// ----------------------------------------------------------------
fun equipmentUnavailabilityConflict(factory: ConstraintFactory): Constraint =
    factory.forEach(AppointmentPlanning::class.java)
        .filter { it.requiresEquipment && it.equipmentId != null && it.appointmentDate != null && it.startTime != null }
        .join(
            EquipmentUnavailabilityFact::class.java,
            Joiners.equal({ appt -> appt.equipmentId }, { fact -> fact.equipmentId }),
            Joiners.equal({ appt -> appt.appointmentDate }, { fact -> fact.date }),
            Joiners.filtering { appt, fact ->
                appt.startTime!! < fact.endTime && fact.startTime < appt.endTime!!
            },
        )
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("equipment-unavailability-conflict")
```

import 추가:
```kotlin
import io.bluetape4k.clinic.appointment.solver.domain.EquipmentUnavailabilityFact
```

- [ ] **Step 2: `AppointmentConstraintProvider.kt` — H11 등록**

`defineConstraints` 내 기존 hard constraint 목록 끝에 추가:

```kotlin
HardConstraints.equipmentUnavailabilityConflict(factory),
```

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :appointment-solver:compileKotlin -i
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/AppointmentConstraintProvider.kt
git commit -m "feat: Solver H11 — 장비 사용불가 기간 Hard Constraint 추가"
```

---

## Task 10: SolutionConverter — EquipmentUnavailabilityFact populate

**Files:**
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt`

- [ ] **Step 1: `buildSolution` 시그니처 + 구현 수정**

파라미터 추가:
```kotlin
fun buildSolution(
    // 기존 파라미터들 ...
    treatmentEquipments: List<TreatmentEquipmentRecord>,
    equipmentUnavailabilities: List<UnavailablePeriod>,   // 추가
    dateRange: ClosedRange<LocalDate>,
): ScheduleSolution {
```

import 추가:
```kotlin
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.solver.domain.EquipmentUnavailabilityFact
```

`ScheduleSolution(...)` 생성 시 필드 추가:
```kotlin
equipmentUnavailabilities = equipmentUnavailabilities.map {
    EquipmentUnavailabilityFact(
        equipmentId = it.equipmentId,
        date        = it.date,
        startTime   = it.startTime,
        endTime     = it.endTime,
    )
},
```

- [ ] **Step 2: 호출부 수정**

`SolverService.kt`에서 `buildSolution` 호출 시 `equipmentUnavailabilities` 파라미터 전달.
`ClosureRescheduleService.kt`에서도 동일하게 업데이트.

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :appointment-solver:compileKotlin :appointment-api:compileKotlin -i
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt
git commit -m "feat: SolutionConverter — EquipmentUnavailabilityFact populate"
```

---

## Task 11: REST API — Request/Response DTO

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/request/CreateEquipmentUnavailabilityRequest.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/request/UpdateEquipmentUnavailabilityRequest.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/request/UnavailabilityExceptionRequest.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/response/UnavailabilityConflictResponse.kt`

- [ ] **Step 1: Request DTO 작성**

```kotlin
// CreateEquipmentUnavailabilityRequest.kt
package io.bluetape4k.clinic.appointment.api.dto.request

import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class CreateEquipmentUnavailabilityRequest(
    val unavailableDate: LocalDate?,
    val isRecurring: Boolean,
    val recurringDayOfWeek: DayOfWeek?,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val reason: String?,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

// UpdateEquipmentUnavailabilityRequest.kt
data class UpdateEquipmentUnavailabilityRequest(
    val effectiveUntil: LocalDate?,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
    val reason: String?,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

// UnavailabilityExceptionRequest.kt
data class UnavailabilityExceptionRequest(
    val originalDate: LocalDate,
    val exceptionType: ExceptionType,
    val rescheduledDate: LocalDate?,
    val rescheduledStartTime: LocalTime?,
    val rescheduledEndTime: LocalTime?,
    val reason: String?,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}
```

- [ ] **Step 2: Response DTO 작성**

```kotlin
// UnavailabilityConflictResponse.kt
package io.bluetape4k.clinic.appointment.api.dto.response

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

data class ConflictingAppointmentResponse(
    val appointmentId: Long,
    val patientName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val doctorId: Long,
    val equipmentId: Long,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

data class UnavailabilityConflictResponse(
    val unavailabilityId: Long,
    val conflictCount: Int,
    val conflicts: List<ConflictingAppointmentResponse>,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :appointment-api:compileKotlin -i
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/
git commit -m "feat: 장비 사용불가 API Request/Response DTO 추가 (Serializable)"
```

---

## Task 12: REST Controller

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentUnavailabilityController.kt`

- [ ] **Step 1: Controller 작성**

```kotlin
package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.request.CreateEquipmentUnavailabilityRequest
import io.bluetape4k.clinic.appointment.api.dto.request.UnavailabilityExceptionRequest
import io.bluetape4k.clinic.appointment.api.dto.request.UpdateEquipmentUnavailabilityRequest
import io.bluetape4k.clinic.appointment.api.dto.response.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.response.ConflictingAppointmentResponse
import io.bluetape4k.clinic.appointment.api.dto.response.UnavailabilityConflictResponse
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.service.EquipmentUnavailabilityService
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities")
class EquipmentUnavailabilityController(
    private val service: EquipmentUnavailabilityService,
    private val appointmentRepository: AppointmentRepository,
) {
    companion object : KLogging()

    @GetMapping
    fun list(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<ApiResponse<List<EquipmentUnavailabilityRecord>>> {
        val records = transaction { service.findUnavailabilityRecords(equipmentId, from, to) }
        return ResponseEntity.ok(ApiResponse(success = true, data = records))
    }

    @PostMapping
    fun create(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @RequestBody request: CreateEquipmentUnavailabilityRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityRecord>> {
        val record = transaction {
            service.create(
                equipmentId = equipmentId, clinicId = clinicId,
                unavailableDate = request.unavailableDate,
                isRecurring = request.isRecurring,
                recurringDayOfWeek = request.recurringDayOfWeek,
                effectiveFrom = request.effectiveFrom,
                effectiveUntil = request.effectiveUntil,
                startTime = request.startTime,
                endTime = request.endTime,
                reason = request.reason,
            )
        }
        return ResponseEntity.ok(ApiResponse(success = true, data = record))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<Unit>> {
        transaction { service.delete(id) }
        return ResponseEntity.ok(ApiResponse(success = true))
    }

    @PostMapping("/{id}/exceptions")
    fun addException(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @RequestBody request: UnavailabilityExceptionRequest,
    ): ResponseEntity<ApiResponse<EquipmentUnavailabilityExceptionRecord>> {
        val exception = transaction {
            service.addException(
                unavailabilityId = id,
                originalDate = request.originalDate,
                exceptionType = request.exceptionType,
                rescheduledDate = request.rescheduledDate,
                rescheduledStartTime = request.rescheduledStartTime,
                rescheduledEndTime = request.rescheduledEndTime,
                reason = request.reason,
            )
        }
        return ResponseEntity.ok(ApiResponse(success = true, data = exception))
    }

    @DeleteMapping("/{id}/exceptions/{exId}")
    fun deleteException(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
        @PathVariable exId: Long,
    ): ResponseEntity<ApiResponse<Unit>> {
        transaction { service.deleteException(exId) }
        return ResponseEntity.ok(ApiResponse(success = true))
    }

    @GetMapping("/{id}/conflicts")
    fun conflicts(
        @PathVariable clinicId: Long,
        @PathVariable equipmentId: Long,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<UnavailabilityConflictResponse>> {
        val result = transaction {
            val record = service.findById(id) ?: return@transaction null
            val periods = service.findUnavailablePeriodsInRange(
                equipmentId = equipmentId,
                from = record.effectiveFrom,
                to = record.effectiveUntil ?: record.effectiveFrom.plusYears(1),
            )
            val conflicting = periods.flatMap { period ->
                appointmentRepository.findOverlappingByEquipment(
                    equipmentId = period.equipmentId,
                    date = period.date,
                    startTime = period.startTime,
                    endTime = period.endTime,
                ).map { appt ->
                    ConflictingAppointmentResponse(
                        appointmentId = appt.id,
                        patientName = appt.patientName,
                        appointmentDate = appt.appointmentDate,
                        startTime = appt.startTime,
                        endTime = appt.endTime,
                        doctorId = appt.doctorId,
                        equipmentId = period.equipmentId,
                    )
                }
            }
            UnavailabilityConflictResponse(
                unavailabilityId = id,
                conflictCount = conflicting.size,
                conflicts = conflicting,
            )
        }
        return if (result != null) {
            ResponseEntity.ok(ApiResponse(success = true, data = result))
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
```

- [ ] **Step 2: AppointmentRepository에 `findOverlappingByEquipment` 추가**

`AppointmentRepository.kt`에 메서드 추가:

```kotlin
fun findOverlappingByEquipment(
    equipmentId: Long,
    date: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime,
): List<AppointmentRecord> =
    Appointments.selectAll()
        .where {
            (Appointments.equipmentId eq equipmentId) and
            (Appointments.appointmentDate eq date) and
            (Appointments.startTime less endTime) and
            (Appointments.endTime greater startTime) and
            (Appointments.status notInList CANCELLED_STATUSES)
        }
        .map { it.toAppointmentRecord() }
```

- [ ] **Step 3: 전체 빌드 확인**

```bash
./gradlew :appointment-api:build -x test -i
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 통합 테스트 실행**

```bash
./gradlew :appointment-core:test :appointment-solver:test :appointment-api:test -i
```
Expected: `PASS` (전체)

- [ ] **Step 5: 최종 커밋**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/EquipmentUnavailabilityController.kt
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt
git commit -m "feat: EquipmentUnavailabilityController REST API 추가 (CRUD + 충돌 감지)"
```

---

## 최종 검증 체크리스트

```
[ ] ./gradlew :appointment-core:build   — PASS
[ ] ./gradlew :appointment-solver:build — PASS
[ ] ./gradlew :appointment-api:build    — PASS
[ ] 모든 DTO가 Serializable + serialVersionUID = 1L 구현
[ ] 모든 인자 검증이 bluetape4k requireXxx() 사용
[ ] 모든 클래스가 companion object : KLogging() 사용
[ ] SlotCalculationService 검증 순서: Holiday→ClinicClosure→OperatingHours→BreakTime→DoctorSchedule→DoctorAbsence→EquipmentUnavailability
[ ] Flyway V2 적용 확인
[ ] TODO 주석 (Doctor/Clinic 반복 예외 리팩터링) 코드에 남아있음
```
