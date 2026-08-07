# Solver nullable planning boundary 구현 계획

> **에이전트 작업자 참고:** 이 계획을 task 단위로 구현하려면 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans` 하위 스킬을 반드시 사용한다. 단계 추적에는 checkbox(`- [ ]`) 구문을 사용한다.

**목표:** Timefold partial-solution scoring과 불완전한 non-pinned appointment를
변환 결과에서 제외하는 기존 규칙을 유지하면서 `appointment-solver`의 우발적인
nullable assertion failure를 제거한다.

**구조:** planning entity가 완전할 때만 네 가지 파생 assignment 값을 제공하는
internal inline domain extension `AppointmentPlanning.withAssigned` 하나를
추가한다. Hard constraint는 multi-value predicate와 indexed join의 nullable
property/method-reference key에 이를 사용한다. `SolutionConverter`는
`mapNotNull`과 함께 사용하고, `SolverService`는 repository와 solver lifecycle
불변식에 명시적인 `checkNotNull`을 사용한다.

**기술 스택:** Kotlin 2.3, Timefold Solver 2.2, JUnit 5, MockK, Gradle,
`appointment-solver` module.

**실행 상태:** 완료 (2026-08-02). 구현·보정 커밋 `1d91f65`, `435d3cf`,
`649f95c`, `29618ec`, `cfd72a4`, `c5c6466`와 모듈 검증·독립 리뷰 결과를
아래에 기록했다.

---

## 범위 지도와 불변식

| 파일 | 책임 | 계획된 변경 |
|---|---|---|
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignment.kt` | 완전한 planning assignment의 domain boundary | KDoc가 있는 internal inline `withAssigned` extension 생성; mutable state나 default value는 사용하지 않음 |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignmentTest.kt` | helper contract | complete/partial entity를 위한 RED/GREEN test 생성 |
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt` | H1~H11 hard constraint | 모든 `!!` 제거; indexed equality/overlap join과 기존 filter/weight 유지 |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/ConstraintVerifierTest.kt` | constraint regression 동작 | partial entity case를 추가하고 기존 full entity penalty assertion 유지 |
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt` | planning-to-record 변환 | assertion 기반 extraction을 `withAssigned` + `mapNotNull`로 교체 |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverterTest.kt` | extraction 동작 | 누락된 각 assignment component와 complete conversion의 coverage 확장 |
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt` | solver 및 persistence boundary | ID/score assertion을 contextual `checkNotNull`로 교체 |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt` | solver lifecycle boundary | score 누락을 위한 mocked solver test 추가 |

구현에서는 `AppointmentPlanning` variable annotation을 변경하거나
`allowsUnassigned=true`를 도입하거나 `forEachIncludingUnassigned()`를 추가하거나
constraint weight를 변경하거나 incomplete-result diagnostic을 노출해서는 안 된다.
이 항목들은 이슈의 명시적인 범위 밖이다.

## 작업 1: complete-assignment helper 추가 및 고정

**파일:**

- 생성: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignmentTest.kt`
- 생성: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignment.kt`

- [x] **Step 1: 실패하는 helper contract test 작성**

production assignment object를 도입하지 않고 네 가지 값(파생 end time 포함)이
모두 전달되는지 검증하도록 local snapshot type으로 test를 작성한다.

```kotlin
package io.bluetape4k.clinic.appointment.solver.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AppointmentPlanningAssignmentTest {

    private data class Snapshot(
        val doctorId: Long,
        val appointmentDate: LocalDate,
        val startTime: LocalTime,
        val endTime: LocalTime,
    )

    private val date = LocalDate.of(2026, 3, 23)
    private val start = LocalTime.of(9, 0)

    @Test
    fun `withAssigned supplies all non-null assignment values`() {
        val planning = AppointmentPlanning(
            id = 1L,
            durationMinutes = 30,
            doctorId = 10L,
            appointmentDate = date,
            startTime = start,
        )

        val snapshot = planning.withAssigned { doctorId, appointmentDate, startTime, endTime ->
            Snapshot(doctorId, appointmentDate, startTime, endTime)
        }

        assertEquals(Snapshot(10L, date, start, LocalTime.of(9, 30)), snapshot)
    }

    @Test
    fun `withAssigned returns null for every incomplete planning variable`() {
        val partialEntities = listOf(
            AppointmentPlanning(doctorId = null, appointmentDate = date, startTime = start),
            AppointmentPlanning(doctorId = 10L, appointmentDate = null, startTime = start),
            AppointmentPlanning(doctorId = 10L, appointmentDate = date, startTime = null),
        )

        partialEntities.forEach { planning ->
            val snapshot = planning.withAssigned { doctorId, appointmentDate, startTime, endTime ->
                Snapshot(doctorId, appointmentDate, startTime, endTime)
            }
            assertNull(snapshot)
        }
    }
}
```

- [x] **Step 2: helper test 실행 및 RED 확인**

실행:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanningAssignmentTest"
```

