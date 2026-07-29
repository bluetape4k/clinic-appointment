# Angular peer family upgrades must be atomic

## Context

Four Dependabot PRs attempted to raise individual Angular packages. Each left
Angular 21 compiler/compiler-cli packages beside Angular 22 build tooling, so
`npm ci` failed before the frontend build could run.

## Decision

Upgrade Angular runtime packages, build/CLI/compiler tooling, CDK/Material, and
TypeScript as one peer-compatible manifest. Do not use `--force` or
`--legacy-peer-deps` to silence Angular peer conflicts.

## Verification

On Node 26.5.0 and npm 11.17.0, clean `npm ci` resolved Angular 22.0.8,
CDK/Material 22.0.6, and TypeScript 6.0.3. `npm run build` passed. The frontend
test result remained equal to the current `develop` baseline: 6 failed files /
31 failed tests rooted in the pre-existing `localStorage.getItem` environment
failure. The Gradle-managed Node runtime was raised from 22.14.0 to 22.22.3,
which satisfies Angular 22's declared Node 22 floor; the Gradle frontend build
and test then passed with 26 files and 173 tests.

## Future guard

When Dependabot raises an Angular build, compiler, CLI, or runtime package across
a major line, consolidate the complete peer family and every repository-managed
Node runtime in an isolated branch. Compare the candidate test result to a fresh
baseline before merging.
