# appointment-api

[English](README.md) | [한국어](README.ko.md)

Spring Boot 4 tenant-scoped REST API server with JWT authentication, Flyway migrations, Swagger UI, and Gatling load tests.

## Responsibilities

- **Does**: exposes HTTP APIs, handles authentication/authorization, runs DB migrations, and publishes domain events.
- **Does not**: send notifications directly. Notifications are delegated through events. It may call Solver for scheduling workflows.

## API Endpoints

| Group | Path | Description |
|------|------|------|
| Appointments | `GET /api/{tenantCode}/appointments` | List appointments by period. |
| Appointments | `POST /api/{tenantCode}/appointments` | Create an appointment. |
| Appointments | `PATCH /api/{tenantCode}/appointments/{id}/status` | Change status, such as Confirm, CheckIn, Complete. |
| Appointments | `DELETE /api/{tenantCode}/appointments/{id}` | Cancel an appointment. |
| Slots | `GET /api/{tenantCode}/clinics/{clinicId}/slots` | Query available slots by doctor, date, and treatment type. |
| Reschedule | `POST /api/{tenantCode}/appointments/{id}/reschedule/closure` | Reschedule appointments affected by a temporary clinic closure. |
| Reschedule | `GET /api/{tenantCode}/appointments/{id}/reschedule/candidates` | List reschedule candidates. |
| Reschedule stream | `GET /api/{tenantCode}/reschedule/batch/stream` | Stream batch reschedule progress with SSE. |
| Equipment unavailability | `GET /api/{tenantCode}/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities` | List equipment unavailability windows. |
| Equipment unavailability | `POST /api/{tenantCode}/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities` | Register an unavailability window. |
| Equipment unavailability | `PUT /api/{tenantCode}/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}` | Update an unavailability window. |
| Equipment unavailability | `DELETE /api/{tenantCode}/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}` | Delete an unavailability window. |
| Clinics | `GET /api/{tenantCode}/clinics`, `/{id}`, `/{id}/operating-hours`, `/{id}/break-times` | Query clinic information. |
| Doctors | `GET /api/{tenantCode}/clinics/{id}/doctors`, `/doctors/{id}`, `/{id}/schedules`, `/{id}/absences` | Query doctor information. |
| Treatment types | `GET /api/{tenantCode}/clinics/{id}/treatment-types`, `/treatment-types/{id}` | Query treatment types. |
| Equipment | `GET /api/{tenantCode}/clinics/{id}/equipments`, `/equipments/{id}` | Query equipment. |
| Dashboard stats | `GET /api/{tenantCode}/admin/stats/{appointments,doctors,cancellations}` | Query admin dashboard aggregates. |

Use `tenant-default` for the local seed tenant. JWTs must include the requested tenant in the `allowedTenants` claim.

**Swagger UI**: `http://localhost:8080/swagger-ui.html` after the server starts.

## Appointment Creation Flow

```mermaid
sequenceDiagram
    participant FE as Frontend
    participant API as AppointmentController
    participant SEC as JwtAuthFilter
    participant SLOT as SlotCalculationService
    participant DB as PostgreSQL
    participant EVT as EventBus

    FE->>API: POST /api/{tenantCode}/appointments (Bearer token)
    API->>SEC: Validate JWT and allowedTenants
    SEC-->>API: SchedulingUserPrincipal
    API->>SLOT: Check slot availability
    SLOT->>DB: Query hours, schedules, conflicts
    DB-->>SLOT: Data
    SLOT-->>API: isAvailable: true
    API->>DB: INSERT appointments
    API->>EVT: publishEvent(Created)
    API-->>FE: AppointmentResponse (201)
```

Full data flow: [data-flow.md](../docs/requirements/data-flow.md)

## Authentication

JWT Bearer Token:

- Header: `Authorization: Bearer <token>`
- Tenant path: `/api/{tenantCode}/...`
- Tenant claim: `allowedTenants` must contain the URL `tenantCode`
- Properties: `JwtSecurityProperties` (`scheduling.security.jwt.*`)
- Filter: `JwtAuthenticationFilter` -> `SchedulingUserPrincipal`

## DB Migration

Flyway migration scripts live under `src/main/resources/db/migration/V*.sql`.

> **Important**: `scheduling_*` table names are fixed in Flyway scripts. Do not rename them.

## Core Classes

| Class | Role |
|--------|------|
| `AppointmentController` | Appointment CRUD and status changes. |
| `SlotController` | Available slot lookup. |
| `RescheduleController` | Temporary clinic closure rescheduling. |
| `EquipmentUnavailabilityController` | Equipment unavailability CRUD and conflict detection. |
| `ClinicController` | Clinic lookup, including operating hours and break times. |
| `DoctorController` | Doctor lookup, including schedules and absences. |
| `TreatmentTypeController` | Treatment type lookup. |
| `EquipmentController` | Equipment lookup. |
| `SecurityConfig` | JWT-based Spring Security configuration. |
| `GlobalExceptionHandler` | Global exception handling that returns `ApiResponse`. |
| `TestDataSeeder` | Automatic development/test seed data insertion. |

## Dependencies

- **Internal**: `appointment-core`, `appointment-event`, `appointment-solver`
- **External**: Spring Boot 4 Web/Security, `jjwt`, Flyway, springdoc-openapi, `exposed-jdbc`

## Run

```bash
# Start the server (requires PostgreSQL + Redis)
./gradlew :appointment-api:bootRun

# Build
./gradlew :appointment-api:build

# Gatling load tests
./gradlew :appointment-api:gatlingRun
```

## Timezone Model

API responses such as `AppointmentResponse` always include `timezone` and `locale`.

```json
{
  "appointmentDate": "2026-04-01",
  "startTime": "09:00:00",
  "endTime": "09:30:00",
  "timezone": "Asia/Seoul",
  "locale": "ko-KR"
}
```

- `appointmentDate`, `startTime`, and `endTime` are based on the clinic's local time.
- The frontend can reconstruct `ZonedDateTime` using the `timezone` field.
- The server does not convert appointment dates/times to UTC, which avoids date-boundary bugs.
- `locale` is for date/time display formatting and is independent from timezone.

Detailed design: [appointment-core timezone design](../appointment-core/README.md#timezone-design)

## Tests

```bash
# H2 in-memory, default
./gradlew :appointment-api:test

# PostgreSQL Testcontainer
./gradlew :appointment-api:test -Dspring.profiles.active=test,test-postgresql

# MySQL8 Testcontainer
./gradlew :appointment-api:test -Dspring.profiles.active=test,test-mysql
```

### Test Structure

| Class | Role |
|--------|------|
| `AbstractApiIntegrationTest` | Abstract class based on `@SpringBootTest(RANDOM_PORT)` and `@DynamicPropertySource`. |
| `Containers` | PostgreSQL / MySQL8 Testcontainer singleton. |

- DataSource is injected dynamically by Spring profile with `@DynamicPropertySource`.
- Controller tests use `RestClient`, not MockMvc.
- CI verifies H2, PostgreSQL, and MySQL8 in parallel.