예상 결과: 아직 `withAssigned`가 없으므로 compilation이 실패한다.

- [x] **Step 3: 최소 inline extension 구현**

다음 계약으로 production file을 생성한다. solving 중 Timefold가 planning
variable을 변경하므로 block은 inline으로 유지하고 snapshot을 cache하지 않는다.

```kotlin
package io.bluetape4k.clinic.appointment.solver.domain

import java.time.LocalDate
import java.time.LocalTime

inline fun <T> AppointmentPlanning.withAssigned(
    block: (doctorId: Long, appointmentDate: LocalDate, startTime: LocalTime, endTime: LocalTime) -> T,
): T? {
    val doctorId = this.doctorId ?: return null
    val appointmentDate = this.appointmentDate ?: return null
    val startTime = this.startTime ?: return null
    val endTime = this.endTime ?: return null
    return block(doctorId, appointmentDate, startTime, endTime)
}
```

helper는 불완전한 planning state에서 `null`을 반환하며 `0L`, `LocalDate.MIN`,
`LocalTime.MIN` 또는 다른 default로 대체하지 않는다.

- [x] **Step 4: helper test 실행 및 GREEN 확인**

같은 targeted Gradle command를 실행한다. 예상 결과: test 2개가 통과한다.

- [x] **Step 5: 격리된 domain boundary commit**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignment.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignmentTest.kt
git commit -m $'Make complete planning assignment explicit\n\nAdd a zero-state helper for nullable Timefold planning variables.\n\nConstraint: Preserve mutable partial-solution input semantics.\nRejected: Store an assignment snapshot on the planning entity | Timefold mutates variables during solving.\nConfidence: high\nScope-risk: narrow\nDirective: Keep incomplete planning state out of final record conversion.\nTested: AppointmentPlanningAssignmentTest\nNot-tested: Full appointment-solver build.'
```

## 작업 2: join을 바꾸지 않고 hard constraint의 assertion 제거

**파일:**

- 수정: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/ConstraintVerifierTest.kt`
- 수정: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt`

- [x] **Step 1: constraint 편집 전에 partial planning regression test 추가**

기존 파일이 다루는 date, doctor, time predicate 형태를 실행하는 partial
appointment와 fact를 `ConstraintVerifierTest`에 추가한다. 기존 full entity test는
변경하지 않는다. 새 assertion으로 unassigned entity가 hard penalty에 기여하지
않고 예외를 던지지 않는지 검증한다.

```kotlin
@Test
fun `partially initialized planning entity is ignored by representative hard constraints`() {
    val partial = appointment(doctorId = null)
    val operatingHours = OperatingHoursRecord(
        clinicId = 10L,
        dayOfWeek = DayOfWeek.MONDAY,
        openTime = LocalTime.of(9, 0),
        closeTime = LocalTime.of(18, 0),
    )
    val doctor = DoctorFact(
        id = 100L,
        clinicId = 10L,
        providerType = "DOCTOR",
        maxConcurrentPatients = null,
    )
    val closure = ClinicClosureRecord(
        clinicId = 10L,
        closureDate = monday,
        isFullDay = true,
    )

    constraintVerifier
        .verifyThat { _, factory -> HardConstraints.withinOperatingHours(factory) }
        .given(partial, operatingHours)
        .penalizesBy(0)
    constraintVerifier
        .verifyThat { _, factory -> HardConstraints.noClinicClosureConflict(factory) }
        .given(partial, closure)
        .penalizesBy(0)
    constraintVerifier
        .verifyThat { _, factory -> HardConstraints.providerTypeMatch(factory) }
        .given(partial, doctor)
        .penalizesBy(0)
    constraintVerifier
        .verifyThat { _, factory -> HardConstraints.doctorBelongsToClinic(factory) }
        .given(partial, doctor)
        .penalizesBy(0)
}
```

- [x] **Step 2: refactor 전에 constraint regression test 실행**

실행:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.constraint.ConstraintVerifierTest"
```

