# appointment-core

[English](README.md) | [한국어](README.ko.md)

Domain model, Exposed ORM tables, repositories, appointment state machine, and slot calculation services.
This is the leaf module that all other modules build on.

## Responsibilities

- **Does**: defines domain entities, database table schemas, repository CRUD operations, state-machine transition validation, and available-slot calculation.
- **Does not**: depend on Spring Context, expose HTTP APIs, send notifications, or publish events.

## Appointment Plan Foundation

`ProductCatalogProjection` stores one immutable, tenant/clinic-scoped product
version and its canonical payload hash. `AppointmentPlanFactory` expands that
snapshot into ordered `PlannedTreatment` occurrences and materialized dependency
edges without assigning dates or IDs. `AppointmentPlanRepository` persists and
loads the aggregate in a caller-owned Exposed transaction.

`AppointmentPlanQueryService` exposes only a sanitized `AppointmentPlanView`;
patient ciphertext, key IDs, and fingerprints remain inside the persistence
boundary. A plan is a purchased obligation, not a visit or resource hold.

## Scheduling Policy Foundation

Immutable tenant baselines and partial clinic overrides are stored as versioned
policy definitions. Strict payload decoding, validation, canonical hashing,
generation-fenced compilation, activation commands, and preview jobs live in
this module; HTTP and worker orchestration live in `appointment-api`.

