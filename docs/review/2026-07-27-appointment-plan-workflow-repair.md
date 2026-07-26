# Appointment Plan Workflow Repair Review

## Scope

- Workflow run: `20260726T192612Z-7a7c4518`
- Type: A — Full Feature
- Base: `c4f0dff1deccce37b2cf1345f321aceb140bbb40`
- Reviewed branch: `design/appointment-plan-scheduling`
- Initial reviewed commit: `b37901bac768c391bf7ae9d7e3178224dd3b76e3`
- Delivery authority: local repair and commit only; push, PR, merge, production
  `WRITE` activation excluded

## Step 2-R Initial Review

Six independent read-only lenses reviewed the exact Markdown specification.

| Lens | P0 | P1 | P2 | Initial verdict |
|---|---:|---:|---:|---|
| Performance | 0 | 3 | 3 | FAIL |
| Stability | 0 | 6 | 2 | FAIL |
| Security | 0 | 3 | 1 | FAIL |
| Operator/Ops | 0 | 5 | 4 | FAIL |
| Developer/API | 0 | 5 | 2 | FAIL |
| User/caller | 0 | 5 | 4 | FAIL |
| Total before deduplication | 0 | 27 | 16 | FAIL |

Main-session integration deduplicated the blocking findings into these repair
groups:

1. Foundation slice versus long-term roadmap boundary
2. tenant/clinic/source-authority-qualified catalog and purchase identities
3. canonical `EXPIRED` lifecycle
4. policy cache boundary, activation CAS, and stale-result fencing
5. saga retry, cancellation, repair, and orphan cleanup
6. disruption/solver result fencing and quality floor
7. trust-material rotation/revocation and event quarantine
8. privileged dry-run redrive and tamper-evident audit
9. Foundation event schema and PHI classification
10. exact Foundation HTTP/error/authorization contracts
11. caller-safe booking/consent/misuse contracts for deferred APIs
12. rollback-safe legacy projection and online backfill contract
13. slot-query, cache, solver, queue, and migration benchmark contracts
14. feature flags, kill switches, alert routing, and backpressure order
15. production enablement thresholds and immutable release evidence
16. reliability-profile explanation, correction, and appeal
17. exact Foundation acceptance commands and multi-dialect ordering
18. standalone HTML current-slice, risk, and normative-source clarity

The Markdown specification and standalone HTML were repaired in the main
session. These edits clarify the approved model and operational contracts; they
do not expand the implemented Foundation slice.

## Step 2-R Rereview

Every affected lens rereviewed the exact repaired artifact. A developer/API
rereview found one additional P1: the catalog event dedupe key still omitted
tenant/clinic/source authority. Main integration also found that
`PurchaseCompleted` could not select an authority-qualified catalog without
`catalogSourceAuthority`. Both contracts were repaired and the affected lens
reran.

| Lens | Final P0 | Final P1 | Final P2 | Final P3 | Verdict |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | PASS |
| Stability | 0 | 0 | 0 | 0 | PASS |
| Security | 0 | 0 | 0 | 0 | PASS |
| Operator/Ops | 0 | 0 | 0 | 0 | PASS |
| Developer/API | 0 | 0 | 0 | 0 | PASS |
| User/caller | 0 | 0 | 0 | 0 | PASS |

The rereview also closed the non-blocking findings that could be repaired in
the current artifact:

- quarantine retention and legal-hold ownership;
- `EXPIRED` in the lifecycle conflict matrix;
- HTML backfill timing, terminal-state accessibility, and first-column sizing;
- external `kid` to internal `keyId` mapping;
- catalog event and purchase catalog-source authority.

Step 2-R integrated result: **P0=0, P1=0**.

## Mandatory Downstream Blockers

These are implementation/plan consistency defects, not unresolved spec
contradictions:

1. Exposed and all V8 dialect migrations must add `sourceAuthority` to catalog
   uniqueness and exact/latest lookup predicates.
2. Exposed and all V8 dialect migrations must scope purchase uniqueness by
   tenant and clinic.
3. `PurchaseCompletedEvent` and plan snapshot persistence must carry
   `catalogSourceAuthority`.
4. Repository, handler, race, security, migration, benchmark, and `EXPLAIN`
   tests must prove the corrected identities on H2, PostgreSQL, and MySQL.

## Step 3-R Initial Review

Six independent read-only lenses reviewed the executable Markdown plan against
the Step 2-R-converged specification.

| Lens | P0 | P1 | P2 | P3 | Initial verdict |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 1 | 1 | 0 | FAIL |
| Stability | 0 | 1 | 0 | 0 | FAIL |
| Security | 0 | 2 | 0 | 0 | FAIL |
| Operator/Ops | 0 | 1 | 2 | 0 | FAIL |
| Developer/API | 0 | 1 | 2 | 1 | FAIL |
| User/caller | 0 | 0 | 1 | 0 | PASS with P2 |
| Total before deduplication | 0 | 6 | 6 | 1 | FAIL |

Main-session integration repaired these groups:

1. executable PostgreSQL/MySQL purchase-expansion performance and `EXPLAIN`
   tasks, fixtures, thresholds, commands, and release evidence;
2. explicit security, configuration, migration, dialect, and final acceptance
   commands;
3. disallowed algorithm, unknown/revoked `kid`, key-pin mismatch, and external
   `kid` to internal `keyId` mapping tests;
4. authority-qualified catalog and purchase identities across model, table,
   repository, event, factory, response, and all V8 dialects;
5. immutable encrypted quarantine storage, append-only audit, retention, legal
   hold, metric label/cardinality budget, and alert ownership;
6. source-authority timeout/circuit-open convergence plus invalid
   retry/backoff/jitter/window configuration rejection;
7. `ACTIVE`/`RETIRED` catalog projection lifecycle;
8. explicit RED ordering for index-plan assertions and reproducible final
   security/stability evidence;
9. standalone HTML parse, relative-link, anchor, desktop, and mobile smoke.

An Operator/Ops rereview found one additional P1: critical
trust/signature/scope incidents used a 15-minute acknowledgement even though
the specification requires five minutes plus immediate consumer
block/quarantine. The plan was repaired in its operational contract, runbook
task, and release-evidence contract, then rereviewed.

## Step 3-R Rereview

| Lens | Final P0 | Final P1 | Final P2 | Final P3 | Verdict |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | PASS |
| Stability | 0 | 0 | 0 | 0 | PASS |
| Security | 0 | 0 | 0 | 0 | PASS |
| Operator/Ops | 0 | 0 | 0 | 0 | PASS |
| Developer/API | 0 | 0 | 0 | 0 | PASS |
| User/caller | 0 | 0 | 0 | 1 | PASS |

The remaining user/caller P3 is a later Task 7 wording improvement: the
runbook must name `sourcePurchaseAuthority + sourcePurchaseId` and
`catalogSourceAuthority + productId + catalogVersion` rather than generic
authority/version phrases.

Step 3-R integrated result: **P0=0, P1=0**.

## Pending Gates

- implementation/spec/plan verifier
- final six-lens code review and integration
- final validation, checklist counts, and Lore commit
