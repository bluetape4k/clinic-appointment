# WIP - clinic-appointment

Snapshot: 2026-05-13 KST
Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
Open count: 1 issue (2 pending close).

## Recently Completed

- **#52** Repository cache → Spring `@Cacheable`/`@CacheEvict` 전환 완료 (commit `3fef28c`, `ceac3a0`). Issue still open — needs close.
- **#60** CI paths-filter + nightly workflow 적용 완료 (commit `991cde7`, `b0be270`). Issue still open — needs close.
- **#61** Kluent → `bluetape4k-assertions` 마이그레이션 완료 (commit `dd69e84`).
- Java 21 baseline 정렬, Dependabot governance guards, bilingual README, frontend security patches merged.
- Dependency cleanup: `bluetape4k-bom` redundant import removed (PR #79).

## Action Items

- [ ] Close issue #52 (work merged)
- [ ] Close issue #60 (work merged)

## Current Direction

Only #36 (multitenancy) remains as active design work.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P2 | [#36](https://github.com/bluetape4k/clinic-appointment/issues/36) Multitenancy strategy decision/design | M | Design-first; keep implementation separate. |

## Dependency Map

```text
#36 multitenancy strategy
  -> future implementation issues
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Architecture/design | 1 | `#36` design-only. |