예상 결과: 기존 suite가 통과한다. 이 test는 동작을 고정한다. assertion을
호출하기 전에 Timefold가 partial entity를 이미 제외할 수도 있으므로 production
변경의 RED signal은 helper compilation test와 source-level `!!` audit다.

- [x] **Step 3: 모든 hard-constraint `!!` 사용을 safe key와 helper predicate로 교체**

`HardConstraints.kt`에 다음의 정확한 transformation rule을 적용한다.

```kotlin
// Indexed key: keep the join indexed and return a nullable key instead of asserting.
Joiners.equal(
    { appt -> appt.appointmentDate?.dayOfWeek },
    { oh -> oh.dayOfWeek },
)

Joiners.equal(
    { appt -> appt.doctorId },
    { ds -> ds.doctorId },
)

// Multi-value predicate: consume a complete local assignment only when available.
Joiners.filtering { appt, oh ->
    appt.withAssigned { _, _, startTime, endTime ->
        oh.isActive && startTime >= oh.openTime && endTime <= oh.closeTime
    } == true
}
```

다음 equality key에는 nullable direct property 또는 method reference를 사용한다.

- H1/H4a: `appointmentDate?.dayOfWeek`를 weekday fact에 사용한다.
- H2/H3: `doctorId`와 `appointmentDate`를 doctor/date fact에 사용한다.
- H5/H6: `appointmentDate`를 closure/holiday date에 사용한다.
- H7/H8: `doctorId`, `equipmentId`, `appointmentDate` key를 사용하고 기존
  `overlapping`, `lessThan` joiner를 유지한다.
- H9/H10: `AppointmentPlanning::doctorId`를 `DoctorFact::id`에 연결한다.
- H11: `AppointmentPlanning::equipmentId`와 `appointmentDate` key를 사용한다.

H1, H2, H3, H4a, H4b, H5, H11의 모든 time-range predicate에는
`withAssigned { ... } == true`를 사용한다. business meaning을 표현하는
명시적인 `equipmentId != null`과 `requiresEquipment` filter는 유지한다. Kotlin
nullability를 피하려고 indexed equality join을 broad `Joiners.filtering` join으로
바꾸지 않는다. 그러면 constraint stream의 performance shape가 바뀐다.

- [x] **Step 4: refactor 후 compile 및 constraint test 실행**

실행:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.constraint.ConstraintVerifierTest"
```

예상 결과: 기존 full-entity penalty test와 새 partial-entity test가 모두
`NullPointerException` 없이 통과한다.

- [x] **Step 5: hard-constraint boundary commit**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/ConstraintVerifierTest.kt
git commit -m $'Make hard constraints tolerate partial planning state\n\nUse safe assignment locals while retaining indexed constraint joins.\n\nConstraint: Preserve H1-H11 scoring and default unassigned filtering.\nRejected: forEachIncludingUnassigned | Null is not a final clinic appointment state.\nConfidence: high\nScope-risk: moderate\nDirective: Do not reintroduce not-null assertions after constraint filters.\nTested: ConstraintVerifierTest\nNot-tested: Full appointment-solver build.'
```

## 작업 3: 결과 extraction에서 불완전한 entity를 명시적으로 건너뛰기

**파일:**

- 수정: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverterTest.kt`
- 수정: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt`

