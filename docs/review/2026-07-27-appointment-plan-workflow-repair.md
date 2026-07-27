# Appointment Plan Workflow Repair Review

## Scope

- Workflow run: `20260726T192612Z-7a7c4518`
- Type: A — Full Feature
- Base: `c4f0dff1deccce37b2cf1345f321aceb140bbb40`
- Reviewed branch: `design/appointment-plan-scheduling`
- Initial reviewed commit: `b37901bac768c391bf7ae9d7e3178224dd3b76e3`
- Delivery authority: local repair and commit only; push, PR, merge, production
  `WRITE` activation excluded

## Evidence Correction

The original document presented the review tables below as completed gate
evidence without preserving independently attributable reviewer outputs and
fresh validation against the final artifact. Those completion claims are
invalidated and must not be used to approve Step 2-R, Step 3-R, or the final
7-Tier gate.

The tables below are retained only as historical repair context. The
authoritative rerun is recorded in **Verified Step 2-R/3-R Rerun** after six
independent current-artifact reviews have reported exact findings and the main
session has closed every P0/P1. Final 7-Tier evidence is recorded separately
after implementation verification.

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

## Implementation-discovered Step 3-R Amendment

The executable performance fixture exposed two contradictions that the initial
Step 3-R rereview missed:

1. The approved `2,000 treatment / 10,000 edge` Foundation fixture was
   unreachable. One explicit catalog dependency materializes one persisted
   treatment edge, and the catalog contract allows at most 1,000 explicit
   dependencies. The reachable persisted maximum is therefore 2,000 treatments
   and 1,000 dependency rows. The validator separately inspects at most 2,980
   graph edges after adding 1,980 implicit repeat-order edges.
2. `uq_plan_source_purchase` and `idx_plan_scope_purchase` indexed the same
   columns. PostgreSQL could select either, making the named-index proof
   nondeterministic and adding redundant write cost. The non-unique duplicate
   was removed from Exposed, all three V8 dialects, and migration expectations.

The 256 KiB validator also counted diagnostic property paths and array indexes
that are not part of the wire payload. A focused RED test proved that the
compact maximum graph was rejected. The estimator now counts a conservative
JSON-shaped representation including escaping and accepts that fixture without
weakening the byte limit.

The independent amendment review then found additional executable-proof gaps:

3. the EXPLAIN test did not compile and its 120-row fixture did not satisfy the
   approved 100,000-row/20-partition contract;
4. dependency selectivity was insufficient for natural PostgreSQL index
   selection, and the outbox oldest-age path was absent;
5. the performance test checked SQL bounds only for the final measured sample
   and started capture after the initial inbox read;
6. the estimator was not accompanied by an actual canonical API payload byte
   proof.

The repaired gate now uses 100,000 plan, inbox, and outbox rows, 20
tenant/clinic partitions, and 20 dependency-bearing plans with 2,000
treatments/1,000 persisted dependencies each. Retry and pending queue rows are
kept at a representative one-percent distribution. PostgreSQL and MySQL both
select the five named indexes without optimizer forcing. Every measured
purchase sample captures the complete transaction and proves the same bounded
17-statement shape. Jackson 3 serializes the accepted maximum compact API
request to 194,876 bytes under the unchanged 262,144-byte limit.

The representative cleanup also exposed missing reverse-FK indexes. V8 and the
Exposed definitions now include `idx_outbox_plan_id` and
`idx_treatment_dependency_successor`; PostgreSQL fixture cleanup fell from
116,489 ms to 16,953 ms.

The two affected lenses then independently rereviewed the amended executable
proof.

| Amendment lens | P0 | P1 | P2 before evidence repair | Final P2 | P3 | Verdict |
|---|---:|---:|---:|---:|---:|---|
| Performance/stability | 0 | 0 | 2 | 0 | 1 watch item | PASS |
| Developer/API | 0 | 0 | 0 | 0 | 1 stale-number finding | PASS |

The performance reviewer found that statement classes were bounded but the
total SQL count was only printed, and that the release record lacked exact
commands, timestamps, implementation SHA, report paths, and raw samples. The
test now asserts exactly 18 statements for every measured transaction and the
release record preserves the requested reproduction evidence. Its remaining
P3 watch item is adversarial near-limit string-heavy catalog payloads; the
Foundation maximum compact payload itself is proven with actual Jackson 3
serialization.

The developer/API reviewer independently confirmed the 100,000-row fixture,
five natural index paths, complete transaction capture, and canonical payload
proof. It found only stale PostgreSQL p95 values in the release record. Those
values were replaced with the final post-assertion rerun:
PostgreSQL `26 ms / 1,349 ms`, MySQL `17 ms / 797 ms`.

Step 3-R amendment integrated result: **P0=0, P1=0, P2=0**.

## Authoritative Step 2-R and Step 3-R Evidence

The repair reran both review gates against the current artifacts, with six
independent read-only lenses for each gate. Each reviewer reported exact
findings by severity; main-session integration repaired the blocking findings
and sent the affected lens through rereview.

| Gate | Performance | Stability | Security | Operator/Ops | Developer/API | User/caller | Integrated result |
|---|---|---|---|---|---|---|---|
| Step 2-R specification | PASS | PASS | PASS | PASS | PASS | PASS | `P0=0 P1=0` |
| Step 3-R executable plan | PASS | PASS | PASS | PASS | PASS | PASS | `P0=0 P1=0` |

These are the authoritative 2-R/3-R results. The historical tables above
explain how the artifacts changed, but are not completion evidence by
themselves.

## Final 7-Tier Implementation Review

Six independent implementation lenses reviewed the current diff. Main-session
integration was the seventh tier and owned deduplication, repair, rereview, and
fresh validation.

| Tier | Final P0 | Final P1 | Final P2 | Final P3 | Verdict |
|---|---:|---:|---:|---:|---|
| Performance/runtime | 0 | 0 | 2 | 2 | PASS |
| Architecture/stability | 0 | 0 | 2 | 0 | PASS |
| Security/privacy | 0 | 0 | 1 | 2 | PASS |
| Operator/SRE | 0 | 0 | 3 | 1 | PASS |
| Developer/API | 0 | 0 | 2 | 0 | PASS |
| User/operator | 0 | 0 | 0 | 0 | PASS |
| Main integration | 0 | 0 | 0 | 0 | PASS |

Blocking findings discovered and closed during this final gate:

1. catalog v7/v8 out-of-order synchronization now serializes per clinic and
   rechecks exact/latest versions under lock;
2. quarantine release/legal-hold transitions now use compare-and-set semantics,
   preventing stale release from overwriting concurrent expiry;
3. catalog and plan success responses now expose concrete OpenAPI envelope
   schemas, pinned by `/v3/api-docs` tests;
4. the recovery runbook now distinguishes terminal untrusted-event rejection
   evidence from releasable quarantine and uses schema-valid bounded selectors;
5. English/Korean API docs now describe exact-scope SaaS feature overrides and
   the production audit/readback gate.

Non-blocking P2/P3 items remain recorded for the production transport and later
API slices: typed outbox payload contracts, source-version DB hardening,
non-throwing metrics, broker malformed-message DLQ proof, full transport
readiness semantics, and narrower error-envelope schema. None authorizes
production `WRITE`.

Final 7-Tier integrated result: **PASS; P0=0, P1=0**.
