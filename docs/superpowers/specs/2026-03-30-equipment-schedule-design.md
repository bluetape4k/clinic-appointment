# 장비 스케줄 관리 설계 스펙

- **날짜**: 2026-03-30
- **작성자**: Claude (설계 협의: debop)
- **대상 모듈**: `appointment-core`, `appointment-solver`, `appointment-api`
- **상태**: 승인됨

---

## 1. 배경 및 목표

현재 시스템은 의사 부재(`DoctorAbsences`)와 병원 휴진(`ClinicClosures`)에 의한 예약 제약은 지원하지만,
**의료 장비의 사용불가 기간(유지보수, 고장 등)** 은 반영되지 않는다.

장비별 점유 시간(`usageDurationMinutes`)도 `Equipments` 테이블에 존재하지만,
실제 슬롯 계산 및 Solver에 충분히 활용되지 않고 있다.

### 목표

1. 장비 사용불가 기간(일회성 + 반복 패턴 + 예외) 등록 및 관리
2. 장비별 소요시간을 슬롯 계산 및 Timefold Solver 최적화에 반영
3. 사용불가 기간과 충돌하는 기존 예약 감지 (Phase 1)
4. 향후 자동 재배정 지원 (Phase 2/3)

---

## 2. 범위

### 이번 구현 (Phase 1)

- 장비 사용불가 기간 CRUD (일회성 + 반복 + 반복 예외)
- 슬롯 계산 파이프라인에 장비 사용불가 체크 추가
- Timefold Solver `EquipmentUnavailabilityFact` + H9 제약조건 확장
- 충돌 예약 감지 API (알림 전용, 자동 재배정 없음)

### 향후 구현

- **Phase 2**: 관리자가 선택하는 자동 재배정 후보 생성 (`ClosureRescheduleService` 연동)
- **Phase 3**: 사용불가 기간 등록 전 충돌 미리보기 + 자동 재배정 통합

---

## 3. 데이터 모델

### 3.1 `EquipmentUnavailabilities` (신규)

```kotlin
object EquipmentUnavailabilities : LongIdTable("equipment_unavailabilities") {
    val equipmentId  = reference("equipment_id", Equipments)
    val clinicId     = reference("clinic_id", Clinics)

    // 일회성: unavailableDate 사용, isRecurring = false
    val unavailableDate = date("unavailable_date").nullable()

    // 반복 패턴
    val isRecurring        = bool("is_recurring").default(false)
    val recurringDayOfWeek = enumerationByName<DayOfWeek>("recurring_day_of_week", 10).nullable()
    val effectiveFrom      = date("effective_from")           // 반복 시작일
    val effectiveUntil     = date("effective_until").nullable() // null = 무기한

    val startTime = time("start_time")
    val endTime   = time("end_time")
    val reason    = varchar("reason", 500).nullable()

    init {
        index(false, equipmentId, effectiveFrom)
        index(false, clinicId, effectiveFrom)
        index(false, equipmentId, recurringDayOfWeek)
    }
}
```

### 3.2 `EquipmentUnavailabilityExceptions` (신규)

반복 스케줄의 특정 발생일에 대한 예외 처리.

```kotlin
object EquipmentUnavailabilityExceptions : LongIdTable("equipment_unavailability_exceptions") {
    val unavailabilityId = reference("unavailability_id", EquipmentUnavailabilities,
                               onDelete = ReferenceOption.CASCADE)
    val originalDate     = date("original_date")   // 원래 발생 예정일

    val exceptionType         = enumerationByName<ExceptionType>("exception_type", 20)
    val rescheduledDate       = date("rescheduled_date").nullable()
    val rescheduledStartTime  = time("rescheduled_start_time").nullable()
    val rescheduledEndTime    = time("rescheduled_end_time").nullable()
    val reason                = varchar("reason", 500).nullable()

    init {
        index(false, unavailabilityId, originalDate)
    }
}

enum class ExceptionType {
    SKIP,        // 이번 발생 건너뜀
    RESCHEDULE,  // 날짜/시간 변경
}
```