- [x] **Step 1: missing-start extraction regression 추가**

기존 missing-doctor 및 missing-date/time test 옆에 test를 추가한다. 이 사례가
time/end-time boundary를 helper가 보호하는지 검증하도록 doctor와 date는 채워 둔다.

```kotlin
@Test
fun `extractResults skips appointments without start time`() {
    val incomplete = AppointmentPlanning(
        id = 1L,
        clinicId = clinicId,
        patientName = "Jane Doe",
        durationMinutes = 30,
        doctorId = doctorId,
        appointmentDate = LocalDate.of(2026, 4, 6),
        startTime = null,
    )

    val results = SolutionConverter.extractResults(
        ScheduleSolution(appointments = listOf(incomplete)),
        mapOf(appointment.id!! to appointment),
    )

    results shouldHaveSize 0
}
```

- [x] **Step 2: production code 변경 전에 converter test 실행**

실행:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.converter.SolutionConverterTest"
```

예상 결과: 현재 filter 아래에서 기존 extraction test와 새 regression test가
통과한다. helper compilation test는 새 production API를 위한 RED guard로 남는다.

- [x] **Step 3: `extractResults`를 helper 기반 `mapNotNull`으로 교체**

extraction pipeline만 교체하고 모든 record field와 optional original metadata는
그대로 유지한다.

```kotlin
fun extractResults(
    solution: ScheduleSolution,
    originalAppointments: Map<Long, AppointmentRecord>,
): List<AppointmentRecord> =
    solution.appointments
        .filter { !it.pinned }
        .mapNotNull { planning ->
            planning.withAssigned { doctorId, appointmentDate, startTime, endTime ->
                val original = originalAppointments[planning.id]
                AppointmentRecord(
                    id = planning.id,
                    clinicId = planning.clinicId,
                    doctorId = doctorId,
                    treatmentTypeId = planning.treatmentTypeId,
                    equipmentId = planning.equipmentId,
                    consultationTopicId = original?.consultationTopicId,
                    consultationMethod = original?.consultationMethod,
                    rescheduleFromId = original?.rescheduleFromId,
                    patientName = planning.patientName,
                    patientPhone = original?.patientPhone,
                    memberId = original?.memberId,
                    appointmentDate = appointmentDate,
                    startTime = startTime,
                    endTime = endTime,
                    status = original?.status ?: AppointmentState.REQUESTED,
                )
            }
        }
```

- [x] **Step 4: converter test suite 실행 및 결과 동등성 검증**

같은 targeted converter command를 실행한다. 예상 결과: complete non-pinned
appointment는 original metadata를 계속 유지하고 pinned appointment는 계속
제외되며 모든 incomplete variant는 record 0개를 반환한다.

- [x] **Step 5: converter boundary commit**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverterTest.kt
git commit -m $'Preserve incomplete appointment result semantics safely\n\nConvert only complete non-pinned assignments without assertion-based null handling.\n\nConstraint: Keep incomplete planning entities out of AppointmentRecord results.\nRejected: Return a partial AppointmentRecord | Downstream callers require complete scheduling fields.\nConfidence: high\nScope-risk: narrow\nDirective: Preserve optional original metadata lookup exactly.\nTested: SolutionConverterTest\nNot-tested: Full appointment-solver build.'
```

## 작업 4: solver와 repository 불변식을 명시화

**파일:**

- 수정: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt`
- 수정: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt`

- [x] **Step 1: mocked solver를 사용한 missing-score boundary test 추가**

`ai.timefold.solver.core.api.solver.Solver`,
`ai.timefold.solver.core.api.solver.SolverFactory`, `io.mockk.every`,
`io.mockk.mockk`, `org.junit.jupiter.api.assertThrows`를 import한 뒤 다음 test를
추가한다. 주입한 mock factory를 사용하도록 기본 time limit로 `optimize`를 호출한다.

