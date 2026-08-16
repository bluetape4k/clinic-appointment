# Solver planning fact version fence 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #334의 solver 결과에 planning-fact snapshot version fence를 추가해 solve와 apply 사이의 fact 추가·수정·삭제 및 PostgreSQL 직렬화 충돌을 오래된 결과 거부로 수렴시킨다.

**Architecture:** `SolverService`가 기존 snapshot transaction 내부에서 모든 solver 입력 fact와 날짜 범위의 canonical SHA-256을 계산하고 `SolverResult`에 `planningFactVersion`과 날짜 범위를 보존한다. apply는 하나의 `SERIALIZABLE` transaction에서 동일 범위 snapshot을 다시 읽고 hash를 비교한 뒤 기존 appointment `FOR UPDATE`/version CAS를 수행한다. canonical encoder는 별도 파일의 명시적 length-framed writer로 두어 collection 순서와 record `toString()`에 의존하지 않는다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed v1 JDBC transactions, Timefold Solver, JUnit 5/Kluent/MockK, H2, `bluetape4k-testcontainers`의 `PostgreSQLServer.Launcher.postgres`.

---

## 승인된 범위와 실행 규칙

- 기준 worktree: `/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/fix/issue-334-solver-fact-version-fence`
- branch/base: `fix/issue-334-solver-fact-version-fence` / `develop` at `90e50da4b49e35d667911418cc9578ab538898e3`
- 설계 source: `docs/superpowers/specs/2026-08-16-issue-334-solver-fact-version-fence-design.md`
- 모든 production/test 문서는 한국어로 작성한다. 코드·API·명령·SQL state는 원문을 보존한다.
- `@Testcontainers`와 raw `GenericContainer`는 사용하지 않는다. PostgreSQL은 singleton launcher만 사용한다.
- 모든 Exposed query와 mutation은 호출 transaction 안에 둔다. H2 fixture는 `SchemaUtils.createMissingTablesAndColumns`와 reverse `deleteAll`을 유지한다.
- 각 구현 task는 RED 확인 → 최소 GREEN 구현 → targeted test → Lore commit 순서로 수행한다.
- 현재 root의 `.superpowers/`, `.workflow-inputs/`와 다른 worktree는 읽기·삭제·정리하지 않는다.

## 파일 매핑

### production

- Create: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/PlanningFactVersionHasher.kt`
  - scope, date range, `ScheduleSolution`의 모든 problem fact/value range를 stable order와 length framing으로 SHA-256한다.
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverResult.kt`
  - nullable legacy-safe `dateRange`와 blank legacy-safe `planningFactVersion`을 추가하고 serialization contract를 갱신한다.
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt`
  - snapshot hash 생성, advisory 비교, `SERIALIZABLE` apply transaction, snapshot loader 분리를 구현한다.
- Modify: `appointment-solver/build.gradle.kts`
  - PostgreSQL singleton 검증에 필요한 기존 version catalog alias의 test dependency만 추가한다.

### tests

- Create: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/PlanningFactVersionHasherTest.kt`
  - deterministic ordering, null/empty framing, each fact field의 digest 변화와 64자리 hex 계약을 검증한다.
- Modify: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt`
  - result metadata, legacy-safe rejection, H2 planning fact add/update/delete fence, 기존 CAS/rollback/pinned 회귀를 검증한다.
- Create: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServicePostgresConcurrencyTest.kt`
  - `PostgreSQLServer.Launcher.postgres`에서 fact 변경 전후와 appointment lock 경합을 재현한다.

### verification artifacts

- Create: `docs/superpowers/risk/2026-08-16-issue-334-solver-fact-version-fence-risk.md`
  - hash 비용, SERIALIZABLE conflict, H2/PostgreSQL 차이, fallback/중단 조건을 기록한다.
- Create: `docs/review/2026-08-16-issue-334-solver-fact-version-fence-step-6r-code-review.md`
  - 독립 review 관점별 P0/P1/P2/P3 판정과 exact test evidence를 기록한다.
- Create: `docs/lessons/2026-08-16-issue-334-solver-fact-version-fence.md`
  - direct writer를 포괄하는 canonical fence 결정과 구현 중 surprise, 검증, 재사용 규칙을 기록한다.

## Task 1: risk prediction과 canonical encoder RED

**Files:**
- Create: `docs/superpowers/risk/2026-08-16-issue-334-solver-fact-version-fence-risk.md`
- Create: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/PlanningFactVersionHasherTest.kt`
- Create: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/PlanningFactVersionHasher.kt`

