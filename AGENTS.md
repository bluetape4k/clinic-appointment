# AGENTS.md - clinic-appointment

## Guidance hierarchy

Before applying this repository overlay, read and follow the guidance in this
order:

1. User scope: `${CODEX_HOME:-$HOME/.codex}/AGENTS.md`.
2. Workspace scope: `/Users/debop/work/bluetape4k/.github/docs/workspace/AGENTS.md`.

Apply both broader scopes before repository-specific rules.

This repository inherits the workspace guidance from `../AGENTS.md`.
Read and follow the workspace root guide first. This file only adds
repo-specific layout, commands, domain rules, and local exceptions.


Clinic appointment management example app built with Kotlin 2.3, Java 25,
Spring Boot 4, and Exposed ORM.

Use `bluetape-kotlin-patterns` for all Kotlin implementation and review work.

## Repository-local Korean artifact policy

`clinic-appointment` is a Korean-only example application. This section is a
repo-local override of the workspace audience-language table and applies only
inside this repository.

- Write all project documentation in Korean, including `README*`, work
  documents, specifications, plans, research notes, lessons, KDoc, and
  documentation comments.
- Write all GitHub-facing artifacts in Korean, including Issue and PR titles,
  bodies, comments, review replies, release notes, and changelog entries.
- Write repository-controlled commit messages and other user-facing delivery
  notes in Korean.
- Do not require an English README or bilingual companion for this repository;
  README and documentation variants are Korean artifacts unless a technical
  identifier or an externally required exact string must remain unchanged.
- Preserve code, identifiers, API names, commands, URLs, required metadata keys,
  machine-readable syntax, and exact error text as written. Translate the
  surrounding prose, not those technical values.
- This exception is scoped to `clinic-appointment`; do not propagate it to the
  workspace guide or sibling repositories. Agent-facing operating files such
  as `AGENTS.md` remain concise English so the tooling contract stays readable.

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
| `:appointment-messaging-benchmark` | PostgreSQL production-schema outbox claim benchmark using `kotlinx-benchmark` |
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
