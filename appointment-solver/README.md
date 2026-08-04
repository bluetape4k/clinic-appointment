# appointment-solver

[English](README.md) | [한국어](README.ko.md)

Timefold Solver based AI appointment scheduler.
It optimizes bulk appointment placement across the global schedule while satisfying 12 hard constraints and 6 soft constraints.

## Responsibilities

- **Does**: assigns planning variables such as doctor, date, and start time; satisfies all hard constraints; minimizes soft-constraint penalties.
- **Does not**: provide real-time single-slot lookup, which belongs to `SlotCalculationService`; write results directly to the database, because `SolverService` returns results to its caller.

## Constraint Summary

Hard constraints (12):

- business hours
- doctor schedule
- doctor absence
- configured break time
- default break time
- temporary clinic closure
- holiday
- concurrent patient capacity
- equipment availability
- treatment-type and doctor matching
- doctor clinic membership
- equipment unavailability windows

Soft constraints (6):

- doctor load balancing, weight 100
- schedule gap minimization, weight 10
- original doctor preference
- early slot preference
- equipment utilization preference
- requested date preference

Full details: [solver.md](../docs/requirements/solver.md)

## Core Classes

| Class | Role |
|--------|------|
| `AppointmentPlanning` | `@PlanningEntity`; doctorId, appointmentDate, and startTime are planning variables. Pinned statuses are fixed. |
| `ScheduleSolution` | `@PlanningSolution`; contains AppointmentPlanning entries and problem facts. |
| `SolverService` | Entry point; loads data from the database, runs SolverConfig, and returns the result. |
| `SolverConfig` | Timefold SolverFactory configuration, including termination and move filters. |
| `SolutionConverter` | Converts between DB records and the planning domain. |
| `AppointmentConstraintProvider` | Registers all constraints, H1-H12 and S1-S6. |
| `EquipmentUnavailabilityFact` | Problem fact for equipment unavailability windows used by H11. |

## Solver Data Flow

![Solver data flow diagram](../docs/images/readme-diagrams/appointment-solver-architecture-01-en.png)

![Solver requirements data flow](../docs/requirements/assets/data-flow-06-solver-data-en.png)

![Closure reschedule solver scenario](../docs/requirements/assets/user-scenarios-03-closure-reschedule-solver-en.png)

Full flow: [data-flow.md](../docs/requirements/data-flow.md#6-solver-데이터-흐름)

## Pinned Appointments

`@PlanningPin` prevents Solver from moving appointments in the following states:

- **Pinned**: `CONFIRMED`, `CHECKED_IN`, `IN_PROGRESS`, `COMPLETED`
- **Movable**: `REQUESTED`, `PENDING_RESCHEDULE`

## Usage Example

```kotlin
val result: SolverResult = solverService.optimize(
    scope = TenantClinicScope(tenantGroupId = 1L, clinicId = 23L),
    dateRange = LocalDate.now()..LocalDate.now().plusDays(7),
)
// result.assignments: Map<Long, Assignment>
// appointmentId -> (doctorId, date, startTime)
```

## Dependencies

- **Internal**: `appointment-core`
- **External**: `ai.timefold.solver:timefold-solver-core`, `exposed-jdbc`

## Tests

```bash
./gradlew :appointment-solver:test
```

## Benchmarks

```bash
./gradlew :appointment-solver:test --tests "*.SolverBenchmarkTest"
```

HTML reports are generated under `build/reports/solver-benchmark/`.

Details: [solver-benchmark-report.md](../docs/requirements/solver.md#벤치마크)

## Design Documents

- [Full Solver Design](../docs/requirements/solver.md)

## Tenant-scoped optimization

`optimize` and `optimizeReschedule` require the same verified
`TenantClinicScope`. The snapshot, fact queries, source-version map, and apply
freshness check stay inside that scope; no thread-local tenant context is used.
The solver result is read-only until `verifySourceVersions` succeeds immediately
before applying changes.
