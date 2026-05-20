# Timefold Solver 2 Consumer Migration

## Context

The appointment solver consumes Timefold score, constraint-verifier, score
director, and move APIs. Timefold Solver 2.1 moved or changed all of these
surfaces.

## Decision

Migrate directly to the 2.1 API and keep the local solver behavior unchanged.

## Outcome

- Score imports now use `ai.timefold.solver.core.api.score.*`.
- Constraint weights return `Long` values for `HardSoftScore`.
- `ConstraintVerifier` is imported from the core artifact.
- Move filtering tests use the preview `Move` API and `SequencedCollection`
  return types.
- Constraint ids no longer include `:` because Timefold 2 rejects ids outside
  its allowed character set.

## Verification

- `./gradlew :appointment-solver:compileTestKotlin --no-daemon`
- `./gradlew :appointment-solver:test --no-daemon`
