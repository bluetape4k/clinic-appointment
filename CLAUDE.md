# clinic-appointment Kotlin Dev Agent

Personal clinic patient appointment management system — Kotlin 2.3 + Java 25 + Spring Boot 4 + Exposed ORM.

## Role

- Apply `bluetape4k-patterns` skill on all Kotlin code. No exceptions.
- Communicate in Korean. Keep code identifiers and technical terms in English.

## Principles

- **Think Before Coding** — ask when uncertain, never guess
- **Simplicity First** — implement only what was asked
- **Surgical Changes** — every changed line must trace back to a request
- **Goal-Driven** — "write a failing test then fix it" not "fix the bug"

## Build

```bash
# Per-module only
./gradlew :<module>:build
./gradlew :<module>:test
./gradlew :<module>:test --tests "fully.qualified.ClassName.methodName"
```

## Modules

| Module                           | Role                                                         |
|----------------------------------|--------------------------------------------------------------|
| `:appointment-core`              | Exposed ORM domain models, repositories, state machine       |
| `:appointment-event`             | Spring event-based domain event publishing                   |
| `:appointment-solver`            | Timefold Solver AI scheduling optimizer                      |
| `:appointment-notification`      | Notification scheduler (Resilience4j, Redis Leader Election) |
| `:appointment-api`               | Spring Boot MVC API (JWT, Flyway, Swagger, Gatling)          |
| `:frontend:appointment-frontend` | Angular frontend                                             |

## Key Files

| Purpose | Path |
|---------|------|
| Dependency versions | `buildSrc/src/main/kotlin/Libs.kt` |
| Module registration | `settings.gradle.kts` |

## Module Gotchas

- **Transactions required** — all Exposed ops must run inside `transaction {}`.
- **DB init in tests** — `@BeforeEach`: `SchemaUtils.createMissingTablesAndColumns(Table)` + `Table.deleteAll()`.
- **Flyway SQL** — `scheduling_*` table names are DB schema names; do not rename them.
- **Testcontainers** — No `@Testcontainers` annotation. Use bluetape4k singleton pattern.
