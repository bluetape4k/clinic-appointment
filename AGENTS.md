# AGENTS.md - clinic-appointment

Clinic appointment management example app built with Kotlin 2.3, Java 25,
Spring Boot 4, and Exposed ORM.

Use `bluetape4k-patterns` for all Kotlin implementation and review work.

## Commands

Prefer module-scoped validation.

```bash
./gradlew :<module>:build
./gradlew :<module>:test
./gradlew :<module>:test --tests "fully.qualified.ClassName.methodName"
```

## Modules

| Module | Purpose |
|---|---|
| `:appointment-core` | Exposed ORM domain models, repositories, state machine |
| `:appointment-event` | Spring event-based domain event publishing |
| `:appointment-solver` | Timefold Solver scheduling optimizer |
| `:appointment-notification` | Notification scheduler with Resilience4j and Redis leader election |
| `:appointment-api` | Spring Boot MVC API with JWT, Flyway, Swagger, Gatling |
| `:frontend:appointment-frontend` | Angular frontend |

## Key Files

| Purpose | Path |
|---|---|
| Dependency versions | `buildSrc/src/main/kotlin/Libs.kt` |
| Module registration | `settings.gradle.kts` |

## Rules

- All Exposed work must run inside `transaction {}`.
- Test DB setup should use `SchemaUtils.createMissingTablesAndColumns(Table)`
  and `Table.deleteAll()` in `@BeforeEach`.
- Flyway SQL `scheduling_*` table names are schema names; do not rename them.
- Do not use `@Testcontainers`; use bluetape4k singleton launchers.
