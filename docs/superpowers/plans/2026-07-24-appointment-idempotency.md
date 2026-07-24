# Appointment Creation Idempotency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent duplicate appointment creation while allowing safe tenant-scoped retries through an optional `Idempotency-Key` header.

**Architecture:** Store request keys separately from appointments. A service result distinguishes newly committed data from replay, and a database unique constraint serializes concurrent calls. The controller preserves tenant authorization and maps those results to 201/200.

**Tech Stack:** Kotlin 2.3, Spring Boot 4 MVC, Exposed JDBC, Flyway (H2/PostgreSQL/MySQL), JUnit 5, MockK.

---

## File structure

| Path | Responsibility |
|---|---|
| `appointment-core/.../AppointmentIdempotencies.kt` | Exposed table with tenant/clinic/key uniqueness and expiry index |
| `appointment-core/.../AppointmentIdempotencyRecord.kt` | Persisted immutable idempotency record |
| `appointment-core/.../AppointmentIdempotencyRepository.kt` | Scoped active-key lookup, save, expiry deletion |
| `appointment-api/.../AppointmentIdempotencyProperties.kt` | Positive 24-hour default TTL |
| `appointment-api/.../AppointmentService.kt` | Transactional create-or-replay and event-once behavior |
| `appointment-api/.../AppointmentController.kt` | Optional header and HTTP status contract |
| `appointment-api/.../GlobalExceptionHandler.kt` | Mismatch-to-409 mapping |
| `appointment-api/src/main/resources/db/migration/*/V7__add_appointment_idempotency.sql` | Dialect schema |

### Task 1: Persist the scoped key

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/AppointmentIdempotencies.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentIdempotencyRecord.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentIdempotencyRepository.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentIdempotencyRepositoryTest.kt`

- [ ] **Step 1: Write RED repository tests**

In `transaction {}` create tenant, clinic, and appointment fixtures. Assert active lookup, expiry deletion, cross-tenant/clinic key isolation, and rejection of duplicate `(tenant, clinic, key)`.

- [ ] **Step 2: Run RED**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepositoryTest"`

Expected: missing types fail compilation.

- [ ] **Step 3: Implement the smallest Exposed boundary**

Use `LongIdTable("scheduling_appointment_idempotency")`, FK columns for `TenantGroups`, `Clinics`, `Appointments`, a 255-character key, 64-character SHA-256 fingerprint, timestamps, `uniqueIndex(tenantGroupId, clinicId, idempotencyKey)`, and an `expiresAt` index. Keep Repository calls in caller-owned `transaction {}` and introduce no production `!!`.

- [ ] **Step 4: Run GREEN**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepositoryTest"`

Expected: PASS.

### Task 2: Make every schema source agree

**Files:**
- Create: `appointment-api/src/main/resources/db/migration/h2/V7__add_appointment_idempotency.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V7__add_appointment_idempotency.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V7__add_appointment_idempotency.sql`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`

- [ ] **Step 1: Write RED migration assertions**

Require V7 table, unique scope constraint, and expiry index in every Flyway test.

- [ ] **Step 2: Run H2 RED**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"`

Expected: table is absent.

- [ ] **Step 3: Add V7 SQL and test-schema registration**

Follow V6 dialect naming and add `AppointmentIdempotencies` to `SchemaInitConfig`.

- [ ] **Step 4: Run migration GREEN sequentially**

Run H2, PostgreSQL, and MySQL migration tests one at a time. Expected: PASS on all three.

### Task 3: Implement atomic create-or-replay

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentIdempotencyProperties.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentServiceIdempotencyTest.kt`

- [ ] **Step 1: Write RED service tests**

Use fixed UTC `Clock` and recording `ApplicationEventPublisher`. Prove initial creation, same-fingerprint replay, mismatch, expiry, non-positive TTL rejection, and two concurrent callers. Assert exactly one appointment, idempotency row, and created event for a replay pair.

- [ ] **Step 2: Run RED**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.service.AppointmentServiceIdempotencyTest"`

Expected: missing result/configuration/protocol types fail.

- [ ] **Step 3: Implement the minimum protocol**

Add immutable positive 24-hour `Duration` configuration. Hash typed request fields in fixed order with explicit null markers; never hash raw JSON. In one transaction delete the matching expired key, lookup scope, save appointment plus key together, and return `Created` or `Replayed`. Catch only verified duplicate-key races, then reread and compare fingerprint. Publish `AppointmentDomainEvent.Created` only for `Created` after transaction success.

- [ ] **Step 4: Run GREEN**

Use `MultithreadingTester` when available; otherwise use synchronized real-H2 callers and record why. Run the service test above; expected PASS.

### Task 4: Publish the HTTP contract

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentControllerTest.kt`

- [ ] **Step 1: Write RED controller tests**

Two equal POSTs carrying `Idempotency-Key: retry-key-1` must produce 201 then 200 with the same `$.data.id`. Changed body with the key must produce 409 and `success=false`; blank key must produce 400; the existing no-header test remains 201. Seed another tenant and prove that its caller cannot replay this tenant's appointment with the same key.

- [ ] **Step 2: Run RED**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentControllerTest"`

Expected: current replay behavior fails the required statuses.

- [ ] **Step 3: Implement header, OpenAPI, and conflict mapping**

Verify tenant and clinic ownership before any replay lookup; retain scheduling-resource verification on creation. Add `@RequestHeader(value = "Idempotency-Key", required = false)`, OpenAPI 200/409 responses, 201 for `Created`, 200 for `Replayed`, and dedicated mismatch exception handling.

- [ ] **Step 4: Run GREEN**

Run the controller test above. Expected: PASS.

### Task 5: Converge and commit

**Files:**
- Modify: `docs/requirements/api.md` only when current API documentation includes appointment creation examples.
- Modify: `docs/superpowers/specs/2026-07-24-appointment-idempotency-design.md` only for review corrections.

- [ ] **Step 1: Check public documentation impact**

Run: `rg -n -i 'POST /api/.*/appointments|Create a new appointment|appointment creation' README* docs appointment-api/src/main/kotlin`

Expected: update a relevant example or record OpenAPI-only scope evidence.

- [ ] **Step 2: Run final verification sequentially**

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

- [ ] **Step 3: Final review and Lore commit**

Map every design acceptance criterion to code/tests. Check production `!!`, broad exception swallowing, Exposed transaction boundaries, secret/PII/key logging, and OpenAPI drift. Resolve P0/P1, stage only #174 changes, and make a Lore-format commit. Do not push, create a PR, or merge without fresh authority.