### 3.3 운영 패턴

| 상황 | 처리 방법 |
|------|----------|
| 이번만 skip | `ExceptionType.SKIP` + `originalDate` |
| 이번만 다른 날/시간 | `ExceptionType.RESCHEDULE` + `rescheduledDate/Time` |
| 특정 시점 이후 변경 | 기존 레코드 `effectiveUntil` 설정 + 새 레코드 `effectiveFrom` 신규 등록 |

### 3.4 기존 `Equipments` 테이블 변경 없음

`usageDurationMinutes` 컬럼 기존 존재 → 스키마 변경 불필요.

---

## 4. DTO

> **규칙**: 모든 DTO는 `Serializable` 구현 + `serialVersionUID = 1L` 필수.

```kotlin
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

data class UnavailablePeriod(
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class ConflictingAppointmentResponse(
    val appointmentId: Long,
    val patientName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val doctorId: Long,
    val equipmentId: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class UnavailabilityConflictResponse(
    val unavailabilityId: Long,
    val conflictCount: Int,
    val conflicts: List<ConflictingAppointmentResponse>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

---

## 5. 서비스 레이어

### 5.1 `UnavailabilityExpander`

반복 규칙을 특정 날짜 범위 내 실제 `UnavailablePeriod` 목록으로 전개.
예외(SKIP/RESCHEDULE) 적용 포함.

```kotlin
object UnavailabilityExpander {
    /**
     * 반복 규칙을 주어진 범위 내 실제 사용불가 기간으로 전개한다.
     * SKIP 예외는 제외, RESCHEDULE 예외는 변경된 날짜/시간으로 대체한다.
     */
    fun expand(
        rule: EquipmentUnavailabilityRecord,
        exceptions: List<EquipmentUnavailabilityExceptionRecord>,
        range: ClosedRange<LocalDate>,
    ): List<UnavailablePeriod>
}
```

### 5.2 `EquipmentUnavailabilityService`

```kotlin
interface EquipmentUnavailabilityService {
    // CRUD
    fun create(request: CreateEquipmentUnavailabilityRequest): EquipmentUnavailabilityRecord
    fun update(id: Long, request: UpdateEquipmentUnavailabilityRequest): EquipmentUnavailabilityRecord
    fun delete(id: Long)

    // 반복 예외
    fun addException(
        unavailabilityId: Long,
        request: UnavailabilityExceptionRequest,
    ): EquipmentUnavailabilityExceptionRecord
    fun deleteException(exceptionId: Long)

    // 조회
    fun findByEquipment(equipmentId: Long, from: LocalDate, to: LocalDate): List<EquipmentUnavailabilityRecord>
    fun findUnavailablePeriodsOnDate(clinicId: Long, date: LocalDate): Map<Long, List<UnavailablePeriod>>

    // 충돌 감지 (Phase 1)
    fun detectConflicts(unavailabilityId: Long): UnavailabilityConflictResponse
    fun previewConflicts(request: CreateEquipmentUnavailabilityRequest): UnavailabilityConflictResponse
}
```

### 5.3 `SlotCalculationService` — 검증 파이프라인 변경

슬롯 가용성 검사 순서 (좁은 범위를 뒤에서 체크, early-return):

```
1. Holiday              (공휴일 — 전사 차단)
2. ClinicClosure        (병원 임시 휴진)
3. OperatingHours       (병원 영업시간)
4. BreakTime            (병원 휴식시간)
5. DoctorSchedule       (의사 근무 스케줄)
6. DoctorAbsence        (의사 임시 부재)
7. EquipmentUnavailability  ← 신규 추가
```

각 단계에서 사용불가 판정 시 즉시 `emptyList()` 반환 (early-return).

---

## 6. Timefold Solver 통합

### 6.1 새 ProblemFact

```kotlin
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

### 6.2 `ScheduleSolution` 변경

```kotlin
@PlanningSolution
class ScheduleSolution(
    // 기존 필드 ...

    @field:ProblemFactCollectionProperty
    val equipmentUnavailabilities: List<EquipmentUnavailabilityFact> = emptyList(), // 추가
)
```

