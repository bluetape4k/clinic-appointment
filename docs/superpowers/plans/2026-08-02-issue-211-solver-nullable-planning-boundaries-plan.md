# Solver Nullable Planning Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove incidental nullable assertion failures from `appointment-solver` while preserving Timefold partial-solution scoring and the existing rule that incomplete non-pinned appointments are omitted from converted results.

**Architecture:** Add one inline domain extension, `AppointmentPlanning.withAssigned`, that supplies all four derived assignment values only when the planning entity is complete. Hard constraints use it for multi-value predicates and nullable property/method-reference keys for indexed joins; `SolutionConverter` uses it with `mapNotNull`; `SolverService` uses explicit `requireNotNull` only for repository and solver lifecycle invariants.

**Tech Stack:** Kotlin 2.3, Timefold Solver 2.2, JUnit 5, MockK, Gradle, `appointment-solver` module.

---

## Scope map and invariants

| File | Responsibility | Planned change |
|---|---|---|
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignment.kt` | Domain boundary for a complete planning assignment | Create inline `withAssigned` extension; no mutable state or default values |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignmentTest.kt` | Helper contract | Create RED/GREEN tests for complete and partial entities |
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt` | H1~H11 hard constraints | Remove every `!!`; keep indexed equality/overlap joins and existing filters/weights |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/ConstraintVerifierTest.kt` | Constraint regression behavior | Add partial-entity cases and retain existing full-entity penalty assertions |
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt` | Planning-to-record conversion | Replace assertion-based extraction with `withAssigned` + `mapNotNull` |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverterTest.kt` | Extraction behavior | Extend coverage for every missing assignment component and complete conversion |
| `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt` | Solver and persistence boundary | Replace IDs/score assertions with contextual `requireNotNull` |
| `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt` | Solver lifecycle boundary | Add a mocked solver test for a missing score |

The implementation must not change `AppointmentPlanning` variable annotations,
introduce `allowsUnassigned=true`, add `forEachIncludingUnassigned()`, alter
constraint weights, or expose incomplete-result diagnostics. Those are explicitly
outside this issue.

## Task 1: Add and lock the complete-assignment helper

**Files:**

- Create: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignmentTest.kt`
- Create: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignment.kt`

- [ ] **Step 1: Write the failing helper contract test.**

Create the test with a local snapshot type so the test proves all four values,
including the derived end time, are passed without introducing a production
assignment object:

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

- [ ] **Step 2: Run the helper test and confirm RED.**

Run:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanningAssignmentTest"
```

Expected result: compilation fails because `withAssigned` does not yet exist.

- [ ] **Step 3: Implement the minimal inline extension.**

Create the production file with this contract; keep the block inline and do not
cache a snapshot because Timefold mutates planning variables during solving:

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

The helper returns `null` for incomplete planning state and never substitutes
`0L`, `LocalDate.MIN`, `LocalTime.MIN`, or any other default.

- [ ] **Step 4: Run the helper test and confirm GREEN.**

Run the same targeted Gradle command. Expected result: 2 tests pass.

- [ ] **Step 5: Commit the isolated domain boundary.**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignment.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/domain/AppointmentPlanningAssignmentTest.kt
git commit -m $'Make complete planning assignment explicit\n\nAdd a zero-state helper for nullable Timefold planning variables.\n\nConstraint: Preserve mutable partial-solution input semantics.\nRejected: Store an assignment snapshot on the planning entity | Timefold mutates variables during solving.\nConfidence: high\nScope-risk: narrow\nDirective: Keep incomplete planning state out of final record conversion.\nTested: AppointmentPlanningAssignmentTest\nNot-tested: Full appointment-solver build.'
```

## Task 2: Remove assertions from hard constraints without changing joins

**Files:**

- Modify: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/ConstraintVerifierTest.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt`

- [ ] **Step 1: Add a partial planning regression test before editing constraints.**

Extend `ConstraintVerifierTest` with a partial appointment and facts that exercise
the date, doctor, and time predicate shapes already covered by the file. The
existing full-entity tests remain unchanged; the new assertions prove that an
unassigned entity contributes no hard penalty and does not throw:

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

- [ ] **Step 2: Run the constraint regression test before the refactor.**

Run:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.constraint.ConstraintVerifierTest"
```

Expected result: the existing suite passes. This is a behavior-locking test;
Timefold may already exclude the partial entity before invoking the assertion,
so the RED signal for the production change is the helper compilation test and
the source-level `!!` audit.

- [ ] **Step 3: Replace all hard-constraint `!!` uses with safe keys and helper predicates.**

Apply the following exact transformation rules in `HardConstraints.kt`:

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

Use nullable direct properties or method references for these equality keys:

- H1/H4a: `appointmentDate?.dayOfWeek` to the weekday fact.
- H2/H3: `doctorId` and `appointmentDate` to doctor/date facts.
- H5/H6: `appointmentDate` to closure/holiday dates.
- H7/H8: `doctorId`, `equipmentId`, and `appointmentDate` keys; retain the existing
  `overlapping` and `lessThan` joiners.