- [x] **Step 1: 동시성·성능·안정성 위험을 문서화한다.**

  risk 문서에 다음 네 가지를 고정한다.

  1. fact 수에 선형인 hash CPU 비용과 apply 시 snapshot 재조회 비용: snapshot에 포함된 record 수, elapsed time, reject count를 로그/검증 증거로 남긴다.
  2. `SERIALIZABLE`에서 PostgreSQL SQLSTATE `40001`/`40P01`이 날 수 있음: 해당 상태만 stale `false`로 수렴하고 그 밖의 SQL 오류는 전파한다.
  3. H2의 isolation이 PostgreSQL과 다름: H2는 fact별 회귀, PostgreSQL singleton은 isolation/race 증거로 역할을 분리한다.
  4. canonical field 누락 또는 순서 drift: field 목록을 이 계획과 test fixture에 고정하고 `toString()`/unordered iteration을 금지한다.

  각 위험에 trigger, 관찰 가능한 증거, 완화책, 중단 조건을 표로 기록한다.

- [x] **Step 2: deterministic hash RED 테스트를 먼저 작성한다.**

  `PlanningFactVersionHasherTest`에 다음 테스트를 추가한다.

```kotlin
@Test
fun `같은 snapshot은 항상 같은 64자리 SHA-256을 만든다`() {
    val first = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution())
    val second = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution())

    first shouldBeEqualTo second
    first.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
}

@Test
fun `fact collection 입력 순서가 달라도 digest는 같다`() {
    val ordered = solution(doctors = listOf(doctor(1), doctor(2)))
    val reversed = solution(doctors = listOf(doctor(2), doctor(1)))

    PlanningFactVersionHasher.hash(SCOPE, RANGE, ordered) shouldBeEqualTo
        PlanningFactVersionHasher.hash(SCOPE, RANGE, reversed)
}

@Test
fun `nullable 값과 빈 문자열은 서로 다른 framing을 사용한다`() {
    val withNull = solution(doctorProviderType = null)
    val withEmpty = solution(doctorProviderType = "")

    PlanningFactVersionHasher.hash(SCOPE, RANGE, withNull) shouldNotBeEqualTo
        PlanningFactVersionHasher.hash(SCOPE, RANGE, withEmpty)
}

@Test
fun `각 problem fact field 변경은 digest를 변경한다`() {
    val baseline = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution())
    val changed = PlanningFactVersionHasher.hash(SCOPE, RANGE, solution(clinicSlotMinutes = 60))

    changed shouldNotBeEqualTo baseline
}
```

  `solution`, `doctor`, `SCOPE`, `RANGE` fixture는 `ScheduleSolution`의 실제 생성자와 `ClinicFact`/`DoctorFact`를 사용해 test file 안에 정의한다. fixture는 appointment와 score를 제외하고 clinic, doctor, treatment, equipment, operating hours, schedules, absences, breaks, default breaks, closures, holidays, treatment-equipment mapping을 각각 최소 한 건 포함한다.

- [x] **Step 3: RED를 확인한다.**

  Run:

  ```bash
  ./gradlew :appointment-solver:test --tests 'io.bluetape4k.clinic.appointment.solver.service.PlanningFactVersionHasherTest'
  ```

  Expected: `PlanningFactVersionHasher`가 아직 없어 compile failure가 발생한다. 이 failure가 새 테스트/심볼 부재인지 확인하고 기존 테스트 failure로 대체하지 않는다.

- [x] **Step 4: length-framed canonical encoder를 구현한다.**

  `PlanningFactVersionHasher.kt`는 다음 계약을 그대로 구현한다.

