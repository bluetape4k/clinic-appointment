# CLAUDE.md — clinic-appointment

개인 클리닉 환자 예약 관리 시스템 — Kotlin 2.3 + Java 25 + Spring Boot 4 + Exposed ORM.

- `bluetape4k-patterns` 스킬: 모든 Kotlin 코드에 예외 없이 적용

## Build

```bash
# 모듈 단위만
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

## Key Files

| Purpose | Path |
|---------|------|
| Dependency versions | `buildSrc/src/main/kotlin/Libs.kt` |
| Module registration | `settings.gradle.kts` |

## Module Gotchas

- **Transactions required** — 모든 Exposed 작업은 `transaction {}` 안에서
- **DB init in tests** — `@BeforeEach`: `SchemaUtils.createMissingTablesAndColumns(Table)` + `Table.deleteAll()`
- **Flyway SQL** — `scheduling_*` 테이블명은 DB 스키마명 — 이름 변경 금지
- **Testcontainers** — `@Testcontainers` 어노테이션 사용 금지. bluetape4k singleton 패턴만