```kotlin
@Test
fun `optimize fails explicitly when solver returns no score`() {
    val (clinicId, _, _, _) = insertBaseData()
    val factory = mockk<SolverFactory<ScheduleSolution>>()
    val solver = mockk<Solver<ScheduleSolution>>()
    every { factory.buildSolver() } returns solver
    every { solver.solve(any()) } returns ScheduleSolution()

    val service = SolverService(solverFactory = factory)

    assertFailsWith<IllegalStateException> {
        service.optimize(clinicId, MONDAY..FRIDAY)
    }
}
```

- [x] **Step 2: service test 실행 및 새 assertion 실패 또는 기존 NPE 도달 확인**

실행:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest"
```

변경 전 예상 동작: mocked solve가 `result.score!!` 지점에 도달해 Kotlin null
assertion으로 실패한다. 또는 예상한 contextual `IllegalStateException` 계약이
아직 구현되지 않아 test가 실패한다.

- [x] **Step 3: service assertion을 contextual `checkNotNull`으로 교체**

`SolverService.kt`에 다음의 정확한 boundary pattern을 사용한다.

```kotlin
val originalMap = transaction {
    appointmentRepository.findByClinicAndDateRange(clinicId, dateRange)
        .associateBy { record ->
            checkNotNull(record.id) {
                "Appointment record is missing id: clinicId=${record.clinicId}"
            }
        }
}

val score = checkNotNull(result.score) {
    "Solver returned no score: clinicId=$clinicId, dateRange=$dateRange"
}
```

doctor schedule/absence를 로드할 때는 lambda마다 ID를 한 번 바인딩하고
검증한다.

```kotlin
val doctorSchedules = doctors.flatMap { doctor ->
    val doctorId = checkNotNull(doctor.id) {
        "Doctor record is missing id: clinicId=${doctor.clinicId}"
    }
    doctorRepository.findAllSchedules(doctorId)
}
val doctorAbsences = doctors.flatMap { doctor ->
    val doctorId = checkNotNull(doctor.id) {
        "Doctor record is missing id: clinicId=${doctor.clinicId}"
    }
    doctorRepository.findAbsencesByDateRange(doctorId, dateRange)
}
```

이 message에 patient name, phone, member ID 또는 다른 개인 정보를 포함하지
않는다. public `SolverResult` type은 변경하지 않는다.

- [x] **Step 4: service test 실행 및 명시적 error contract 검증**

같은 targeted service command를 실행한다. 예상 결과: 기존 database solver
test가 모두 통과하고 missing-score test가 clinic/date-range context와 함께
`IllegalStateException`을 받는다.

- [x] **Step 5: service boundary commit**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt
git commit -m $'Make solver lifecycle invariants explicit\n\nReport missing persisted IDs and solver scores with contextual failures.\n\nConstraint: Treat repository IDs and post-solve score as boundary invariants.\nRejected: Keep Kotlin not-null assertions | They obscure the failing lifecycle contract.\nConfidence: high\nScope-risk: narrow\nDirective: Do not put patient data in invariant error messages.\nTested: SolverServiceTest\nNot-tested: Full appointment-solver build.'
```

## 작업 5: module 검증 및 전체 diff 검토

**파일:**

- 검증: `appointment-solver/src/main/kotlin` 아래의 모든 production Kotlin file
- 검증: 변경된 모든 solver test와 승인된 design/plan document

- [x] **Step 1: 집중 solver test set 실행**

```bash
./gradlew :appointment-solver:test \
  --tests "io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanningAssignmentTest" \
  --tests "io.bluetape4k.clinic.appointment.solver.constraint.ConstraintVerifierTest" \
  --tests "io.bluetape4k.clinic.appointment.solver.converter.SolutionConverterTest" \
  --tests "io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest"
```

예상 결과: complete-result equivalence, partial-entity skip 동작, 명시적인
missing-score failure를 포함해 선택한 모든 test가 통과한다.

- [x] **Step 2: production source의 assertion과 금지된 의미 변경 audit**

실행:

```bash
rg -n '!!' appointment-solver/src/main/kotlin
rg -n 'allowsUnassigned|forEachIncludingUnassigned' appointment-solver/src/main/kotlin
git diff --check
```

예상 결과: 첫 두 search는 production match를 출력하지 않고
`git diff --check`는 아무것도 출력하지 않는다.