```kotlin
internal object PlanningFactVersionHasher {
    fun hash(
        scope: TenantClinicScope,
        dateRange: ClosedRange<LocalDate>,
        solution: ScheduleSolution,
    ): String
}
```

  내부 `CanonicalWriter`는 field마다 type marker와 UTF-8 byte length를 기록하고, `null`은 별도 marker로 기록한다. 지원 타입은 `String`, `Long`, `Int`, `Boolean`, `LocalDate`, `LocalTime`, `DayOfWeek`, `Enum`, `null`로 제한하며 그 외 타입은 `IllegalArgumentException`으로 즉시 실패시킨다. `MessageDigest.getInstance("SHA-256")` 결과는 `lowercase` 64자리 hex로 반환한다.

  record 순서는 `scope`, `date-range`, `clinic`, `doctors`, `treatments`, `equipments`, `operating-hours`, `doctor-schedules`, `doctor-absences`, `break-times`, `default-break-times`, `closures`, `holidays`, `treatment-equipments`, `equipment-unavailabilities`, `doctor-ids`, `solution-date-range`, `time-slots`로 고정한다. 각 collection은 stable key로 정렬하고 다음 field를 빠짐없이 쓴다.

  - clinic: `id`, `slotDurationMinutes`, `maxConcurrentPatients`, `openOnHolidays`
  - doctor: `id`, `clinicId`, `providerType`, `maxConcurrentPatients`
  - treatment: `id`, `defaultDurationMinutes`, `requiredProviderType`, `requiresEquipment`, `maxConcurrentPatients`
  - equipment: `id`, `usageDurationMinutes`, `quantity`
  - operating hour: `id`, `clinicId`, `dayOfWeek`, `openTime`, `closeTime`, `isActive`
  - doctor schedule: `id`, `doctorId`, `dayOfWeek`, `startTime`, `endTime`
  - doctor absence: `id`, `doctorId`, `absenceDate`, `startTime`, `endTime`, `reason`
  - break/default break: record DTO의 모든 생성자 field
  - closure: `id`, `clinicId`, `closureDate`, `reason`, `isFullDay`, `startTime`, `endTime`
  - holiday: `id`, `tenantGroupId`, `holidayDate`, `name`, `recurring`
  - treatment-equipment: `id`, `treatmentTypeId`, `equipmentId`
  - equipment unavailability fact: `equipmentId`, `date`, `startTime`, `endTime`

  `ScheduleSolution.equipmentUnavailabilities`가 현재 loader에서 비어 있어도 encoder contract에는 포함한다. 향후 loader가 해당 fact를 채울 때 같은 fence가 자동으로 적용된다. appointments와 mutable `score`는 기존 appointment source version/CAS와 solver 결과이므로 planning-fact digest에서 제외한다.

- [x] **Step 5: encoder targeted GREEN을 실행한다.**

  Run the exact command from Step 3. Expected: hasher tests PASS. 이어서 `./gradlew :appointment-solver:compileKotlin`으로 production compile을 확인한다.

- [x] **Step 6: Task 1을 Lore commit한다.**

  ```bash
  git add docs/superpowers/risk/2026-08-16-issue-334-solver-fact-version-fence-risk.md appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/PlanningFactVersionHasher.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/PlanningFactVersionHasherTest.kt
  git commit -m "planning fact canonical hash의 위험과 결정성을 고정한다" -m "Constraint: direct writer 변경을 schema migration 없이 감지하고 JVM collection 순서에 의존하지 않아야 한다
Rejected: record toString hash와 unordered iteration | field framing과 stable sort를 보장하지 못한다
Confidence: high
Scope-risk: moderate
Directive: 새 planning fact를 ScheduleSolution에 추가하면 encoder field와 deterministic test를 같은 변경에 포함한다
Tested: PlanningFactVersionHasherTest, :appointment-solver:compileKotlin
Not-tested: SolverService apply 경로와 PostgreSQL race는 다음 task에서 검증한다"
  ```

## Task 2: SolverResult와 snapshot metadata 연결 RED/GREEN

**Files:**
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverResult.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt`
- Modify: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt`

- [x] **Step 1: result metadata RED를 작성한다.**

  기존 feasible test의 result assertion 뒤에 다음을 추가한다.

```kotlin
result.dateRange?.start shouldBeEqualTo MONDAY
result.dateRange?.endInclusive shouldBeEqualTo FRIDAY
result.planningFactVersion.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
```

  별도 test `legacy result metadata가 advisory와 apply에서 안전하게 거부된다`에서 `result.copy(dateRange = null, planningFactVersion = "")`를 `isSourceVersionCurrentAdvisory`와 `applyOptimizedAssignments`에 전달하고 둘 다 `false`인지 고정한다.

- [x] **Step 2: metadata RED를 확인한다.**

  ```bash
  ./gradlew :appointment-solver:test --tests 'io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest.*'
  ```

  Expected: 새 result property가 없어 compile failure가 발생한다.

- [x] **Step 3: SolverResult contract를 추가한다.**

  `scope` 다음에 아래 두 field를 둔다.

```kotlin
val dateRange: ClosedRange<LocalDate>? = null,
val planningFactVersion: String = "",
```

  `sourceVersions`는 유지한다. nullable/blank 기본값은 기존 in-memory fixture와 이전 serialized result를 안전하게 읽되, apply/advisory가 fence 없는 result를 성공시키지 않도록 하기 위한 것이다. `Serializable` `serialVersionUID`는 새 결과 contract에 맞춰 `2L`로 올리고 KDoc에 legacy result reject 의미를 기록한다.

- [x] **Step 4: snapshot loader를 current-transaction 함수로 분리한다.**

  `SolverService`에 다음 구조를 적용한다.