### 6.3 Hard Constraint 확장 (H9)

기존 H9(`equipmentAvailability`)에 사용불가 기간 충돌 체크 추가:

```kotlin
// 장비 사용불가 기간 중 예약 배정 금지
fun equipmentUnavailabilityConflict(factory: ConstraintFactory): Constraint =
    factory.forEach(AppointmentPlanning::class.java)
        .filter { it.requiresEquipment && it.equipmentId != null }
        .join(
            EquipmentUnavailabilityFact::class.java,
            equal({ it.equipmentId }, { it.equipmentId }),
            equal({ it.appointmentDate }, { it.date }),
        )
        .filter { appt, unavail ->
            appt.startTime!! < unavail.endTime && unavail.startTime < appt.endTime()
        }
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("equipment-unavailability-conflict")
```

---

## 7. REST API

```
GET    /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities
       ?from=&to=
POST   /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities
PUT    /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}
DELETE /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}

POST   /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}/exceptions
DELETE /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}/exceptions/{exId}

GET    /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}/conflicts
POST   /api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/preview-conflicts
```

---

## 8. Flyway 마이그레이션

신규 SQL 파일 2개 추가 (`appointment-api/src/main/resources/db/migration/`):

```
V{next}__add_equipment_unavailabilities.sql
```

- `equipment_unavailabilities` 테이블 생성
- `equipment_unavailability_exceptions` 테이블 생성
- 관련 인덱스 생성

---

## 9. bluetape4k-patterns 준수 사항

구현 시 아래 패턴을 **반드시** 적용한다.

| 항목 | 적용 방법 |
|------|----------|
| 인자 검증 | `equipmentId.requirePositiveNumber("equipmentId")` 등 bluetape4k 확장 사용. `require()`/`requireNotNull()` 금지 |
| 로깅 | `companion object : KLogging()` + `log.debug { }` lazy 람다 |
| DTO/Value | `Serializable` 구현 + `serialVersionUID = 1L` |
| 예외 처리 | `runCatching { }.onFailure { log.warn(e) { } }` |
| 상수 | Magic literal 금지, `const val` 또는 enum 사용 |
| 트랜잭션 | 모든 Exposed 작업은 `transaction { }` 내부에서 실행 |
| DB 초기화 (테스트) | `@BeforeEach`: `SchemaUtils.createMissingTablesAndColumns()` + `Table.deleteAll()` |

---

## 10. TODO: Doctor/Clinic 반복 스케줄 예외 처리 리팩터링

> 장비 스케줄 예외 패턴 검증 후 아래 항목을 동일하게 적용할 것.

```
// TODO: [FUTURE] EquipmentUnavailabilities 패턴 검증 후 적용
// 1. DoctorSchedules: effectiveFrom/effectiveUntil + DoctorScheduleExceptions 테이블 추가
//    - 현재: dayOfWeek + startTime/endTime (예외 없음)
//    - 목표: 반복 패턴 + SKIP/RESCHEDULE 예외 지원
// 2. ClinicBreakTimes: 동일 패턴 적용
//    - 현재: BreakTimes (요일별 고정) + ClinicDefaultBreakTimes
//    - 목표: 반복 패턴 + 예외 지원
// 3. ClinicClosures: 반복 패턴 + 예외 지원으로 확장
//    - 현재: 일회성 closureDate만 지원
//    - 목표: 정기 휴진(예: 매월 마지막 주 수요일) + 예외 지원
// Ref: docs/superpowers/specs/2026-03-30-equipment-schedule-design.md
```

---

## 11. 단계별 구현 로드맵

| Phase | 내용 | 상태 |
|-------|------|------|
| Phase 1 | 장비 사용불가 CRUD + 슬롯 계산 통합 + Solver H9 확장 + 충돌 알림 | **이번 구현** |
| Phase 2 | 관리자가 선택하는 자동 재배정 후보 생성 | 향후 |
| Phase 3 | 등록 전 충돌 미리보기 + 자동 재배정 통합 | 향후 |