See the [scheduling-policy domain model](../docs/requirements/domain-model.md#scheduling-policy-모델)
and [Scheduling Policy API contract](../docs/api/scheduling-policy.md).

## Booking Reliability Domain

`BookingReliabilityEvaluator` is a pure, deterministic evaluator for typed
`NO_SHOW` and `CANCELLED` events. It filters by patient responsibility,
deduplicates by event ID/source version, applies lookback and late-cancellation
thresholds from an immutable policy snapshot, and returns bounded reason codes,
trigger IDs, expiry, and a digest. `BookingEligibilityPort` exposes this as a
read contract; the API module owns rollout mode and authorization. The core
model never loads member names, phone numbers, or free-text notes.

Persistence uses additive V17 tables for events, immutable decisions,
append-only overrides, and keyset reevaluation jobs. Callers own the Exposed
`transaction {}` boundary.

See the [booking reliability policy](../docs/booking-reliability-policy.md),
[ERD](../docs/images/readme-diagrams/booking-reliability-erd-01-en.png), and
[class boundary](../docs/images/readme-diagrams/booking-reliability-class-01-en.png).

## Core Classes

### Domain Entities (Record)

| Class | Role |
|--------|------|
| `AppointmentRecord` | Appointment with clinicId, doctorId, treatmentTypeId, appointmentDate, startTime, endTime, and status. |
| `ClinicRecord` | Clinic settings such as slotDurationMinutes, maxConcurrentPatients, and openOnHolidays. |
| `DoctorRecord` | Doctor information such as clinicId, providerType, and maxConcurrentPatients. |
| `TreatmentTypeRecord` | Treatment type with defaultDurationMinutes, requiredProviderType, and requiresEquipment. |
| `EquipmentRecord` | Equipment with usageDurationMinutes and quantity. |
| `OperatingHoursRecord` | Operating hours with dayOfWeek, openTime, closeTime, and isActive. |
| `DoctorScheduleRecord` | Doctor working schedule with dayOfWeek, startTime, and endTime. |
| `DoctorAbsenceRecord` | Doctor absence with absenceDate, optional startTime, and optional endTime. |
| `ClinicClosureRecord` | Temporary clinic closure with closureDate, isFullDay, optional startTime, and optional endTime. |
| `HolidayRecord` | Holiday with holidayDate and recurring flag. |
| `EquipmentUnavailabilityRecord` | Equipment unavailability window with recurrence rule and exceptions. |

### State Machine

```kotlin
val machine = AppointmentStateMachine()
val newState = machine.transition(
    current = AppointmentState.REQUESTED,
    event = AppointmentEvent.Confirm,
)   // AppointmentState.CONFIRMED
```

Full transition list: [domain model document](../docs/requirements/domain-model.md#상태-전이도)

### Repositories

| Class | Main Methods |
|--------|-----------|
| `AppointmentRepository` | `findByDateRange()`, `findByStatus()`, `save()`, `updateStatus()` |
| `ClinicRepository` | `findById()`, `findAll()` |
| `DoctorRepository` | `findByClinic()`, `findByProviderType()` |
| `TreatmentTypeRepository` | `findAll()`, `findById()` |
| `HolidayRepository` | `isHoliday(date)`, `findByYear()` |
| `RescheduleCandidateRepository` | `findPendingByClinic()`, `save()` |
| `EquipmentUnavailabilityRepository` | `findByEquipment()`, `findOverlapping()`, `save()`, `delete()` |
| `ProductCatalogRepository` | Saves immutable catalog versions and detects same-version content conflicts. |
| `AppointmentPlanRepository` | Saves/loads one complete plan aggregate within exact tenant and clinic scope. |

> **Important**: every repository call must run inside a `transaction { }` block.

### Service Value Types (`model/service/`)

| Class | Role |
|--------|------|
| `SlotQuery` | Slot query parameters: clinicId, doctorId, treatmentTypeId, date. |
| `AvailableSlot` | Calculated available slot result: date, startTime, endTime, doctorId, remainingCapacity. |
| `TimeRange` | Time range value type plus top-level `subtractRanges` and `computeEffectiveRanges` functions. |

### Services

| Class | Role |
|--------|------|
| `SlotCalculationService` | Returns available slots for a doctor/date/treatment-type combination. |
| `ClosureRescheduleService` | Reassigns appointments affected by a temporary closure to the first available slot. |
| `ConcurrencyResolver` | Resolves concurrent appointment conflicts. |
| `ClinicTimezoneService` | Converts and combines clinic timezone data at API boundaries. |
| `EquipmentUnavailabilityService` | CRUD for equipment unavailability windows and recurrence expansion with `UnavailabilityExpander`. |

## Dependencies

- **Internal**: none. This is a leaf module.
- **External**: `exposed-core`, `exposed-jdbc`, `bluetape4k-coroutines`, Exposed ORM.

## Tests

```bash
./gradlew :appointment-core:test

# Specific test
./gradlew :appointment-core:test --tests "*.SlotCalculationServiceTest"
```

> Test DB setup: `@BeforeEach` with `SchemaUtils.createMissingTablesAndColumns(Table)` and `Table.deleteAll()`.
> Testcontainers: use the bluetape4k singleton pattern without `@Testcontainers`.

## Entity Relationship Overview

![Entity Relationship Overview diagram](../docs/images/readme-diagrams/appointment-core-erd-01-en.png)

Full ERD: [erd.md](../docs/requirements/erd.md)

## Appointment State Machine

![Appointment State Machine diagram](../docs/images/readme-diagrams/appointment-core-architecture-02-en.png)

Full transition list: [domain-model.md](../docs/requirements/domain-model.md#상태-전이도)

## Core Domain Flows

![Available slot query data flow](../docs/requirements/assets/data-flow-02-slot-query-en.png)

![Temporary closure reschedule data flow](../docs/requirements/assets/data-flow-03-closure-reschedule-en.png)

![Equipment unavailability data flow](../docs/requirements/assets/data-flow-04-equipment-unavailability-en.png)

## Timezone Design

### Storage Rules

| Column | Type | Reference |
|------|------|------|
| `appointment_date` | `LocalDate` | Clinic-local date. |
| `start_time` / `end_time` | `LocalTime` | Clinic-local time. |
| `created_at` / `updated_at` | `Instant` (UTC) | System audit timestamp. |

Appointment times are stored as clinic-local date and time without UTC conversion.

Reasons:

- Appointments are local events. Converting "Seoul clinic 23:00" to UTC can shift the date.
- Date-based queries such as `WHERE appointment_date = '2026-04-01'` stay correct regardless of timezone.
- Slot calculation and business-hour comparison remain simple inside the same timezone.

### Multi-Country SaaS Support

Each clinic stores a ZoneId in `Clinics.timezone`, such as `"Asia/Seoul"` or `"America/New_York"`.
`Clinics.locale` is used only for display format and language. It is independent from timezone.

For example, a Korean expatriate clinic can use `locale = "ko-KR"` with `timezone = "America/Los_Angeles"`.

### API Flow

```text
Frontend  ->  LocalDate + LocalTime (clinic local)
               stored without conversion
DB        ->  LocalDate + LocalTime (clinic local)
               response includes Clinics.timezone / locale
Frontend  ->  can reconstruct ZonedDateTime from appointmentDate + startTime + timezone
```

### ClinicTimezoneService

`ClinicTimezoneService` is used at API boundaries when timezone information must be combined:

```kotlin
// Include timezone/locale in a response with one DB lookup.
val (timezone, locale) = timezoneService.getTimezoneAndLocale(clinicId)

// Convert to ZonedDateTime for cross-clinic comparison.
val zoned: ZonedDateTime = timezoneService.toClinicTime(clinicId, date, time)
```

## Design Documents

- [Full Domain Model](../docs/requirements/domain-model.md)
