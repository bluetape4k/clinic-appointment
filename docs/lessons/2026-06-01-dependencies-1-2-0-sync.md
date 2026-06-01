# Dependencies 1.2.0 Sync

## Context

`bluetape4k-dependencies:1.2.0` was published after the final upstream BOM
matrix became Maven Central-visible.

## Decision

Move the clinic appointment shared catalog from `1.1.4` to `1.2.0`.

## Outcome

The example app now consumes the published 1.2.0 dependency-governance baseline.

## Verification

- `sync-shared-versions.py --workspace .. --write --check --summary` updated
  the catalog line.
- Maven Central returned HTTP 200 for
  `io.github.bluetape4k:bluetape4k-dependencies:1.2.0`.