- H9/H10: `AppointmentPlanning::doctorId` to `DoctorFact::id`.
- H11: `AppointmentPlanning::equipmentId` and `appointmentDate` keys.

Use `withAssigned { ... } == true` for every time-range predicate in H1, H2,
H3, H4a, H4b, H5, and H11. Retain explicit `equipmentId != null` and
`requiresEquipment` filters where they express business meaning. Do not replace
an indexed equality join with a broad `Joiners.filtering` join merely to avoid
Kotlin nullability; that would alter the constraint stream performance shape.

- [ ] **Step 4: Compile and run constraint tests after the refactor.**

Run:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.constraint.ConstraintVerifierTest"
```

Expected result: all existing full-entity penalty tests and the new partial-entity
test pass, with no `NullPointerException`.

- [ ] **Step 5: Commit the hard-constraint boundary.**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/HardConstraints.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/constraint/ConstraintVerifierTest.kt
git commit -m $'Make hard constraints tolerate partial planning state\n\nUse safe assignment locals while retaining indexed constraint joins.\n\nConstraint: Preserve H1-H11 scoring and default unassigned filtering.\nRejected: forEachIncludingUnassigned | Null is not a final clinic appointment state.\nConfidence: high\nScope-risk: moderate\nDirective: Do not reintroduce not-null assertions after constraint filters.\nTested: ConstraintVerifierTest\nNot-tested: Full appointment-solver build.'
```

## Task 3: Make result extraction explicitly skip incomplete entities

**Files:**

- Modify: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverterTest.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt`

- [ ] **Step 1: Add the missing-start extraction regression.**

Add a test next to the existing missing-doctor and missing-date/time tests. Keep
doctor and date present so this case specifically proves the helper guards the
time/end-time boundary:

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

- [ ] **Step 2: Run converter tests before changing production code.**

Run:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.converter.SolutionConverterTest"
```

Expected result: existing extraction tests pass and the new regression test passes
under the current filter; the helper compilation test remains the RED guard for
the new production API.

- [ ] **Step 3: Replace `extractResults` with helper-backed `mapNotNull`.**

Replace only the extraction pipeline; keep all record fields and optional original
metadata unchanged:

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

- [ ] **Step 4: Run the converter test suite and verify result equivalence.**

Run the same targeted converter command. Expected result: complete non-pinned
appointments still retain their original metadata, pinned appointments remain
excluded, and all incomplete variants return zero records.

- [ ] **Step 5: Commit the converter boundary.**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverterTest.kt
git commit -m $'Preserve incomplete appointment result semantics safely\n\nConvert only complete non-pinned assignments without assertion-based null handling.\n\nConstraint: Keep incomplete planning entities out of AppointmentRecord results.\nRejected: Return a partial AppointmentRecord | Downstream callers require complete scheduling fields.\nConfidence: high\nScope-risk: narrow\nDirective: Preserve optional original metadata lookup exactly.\nTested: SolutionConverterTest\nNot-tested: Full appointment-solver build.'
```

## Task 4: Make solver and repository invariants explicit

**Files:**

- Modify: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt`

- [ ] **Step 1: Add a missing-score boundary test with a mocked solver.**

Add the imports `ai.timefold.solver.core.api.solver.Solver`,
`ai.timefold.solver.core.api.solver.SolverFactory`, `io.mockk.every`,
`io.mockk.mockk`, and `org.junit.jupiter.api.assertThrows`, then add this test.
Call `optimize` with the default time limit so the injected mock factory is used:

```kotlin
@Test
fun `optimize fails explicitly when solver returns no score`() {
    val (clinicId, _, _, _) = insertBaseData()
    val factory = mockk<SolverFactory<ScheduleSolution>>()
    val solver = mockk<Solver<ScheduleSolution>>()
    every { factory.buildSolver() } returns solver
    every { solver.solve(any()) } returns ScheduleSolution()

    val service = SolverService(solverFactory = factory)

    assertThrows<IllegalArgumentException> {
        service.optimize(clinicId, MONDAY..FRIDAY)
    }
}
```

- [ ] **Step 2: Run the service test and confirm the new assertion fails or reaches the old NPE.**

Run:

```bash
./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest"
```

Expected pre-change behavior: the mocked solve reaches the `result.score!!`
site and fails with a Kotlin null assertion (or the test fails because the
expected contextual `IllegalArgumentException` contract is not yet implemented).

- [ ] **Step 3: Replace service assertions with contextual `requireNotNull`.**

Use these exact boundary patterns in `SolverService.kt`:

```kotlin
val originalMap = transaction {
    appointmentRepository.findByClinicAndDateRange(clinicId, dateRange)
        .associateBy { record ->
            requireNotNull(record.id) {
                "Appointment record is missing id: clinicId=${record.clinicId}"
            }
        }
}

val score = requireNotNull(result.score) {
    "Solver returned no score: clinicId=$clinicId, dateRange=$dateRange"
}
```

For doctor schedule/absence loading, bind and validate the ID once per lambda:

