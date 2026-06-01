# Dependencies 1.1.4 Sync

## Context

`bluetape4k-dependencies` 1.2.0 release preparation found this app's catalog
and Dependabot ignore metadata behind the latest published dependencies
baseline.

## Decision

Align the consumer catalog to `bluetape4k-dependencies:1.1.4` and sync
centrally governed Dependabot ignores. Do not point the app at `1.2.0` until
that BOM is published.

## Outcome

The app no longer contributes shared-version or Dependabot-ignore drift to the
central dependencies release-train CI gate.

## Verification

Validated from `bluetape4k-dependencies` with `sync-shared-versions.py` and
`sync-dependabot-ignores.py` using `--workspace /Users/debop/work/bluetape4k
--write --check --summary`.