```kotlin
private fun loadSnapshot(scope: TenantClinicScope, dateRange: ClosedRange<LocalDate>): SolverSnapshot =
    transaction { loadSnapshotInCurrentTransaction(scope, dateRange) }

private fun loadSnapshotInCurrentTransaction(
    scope: TenantClinicScope,
    dateRange: ClosedRange<LocalDate>,
): SolverSnapshot {
    // 현재 loadSnapshot의 repository read와 SolutionConverter.buildSolution을 그대로 유지한다.
    val planningFactVersion = PlanningFactVersionHasher.hash(scope, dateRange, solution)
    return SolverSnapshot(solution, originalAppointments, planningFactVersion)
}
```

  `SolverSnapshot`에 `planningFactVersion: String`을 추가하고, `optimize`의 `SolverResult` 생성 시 snapshot의 hash와 원래 dateRange를 전달한다. solve가 사용하는 appointments는 digest에서 제외하지만 기존 `originalAppointments` map과 source version 생성은 변경하지 않는다.

- [x] **Step 5: metadata GREEN과 기존 solver 회귀를 실행한다.**

  ```bash
  ./gradlew :appointment-solver:test --tests 'io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest.*'
  ```

  Expected: metadata test와 기존 feasible/reschedule/empty/no-score test가 PASS한다.

- [x] **Step 6: Task 2를 Lore commit한다.**

  ```bash
  git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverResult.kt appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt
  git commit -m "solver 결과에 planning fact snapshot metadata를 보존한다" -m "Constraint: 기존 appointment sourceVersions와 optimize 호출 의미를 유지하면서 fence 없는 legacy result는 거부해야 한다
Rejected: appointment version map을 planning fact version으로 재사용 | fact table 변경을 표현하지 못한다
Confidence: high
Scope-risk: moderate
Directive: snapshot loader를 우회해 독립적인 fact read transaction을 추가하지 않는다
Tested: SolverServiceTest targeted, :appointment-solver:compileKotlin
Not-tested: SERIALIZABLE apply와 PostgreSQL concurrency는 다음 task에서 검증한다"
  ```

## Task 3: advisory/apply planning-fact fence와 H2 fact별 회귀

**Files:**
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt`
- Modify: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt`

- [x] **Step 1: H2 fact mutation RED 회귀를 추가한다.**

  `SolverServiceTest`에 공통 helper를 추가한다.

```kotlin
private fun assertApplyRejectsAfterPlanningFactChange(
    change: (BaseData) -> Unit,
) {
    val base = insertBaseData()
    insertAppointment(base)
    val result = solverService.optimize(scope(base.clinicId), MONDAY..FRIDAY, Duration.ofSeconds(5))

    change(base)

    solverService.isSourceVersionCurrentAdvisory(result).shouldBeFalse()
    solverService.applyOptimizedAssignments(result).shouldBeFalse()
    assertAppointmentVersionAndStatusUnchanged(base.clinicId)
}
```

  아래 test method를 각각 작성하고 mutation은 Exposed `update`, `insert`, `deleteWhere`로 직접 수행한다. 모든 mutation은 `transaction {}` 안에서 수행한다.

  - `clinic 변경은 stale result를 거부한다`: `Clinics.update({ Clinics.id eq clinicId }) { it[Clinics.maxConcurrentPatients] = 2 }`
  - `doctor 변경은 stale result를 거부한다`: `Doctors.update({ Doctors.id eq doctorId1 }) { it[Doctors.providerType] = ProviderType.CONSULTANT }`
  - `treatment 변경은 stale result를 거부한다`: `TreatmentTypes.update({ TreatmentTypes.id eq treatmentTypeId }) { it[TreatmentTypes.defaultDurationMinutes] = 60 }`
  - `equipment 추가는 stale result를 거부한다`: `Equipments.insert { it[Equipments.clinicId] = clinicId; it[Equipments.name] = "MRI"; it[Equipments.usageDurationMinutes] = 30 }`
  - `operating hour 삭제는 stale result를 거부한다`: `OperatingHoursTable.deleteWhere { OperatingHoursTable.clinicId eq clinicId }`
  - `doctor schedule 변경은 stale result를 거부한다`: `DoctorSchedules.update({ DoctorSchedules.doctorId eq doctorId1 }) { it[DoctorSchedules.startTime] = LocalTime.of(10, 0) }`
  - `doctor absence 추가는 stale result를 거부한다`: `DoctorAbsences.insert { it[DoctorAbsences.doctorId] = doctorId1; it[DoctorAbsences.absenceDate] = MONDAY; it[DoctorAbsences.reason] = "회의" }`
  - `break 변경은 stale result를 거부한다`: `BreakTimes.insert { it[clinicId] = clinicId; it[dayOfWeek] = DayOfWeek.MONDAY; it[startTime] = LocalTime.of(12, 0); it[endTime] = LocalTime.of(13, 0) }`
  - `default break 변경은 stale result를 거부한다`: `ClinicDefaultBreakTimes.insert { it[clinicId] = clinicId; it[name] = "점심"; it[startTime] = LocalTime.of(12, 0); it[endTime] = LocalTime.of(13, 0) }`
  - `closure 추가는 stale result를 거부한다`: `ClinicClosures.insert { it[ClinicClosures.clinicId] = clinicId; it[ClinicClosures.closureDate] = MONDAY; it[ClinicClosures.reason] = "점검" }`
  - `holiday 추가는 stale result를 거부한다`: `Holidays.insert { it[Holidays.tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups); it[Holidays.holidayDate] = MONDAY; it[Holidays.name] = "임시 휴일" }`
  - `treatment equipment 연결 추가는 stale result를 거부한다`: `val equipmentId = Equipments.insertAndGetId { it[Equipments.clinicId] = clinicId; it[Equipments.name] = "MRI"; it[Equipments.usageDurationMinutes] = 30 }.value; TreatmentEquipments.insert { it[TreatmentEquipments.treatmentTypeId] = treatmentTypeId; it[TreatmentEquipments.equipmentId] = equipmentId }`

  시간 값은 `LocalTime.of(12, 0)`/`LocalTime.of(13, 0)`로 작성하고, fixture의 운영시간과 겹쳐도 appointment 결과의 assignment 자체보다 fence 거부를 검증한다. H2 cleanup 순서는 기존 reverse `deleteAll`을 유지하고 신규 equipment/association row를 먼저 지운다.