```kotlin
val doctorSchedules = doctors.flatMap { doctor ->
    val doctorId = requireNotNull(doctor.id) {
        "Doctor record is missing id: clinicId=${doctor.clinicId}"
    }
    doctorRepository.findAllSchedules(doctorId)
}
val doctorAbsences = doctors.flatMap { doctor ->
    val doctorId = requireNotNull(doctor.id) {
        "Doctor record is missing id: clinicId=${doctor.clinicId}"
    }
    doctorRepository.findAbsencesByDateRange(doctorId, dateRange)
}
```

Do not include patient name, phone, member ID, or other personal data in these
messages. Do not change the public `SolverResult` type.

- [ ] **Step 4: Run the service tests and verify the explicit error contract.**

Run the same targeted service command. Expected result: all existing database
solver tests pass and the missing-score test receives `IllegalArgumentException`
with the clinic/date-range context.

- [ ] **Step 5: Commit the service boundary.**

```bash
git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt
git commit -m $'Make solver lifecycle invariants explicit\n\nReport missing persisted IDs and solver scores with contextual failures.\n\nConstraint: Treat repository IDs and post-solve score as boundary invariants.\nRejected: Keep Kotlin not-null assertions | They obscure the failing lifecycle contract.\nConfidence: high\nScope-risk: narrow\nDirective: Do not put patient data in invariant error messages.\nTested: SolverServiceTest\nNot-tested: Full appointment-solver build.'
```

## Task 5: Run module verification and review the complete diff

**Files:**

- Verify: all production Kotlin files under `appointment-solver/src/main/kotlin`
- Verify: all changed solver tests and the approved design/plan documents

- [ ] **Step 1: Run the focused solver test set.**

```bash
./gradlew :appointment-solver:test \
  --tests "io.bluetape4k.clinic.appointment.solver.domain.AppointmentPlanningAssignmentTest" \
  --tests "io.bluetape4k.clinic.appointment.solver.constraint.ConstraintVerifierTest" \
  --tests "io.bluetape4k.clinic.appointment.solver.converter.SolutionConverterTest" \
  --tests "io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest"
```

Expected result: all selected tests pass, including complete-result equivalence,
partial-entity skip behavior, and explicit missing-score failure.

- [ ] **Step 2: Audit the production source for assertions and forbidden semantic changes.**

Run:

```bash
rg -n '!!' appointment-solver/src/main/kotlin
rg -n 'allowsUnassigned|forEachIncludingUnassigned' appointment-solver/src/main/kotlin
git diff --check
```

Expected result: the first two searches print no production matches and
`git diff --check` is silent.

- [ ] **Step 3: Build the solver module.**

```bash
./gradlew :appointment-solver:build --no-build-cache
```

Expected result: `BUILD SUCCESSFUL`; record any unrelated shutdown-delay warning
separately instead of weakening the solver verification claim.

- [ ] **Step 4: Perform the independent completion review.**

Review the diff against
`docs/superpowers/specs/2026-08-02-issue-211-solver-nullable-planning-boundaries-design.md`
and confirm:

- every production `!!` in `HardConstraints`, `SolutionConverter`, and
  `SolverService` is gone;
- no join changed from an indexed equality/overlap join to a broad filtering join;
- no default value masks a missing planning variable;
- incomplete non-pinned results remain excluded;
- full solution score, feasibility, counts, and converted records remain equivalent;
- error messages contain no patient/member personal data;
- no P0/P1 review finding remains.

- [ ] **Step 5: Commit the verified implementation with the Lore protocol.**

```bash
git add appointment-solver docs/superpowers/plans/2026-08-02-issue-211-solver-nullable-planning-boundaries-plan.md
git commit -m $'Make solver nullable planning boundaries safe\n\nPreserve partial planning semantics while removing incidental assertion failures.\n\nConstraint: Keep Timefold scoring and incomplete-result exclusion behavior unchanged.\nRejected: Model null as a final unassigned appointment | It changes the clinic domain contract.\nConfidence: high\nScope-risk: moderate\nDirective: Future constraints must use the assigned-value boundary instead of !!.\nTested: appointment-solver focused tests; appointment-solver build; git diff --check; production !! audit\nNot-tested: Full multi-module build unless separately requested.'
```

## Self-review checklist

- Spec coverage: Tasks 1–4 cover the helper, H1–H11 assertion removal, converter
  skip semantics, service invariants, tests, and explicit non-goals. Task 5 covers
  module build, static audit, diff review, and the P0/P1 completion gate.
- Placeholder scan: no unresolved placeholder instruction or undefined future
  symbol appears in the task steps; every code change names an exact file,
  symbol, command, and expected result.
- Type consistency: `withAssigned` returns `T?` in Task 1, and Tasks 2–3 consume
  it with `== true` or `mapNotNull`; Task 4 leaves `ScheduleSolution.score`
  nullable and narrows it only at the service boundary.
- Behavior lock: `allowsUnassigned`, `forEachIncludingUnassigned`, constraint
  weights, and `SolverResult` are explicitly protected from accidental scope growth.
