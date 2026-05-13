# WIP - clinic-appointment

Snapshot: 2026-05-13 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 3 issues.

## Recently Completed

- Bilingual README set, Java baseline alignment, Spring Cache conversion, NearCache adoption, dependency governance, and compatibility guard maintenance are merged.
- Frontend dependency updates and backend test/solver coverage improvements are merged.
- Dependency cleanup: removed redundant `bluetape4k-bom` imports — now fully covered by `bluetape4k-dependencies`.

## Current Direction

The active queue is focused on CI selectivity, repository cache design direction,
and multitenancy strategy. Keep these as separate PRs because they touch different risk areas.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#60](https://github.com/bluetape4k/clinic-appointment/issues/60) CI paths-filter | M | Reduces CI cost; verify workflows carefully. |
| P1 | [#52](https://github.com/bluetape4k/clinic-appointment/issues/52) Repository cache via Spring Cache annotations | M | Prior work exists; reconcile current code before editing. |
| P2 | [#36](https://github.com/bluetape4k/clinic-appointment/issues/36) Multitenancy strategy decision/design | M | Design-first; keep implementation separate. |

## Dependency Map

```text
#36 multitenancy strategy
  -> future implementation issues

#52 repository cache direction
  -> cache behavior/tests

#60 CI paths-filter
  -> workflow-only verification
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| CI/workflow | 1 | `#60` |
| Cache design/implementation | 1 | `#52` after current code audit. |
| Architecture/design | 1 | `#36` as design-only first. |