- [x] **Step 2: RED를 확인한다.**

  ```bash
  ./gradlew :appointment-solver:test --tests 'io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest.*'
  ```

  Expected: helper가 호출하는 `planningFactVersion` 비교가 없어서 변경 후 advisory/apply가 `true`가 되거나, 아직 구현 전이면 새 test compile/behavior failure가 난다.

- [x] **Step 3: advisory에 planning fact 비교를 추가한다.**

  `isSourceVersionCurrentAdvisory`는 결과의 nullable dateRange/blank hash를 먼저 안전하게 거부하고, 하나의 transaction에서 `loadSnapshotInCurrentTransaction`을 호출해 현재 hash와 result hash를 비교한다. 이어서 기존 `sourceVersions.all`을 확인한다. 현재 snapshot read가 실패하면 예외를 삼키지 않고 기존 transaction exception contract로 전파한다.

- [x] **Step 4: apply를 SERIALIZABLE fence로 교체한다.**

  `applyOptimizedAssignments`는 다음 구조를 사용한다.

```kotlin
return try {
    transaction(transactionIsolation = Connection.TRANSACTION_SERIALIZABLE) {
        val resultDateRange = result.dateRange ?: throw StaleSolverResultException
        if (result.planningFactVersion.isBlank()) throw StaleSolverResultException

        val current = loadSnapshotInCurrentTransaction(result.scope, resultDateRange)
        if (current.planningFactVersion != result.planningFactVersion) {
            throw StaleSolverResultException
        }
        if (!appointmentRepository.lockLegacySourceVersions(result.scope, result.sourceVersions)) {
            throw StaleSolverResultException
        }
        result.appointments.forEach { appointment ->
            // 기존 id/expectedVersion validation과 updateLegacyAssignment를 그대로 사용한다.
        }
        true
    }
} catch (_: StaleSolverResultException) {
    false
} catch (failure: Exception) {
    if (failure.isSerializationConflict()) false else throw failure
}
```

  `isSerializationConflict`는 cause chain과 `SQLException.nextException`을 순회해 SQLSTATE `40001` 또는 `40P01`만 인식한다. `StaleSolverResultException`은 기존 rollback을 위해 transaction 안에서 throw하고, unexpected SQL/encoding failure는 재throw한다. 기존 duplicate assignment rollback, concurrent appointment writer, pinned behavior test는 수정하지 않고 그대로 통과시킨다.

- [x] **Step 5: H2 GREEN과 rollback 보존을 실행한다.**

  ```bash
  ./gradlew :appointment-solver:test --tests 'io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest.*'
  ```

  Expected: 12개 planning fact mutation test, legacy-safe rejection, 기존 CAS/concurrent writer/duplicate rollback/pinned 관련 test가 PASS한다.

