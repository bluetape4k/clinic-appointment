## Context

Adopted the JetBrains Exposed Gradle plugin for clinic appointment modules that define Exposed tables.

## Decision

The application stays independent from the managed `bt4k` catalog. It declares the plugin version locally and keeps `bluetape4k-dependencies` as the dependency BOM.

## Outcome

Core, API, event, and notification modules now expose `generateMigrations` with explicit migration settings.

## Verification

Ran `git diff --check`, `./gradlew -q help`, and `:appointment-core:tasks --all`.

## Future Guard

When an app catalog lacks an explicit JetBrains Exposed version, add a local plugin version that matches the Exposed BOM line used by `bluetape4k-dependencies`.
