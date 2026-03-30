# clinic-appointment Kotlin Dev Agent

개인병원 환자 예약 관리 시스템 — Kotlin 2.3 + Java 25 + Spring Boot 4 + Exposed ORM.

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

| 모듈 | 역할 |
|------|------|
| `:appointment-core` | Exposed ORM 도메인 모델, 리포지토리, 상태 머신 |
| `:appointment-event` | Spring 이벤트 기반 도메인 이벤트 퍼블리싱 |
| `:appointment-solver` | Timefold Solver AI 최적화 스케줄러 |
| `:appointment-notification` | 알림 스케줄러 (Resilience4j, Redis Leader Election) |
| `:appointment-api` | Spring Boot MVC API (JWT, Flyway, Swagger, Gatling) |
| `:frontend:appointment-frontend` | Angular 프론트엔드 |

## Key Files

| Purpose | Path |
|---------|------|
| Dependency versions | `buildSrc/src/main/kotlin/Libs.kt` |
| Module registration | `settings.gradle.kts` |

## Module Gotchas

- **Transactions required** — all Exposed ops must run inside `transaction {}`.
- **DB init in tests** — `@BeforeEach`: `SchemaUtils.createMissingTablesAndColumns(Table)` + `Table.deleteAll()`.
- **Flyway SQL** — `scheduling_*` 테이블명은 DB 스키마명이므로 변경 금지.
- **Testcontainers** — No `@Testcontainers` annotation. Use bluetape4k singleton pattern.

## Skills Reference

| Skill | When to use |
|-------|-------------|
| `bluetape4k-patterns` | Writing or reviewing any Kotlin code |
| `coroutines-kotlin` | Coroutines, Flow, Channel |
| `kotlin-spring` | Spring Boot + Kotlin integration |