- [x] **Step 6: Task 3을 Lore commit한다.**

  ```bash
  git add appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt
  git commit -m "solver apply가 planning fact 변경을 원자적으로 거부한다" -m "Constraint: appointment CAS와 planning fact snapshot fence가 동일한 적용 transaction에 있어야 한다
Rejected: advisory hash만 확인하고 apply transaction을 유지하는 방식 | 확인 직후 fact writer가 결과를 무효화할 수 있다
Confidence: high
Scope-risk: broad
Directive: SERIALIZABLE conflict를 성공으로 재시도하거나 부분 assignment를 남기지 않는다
Tested: H2 fact add/update/delete, CAS, rollback, pinned, concurrent writer targeted tests
Not-tested: PostgreSQL singleton race는 다음 task에서 검증한다"
  ```

## Task 4: PostgreSQL singleton concurrency proof

**Files:**
- Modify: `appointment-solver/build.gradle.kts`
- Create: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServicePostgresConcurrencyTest.kt`

- [x] **Step 1: 기존 alias만 test dependency로 추가한다.**

  `appointment-solver/build.gradle.kts`에 다음 세 줄을 추가한다. version catalog에 이미 정의된 alias를 재사용하며 새 dependency를 만들지 않는다.

```kotlin
testImplementation(libs.bluetape4k.testcontainers)
testImplementation(libs.postgresql.driver)
testImplementation(libs.testcontainers.postgresql)
```

- [x] **Step 2: PostgreSQL test fixture를 작성한다.**

  `SolverServicePostgresConcurrencyTest`는 `PostgreSQLServer.Launcher.postgres`를 lazy singleton으로 가져오고, `@BeforeAll`에서 `Database.connect(postgres.jdbcUrl, driver = "org.postgresql.Driver", user = ..., password = ...)` 후 H2와 같은 scheduling table set을 `SchemaUtils.createMissingTablesAndColumns`로 생성한다. `@BeforeEach`는 tenant/group와 모든 child table을 reverse `deleteAll`한다. H2 `SolverServiceTest`와 이 class에 JUnit `@ResourceLock("exposed-default-database")`와 `@Execution(ExecutionMode.SAME_THREAD)`를 적용하고, `@AfterAll`에서 H2 URL을 다시 `Database.connect`해 Exposed default database를 복원한다. raw container annotation이나 per-test container start는 작성하지 않는다.

- [x] **Step 3: fact 변경과 실제 Postgres hash mismatch를 검증한다.**

  H2 helper와 같은 fixture를 Postgres에 넣고 다음 test를 작성한다.

```kotlin
@Test
fun `PostgreSQL에서 solve와 apply 사이 clinic 변경은 결과를 거부한다`() {
    val base = insertBaseData()
    insertAppointment(base)
    val result = service.optimize(scope(base.clinicId), MONDAY..FRIDAY, Duration.ofSeconds(2))

    transaction { Clinics.update({ Clinics.id eq base.clinicId }) { it[maxConcurrentPatients] = 2 } }

    service.applyOptimizedAssignments(result).shouldBeFalse()
    assertAppointmentVersionAndStatusUnchanged(base.clinicId)
}
```

- [x] **Step 4: appointment lock 경합과 CAS stale 수렴을 검증한다.**

  `CountDownLatch` 두 개와 two-thread executor를 사용한다. writer transaction이
  `lockLegacySourceVersions(scope, result.sourceVersions)`로 appointment row lock을 잡고
  appointment status/version을 먼저 소비한 뒤 `writerReady`를 count down한다. apply thread를
  시작해 snapshot hash를 읽고 appointment lock에서 대기시킨 뒤, main thread가 별도 transaction에서
  clinic max concurrency도 수정·commit한다. writer를 `releaseWriter`로 종료하면 apply는
  appointment CAS stale로 `false`를 반환해야 하며, 결과 assignment는 남지 않고 writer의
  status/version만 보존되어야 한다. timeout은 5초로 제한하고 finally에서 executor shutdown/await를
  수행한다. PostgreSQL fact 변경 자체는 별도 hash mismatch test로 검증하며, 이 경합 test는
  실제 row lock·CAS rollback 경계를 검증한다.

- [x] **Step 5: Docker/Colima 전제와 test를 순차 검증한다.**

  ```bash
  colima status
  docker context show
  docker info
  ./gradlew :appointment-solver:test --tests 'io.bluetape4k.clinic.appointment.solver.service.SolverServicePostgresConcurrencyTest' --no-build-cache
  ```

  관리된 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 상속한다. Colima가 healthy인데 bind-mount 오류가 나면 VM을 재시작하거나 skip하지 않고 원인을 진단한다. Expected: PostgreSQL 두 test PASS; 실패 시 오류와 환경을 risk/lesson에 기록하고 verification을 완료로 표시하지 않는다.

- [x] **Step 6: Task 4를 Lore commit한다.**

  ```bash
  git add appointment-solver/build.gradle.kts appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServicePostgresConcurrencyTest.kt
  git commit -m "PostgreSQL singleton으로 solver fact fence 경합을 검증한다" -m "Constraint: production-like DB semantics는 bluetape4k singleton launcher로만 재현한다