- [x] **Step 3: solver module build**

```bash
./gradlew :appointment-solver:build --no-build-cache
```

예상 결과: `BUILD SUCCESSFUL`. 관련 없는 shutdown-delay warning은 solver
verification 주장을 약화하지 말고 별도로 기록한다.

- [x] **Step 4: 독립 완료 review 수행**

다음과 diff를 대조해 review하고
`docs/superpowers/specs/2026-08-02-issue-211-solver-nullable-planning-boundaries-design.md`
다음을 확인한다.

- `HardConstraints`, `SolutionConverter`, `SolverService`의 production `!!`가
  모두 제거되었는가;
- indexed equality/overlap join을 broad filtering join으로 바꾼 join이 없는가;
- default value가 누락된 planning variable을 가리지 않는가;
- incomplete non-pinned result가 계속 제외되는가;
- full solution score, feasibility, count, converted record가 계속 동등한가;
- error message에 patient/member 개인 정보가 없는가;
- lifecycle failure가 caller-input `IllegalArgumentException`이 아니라
  `IllegalStateException`을 사용하는가;
- internal `withAssigned` helper가 partial-state contract를 문서화하는가;
- H2/H3/H4/H11 partial-state regression path를 다루는가;
- P0/P1 review finding이 남아 있지 않은가.

- [x] **Step 5: Lore protocol에 따라 검증된 구현 commit**

```bash
git add appointment-solver docs/superpowers/plans/2026-08-02-issue-211-solver-nullable-planning-boundaries-plan.md
git commit -m $'Make solver nullable planning boundaries safe\n\nPreserve partial planning semantics while removing incidental assertion failures.\n\nConstraint: Keep Timefold scoring and incomplete-result exclusion behavior unchanged.\nRejected: Model null as a final unassigned appointment | It changes the clinic domain contract.\nConfidence: high\nScope-risk: moderate\nDirective: Future constraints must use the assigned-value boundary instead of !!.\nTested: appointment-solver focused tests; appointment-solver build; git diff --check; production !! audit\nNot-tested: Full multi-module build unless separately requested.'
```

## 자체 review checklist

- Spec coverage: 작업 1~4가 helper, H1~H11 assertion 제거, converter skip
  semantics, service invariant, test, explicit non-goal을 다룬다. 작업 5는
  module build, static audit, diff review, P0/P1 완료 게이트를 다룬다.
- Placeholder scan: task step에 unresolved placeholder instruction이나 정의되지
  않은 future symbol이 없다. 모든 code change가 정확한 file, symbol, command,
  expected result를 지정한다.
- Type consistency: 작업 1의 `withAssigned`는 `T?`를 반환하고 작업 2~3은
  `== true` 또는 `mapNotNull`으로 소비한다. 작업 4는 `ScheduleSolution.score`를
  nullable로 유지하고 service boundary에서만 범위를 좁힌다.
- Behavior lock: `allowsUnassigned`, `forEachIncludingUnassigned`, constraint
  weight, `SolverResult`를 accidental scope growth로부터 명시적으로 보호한다.

## 실행 결과

- 집중 회귀 테스트: `AppointmentPlanningAssignmentTest`,
  `ConstraintVerifierTest`, `SolutionConverterTest`, `SolverServiceTest` 대상
  `BUILD SUCCESSFUL` (선택 test 40개, constraint suite 안에서 partial-state assertion 확장).
- 모듈 전체 검증: `./gradlew :appointment-solver:build --no-build-cache`;
  `67 passing`, Kover `koverVerify`, `BUILD SUCCESSFUL`.
- 정적 점검: production `!!` 0건, `allowsUnassigned` 및
  `forEachIncludingUnassigned` 0건, `git diff --check` 통과.
- 독립 완료 검토: code-reviewer와 architect가 exact HEAD를 각각 검토했다.
  code-reviewer는 코드 결함 P0/P1/P2/P3 0건을 확인했다. architect가 지적한
  lifecycle 예외 매핑(P1), helper API visibility/KDoc(P2), partial constraint test
  범위(P3)를 보정했다.
