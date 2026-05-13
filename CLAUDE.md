# CLAUDE.md - clinic-appointment

Clinic appointment management example app built with Kotlin 2.3, Java 25,
Spring Boot 4, and Exposed ORM.

- Use the `bluetape4k-patterns` skill for all Kotlin implementation and review work.

## Build

```bash
# Prefer module-scoped validation
./gradlew :<module>:build
./gradlew :<module>:test
./gradlew :<module>:test --tests "fully.qualified.ClassName.methodName"
```

## Modules

| Module | Role |
|--------|------|
| `:appointment-core` | Exposed ORM domain models, repositories, state machine |
| `:appointment-event` | Spring event-based domain event publishing |
| `:appointment-solver` | Timefold Solver AI scheduling optimizer |
| `:appointment-notification` | Notification scheduler (Resilience4j, Redis Leader Election) |
| `:appointment-api` | Spring Boot MVC API (JWT, Flyway, Swagger, Gatling) |
| `:frontend:appointment-frontend` | Angular frontend |

## Documentation Rules

- Keep `README.md` and `README.ko.md` structurally aligned.
- Store shared README images under `docs/assets/` and reference them with the
  same relative path from both locales.
- Keep this file and other agent-facing guidance in English.

## Key Files

| Purpose | Path |
|---------|------|
| Dependency versions | `buildSrc/src/main/kotlin/Libs.kt` |
| Module registration | `settings.gradle.kts` |

## Module Gotchas

- **Transactions required** - Run all Exposed work inside `transaction {}`.
- **DB init in tests** - Use `SchemaUtils.createMissingTablesAndColumns(Table)`
  plus `Table.deleteAll()` in `@BeforeEach`.
- **Flyway SQL** - `scheduling_*` table names are database schema names; do not rename them.
- **Testcontainers** - Do not use `@Testcontainers`; use bluetape4k singleton launchers.