Rejected: H2만으로 SERIALIZABLE 증거를 주장하는 방식 | PostgreSQL lock/MVCC 의미를 증명하지 못한다
Confidence: medium
Scope-risk: broad
Directive: Testcontainers bind-mount 오류를 pass나 skip으로 포장하지 않고 fresh diagnostic으로 보고한다
Tested: PostgreSQL hash mismatch and appointment lock race tests
Not-tested: 전체 repository suite와 remote CI는 delivery 단계에서 검증한다"
  ```

  Result: Task 4 핵심 변경은 `54f420f0`으로 커밋했고, race test 안정화와
  advisory null-safety 후속 수정은 `e29271f4`, `2c821ed0`으로 각각 수렴했다.

## Task 5: module verification, review, lesson, checklist 수렴

**Files:**
- Create: `docs/review/2026-08-16-issue-334-solver-fact-version-fence-step-6r-code-review.md`
- Create: `docs/lessons/2026-08-16-issue-334-solver-fact-version-fence.md`
- Modify: `docs/superpowers/checklists/2026-08-16-issue-334-workflow.md`

- [x] **Step 1: 변경된 Kotlin 경계와 static safety를 검사한다.**

  ```bash
  ./gradlew :appointment-solver:compileKotlin :appointment-solver:compileTestKotlin
  rg -n "@Testcontainers|GenericContainer" appointment-solver/src/main appointment-solver/src/test
  rg -n "미완료|추후|보류" appointment-solver/src/main appointment-solver/src/test
  git diff --check
  ```

  Result: compile/build 경로 성공, 금지 annotation/container와 코드 표식 없음,
  `git diff --check` 통과. `!!` 신규 production 사용은 추가하지 않았다.

- [x] **Step 2: solver module 전체를 순차 실행한다.**

  ```bash
  ./gradlew :appointment-solver:test --no-build-cache --no-daemon
  ./gradlew :appointment-solver:build --no-build-cache --no-daemon
  ```

  Result: 10 suites / 98 tests, skipped 0, failures 0, errors 0과
  `BUILD SUCCESSFUL`을 확인했다. 첫 daemon 종료 후 `--no-daemon` 재실행 결과를
  최종 증거로 사용했다.

- [x] **Step 3: 독립 final review 문서를 작성한다.**

  review 문서는 문제 재현, digest field ledger, transaction boundary, serialization conflict, rollback/CAS/pinned, Testcontainers policy, API/serialization compatibility를 별도 관점으로 읽고 P0/P1/P2/P3 개수를 기록한다. P0/P1이 0이 아니면 pre-PR을 중지한다. review에는 exact commit SHA, targeted test 명령과 결과, known gap을 포함한다.

- [x] **Step 4: Korean lesson을 작성한다.**

  lesson에는 context, appointment-only fence의 한계, canonical hash 선택 이유, implementation surprise, H2와 PostgreSQL evidence 차이, 재사용할 `ScheduleSolution` field ledger 규칙, future writer가 지켜야 할 directive를 포함한다. 운영 증거가 아니라 Testcontainers 기반 DB consistency simulation임을 명시한다.

- [x] **Step 5: checklist와 receipt를 fresh evidence로 갱신한다.**

  `KT-01..KT-07`, `A-05..A-08`, `CL-09..CL-10`, `SPW-05`를 실제
  command/path/result로 갱신했고, `CL-08`은 CI/merge-ready 시점까지 보류한다.
  receipt에는 implementation/verification component check와 evidence를 순서대로
  기록하고, unchecked ID를 임의로 체크하지 않는다.

- [x] **Step 6: Task 5를 Lore commit한다.**

  ```bash
  git add docs/review/2026-08-16-issue-334-solver-fact-version-fence-step-6r-code-review.md docs/lessons/2026-08-16-issue-334-solver-fact-version-fence.md docs/superpowers/checklists/2026-08-16-issue-334-workflow.md
  git commit -m "stale solver 결과 적용 방지의 검증 근거를 남긴다" -m "Constraint: module test와 production-like DB 증거를 서로 대체하지 않고 분리해 보고해야 한다
Rejected: H2 green만으로 production consistency를 완료로 판정하는 방식 | isolation과 container runtime 위험을 가린다
Confidence: high
Scope-risk: narrow
Directive: planning fact 목록이 바뀌면 hasher ledger·회귀·lesson을 함께 갱신한다
Tested: solver compile/test/build, diff-check, static scan, final review
Not-tested: remote CI와 merge는 fresh PR authority 뒤에 수행한다"
  ```

## Task 6: PR/CI/merge-ready handoff

- [x] **Step 1: exact head와 PR authority를 재확인한다.**

  ```bash
  git status --short --branch
  git log -1 --format='%H %s'
  gh repo view bluetape4k/clinic-appointment --json defaultBranchRef
  gh pr list --repo bluetape4k/clinic-appointment --head fix/issue-334-solver-fact-version-fence --state all
  ```

  target repository는 `bluetape4k/clinic-appointment`, base는 `develop`, head는 현재 semantic branch다. root dirty file과 다른 worktree를 PR diff에 포함하지 않는다.

  Result: repository default branch는 `develop`, matching PR은 없고 현재 exact head는
  `51688f86460e1863cb72fa9ad91d74fad32a359a`다. feature worktree만 이 branch를
  가리키며 root의 별도 worktree 변경은 PR 범위에 포함하지 않는다.

- [ ] **Step 2: 한국어 PR을 생성하고 metadata parity를 확인한다.**

  PR body는 issue #334 URL, 문제/선택한 canonical hash, 변경 파일, H2/PostgreSQL test evidence, `## DoD Status` 표와 known gap을 포함한다. `gh pr create --repo bluetape4k/clinic-appointment --base develop --head fix/issue-334-solver-fact-version-fence` 후 live body/title/assignee/labels/milestone와 head SHA를 다시 읽는다. CI 전에는 merge를 요청하지 않는다.

- [ ] **Step 3: exact-head CI와 review thread를 수렴한다.**

  `gh pr checks <number> --watch`와 `gh pr view <number> --json headRefOid,statusCheckRollup,reviews,comments,body`로 exact head를 확인한다. 실패하면 logs를 읽고 같은 branch에서 수정·push·재검증한다. CI가 성공해도 unresolved review/P1이 있으면 merge-ready가 아니다.

- [ ] **Step 4: merge-ready report 후 새 merge 승인을 받는다.**

  report에는 PR number/URL, exact head SHA, CI checks, review/thread, `Required checks: X/Y; N/A: N; Blocked: 0`, known gaps를 포함한다. 사용자가 해당 현재 head에 대해 새로 승인하기 전에는 merge하지 않는다.

- [ ] **Step 5: 승인 후 rebase merge, local sync, worktree 정리를 수행한다.**

  ```bash
  gh pr merge <number> --repo bluetape4k/clinic-appointment --rebase --delete-branch=false
  git fetch origin develop
  git -C /Users/debop/work/bluetape4k/clinic-appointment switch develop
  git -C /Users/debop/work/bluetape4k/clinic-appointment pull --ff-only origin develop
  git -C /Users/debop/work/bluetape4k/clinic-appointment worktree remove --force .worktrees/fix/issue-334-solver-fact-version-fence
  ```

  merge commit/PR state가 `MERGED`이고 `develop`이 `origin/develop`과 같으며, merged head가 develop ancestry에 포함되는지 확인한 뒤 worktree만 삭제한다. remote branch는 사용자의 삭제 지시가 없으면 보존한다. root의 `.superpowers/`와 `.workflow-inputs/`는 건드리지 않는다.

## 계획 자체 검토

- [x] Spec coverage: 문제/선택지/계약은 Task 1–3, H2와 PostgreSQL acceptance는 Task 3–4, performance/stability와 Korean artifacts는 Task 1/5, PR/CI/merge는 Task 6에 연결했다.
- [x] Placeholder scan: 계획 저장 후 unresolved-marker scan이 결과를 내지 않았다.
- [x] Type consistency: `PlanningFactVersionHasher.hash(scope, dateRange, solution)`, `SolverSnapshot.planningFactVersion`, `SolverResult.dateRange/planningFactVersion`, `loadSnapshotInCurrentTransaction` 이름을 모든 task에서 동일하게 사용한다.
- [x] Artifact read-back: `git diff --check`와 계획 전체 read-back을 실행했다.

## 완료 조건

구현 완료는 H2 fact별 회귀와 PostgreSQL singleton race가 통과하고, solver module build/static/review/lesson/receipt가 fresh evidence로 수렴한 경우에만 주장한다. PR/CI 완료는 exact head metadata와 remote checks를 다시 읽은 뒤에만 보고한다. merge는 별도의 현재 head 승인 이후 사용자 지정 rebase 전략으로 수행한다.
