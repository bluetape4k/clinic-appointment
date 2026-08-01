# Booking Reliability Policy Baseline

Status: approved · Related issues: #176, #170 · Baseline date: 2026-08-01

This is the normative policy document for applying repeated `NO_SHOW` and patient-responsible
`late cancellation` to new booking eligibility. The [approved design](superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md)
and [implementation plan](superpowers/plans/2026-08-01-issue-176-booking-reliability-plan.md) provide
the implementation record.

## Decisions

- The decision key is `tenantGroupId + clinicId + MemberId`. Names, phone numbers, email addresses,
  and free-form staff labels are never inputs, stored fields, or metric tags.
- The policy is read from an immutable effective `PRIORITY_AND_RELIABILITY` snapshot. Tenant values
  are inherited or replaced by clinic `INHERIT`/`SET`; threshold-level `DISABLE` disables only that
  threshold.
- `NO_SHOW` and `CANCELLED` events carry typed responsibility: `PATIENT`, `CLINIC`,
  `OPERATIONAL_EXCEPTION`, `DATA_CORRECTION`, or `UNKNOWN`. Only patient-responsible events count;
  `UNKNOWN` is observed as `UNATTRIBUTED_EVENT_EXCLUDED`.
- Outcomes are `ELIGIBLE`, `REQUIRES_STAFF_APPROVAL`, `RESTRICTED`, `OVERRIDDEN`,
  `POLICY_DISABLED`, `STALE`, or `UNAVAILABLE`.
- `PROPOSED`, `HELD`, and a new direct `CONFIRMED` path re-check the decision immediately before
  commit. An existing `CONFIRMED` appointment is never automatically changed, cancelled, or
  deallocated by this policy.
- Staff override and clear append audit rows and use decision digest/version CAS; they do not mutate
  the original decision.
- A new proposal/commitment stores an immutable decision stamp (`decisionId`, policy version/hash,
  evaluation digest, and expiry). Existing `CONFIRMED` rows keep their prior behavior and stamp.

## Defaults and bounds

| Setting | Default | Safety rule |
|---|---:|---|
| lookback | 180 days | pinned in the effective policy |
| late-cancellation window | 120 minutes | measured before scheduled start |
| no-show threshold | 3 events | patient responsibility only |
| late-cancellation threshold | 3 events | independent from no-show |
| cooling-off | 24 hours | represented by `expiresAt` |
| history read | 100 rows | bounded to member and clinic |
| trigger IDs in response | 32 | remainder uses an opaque audit cursor |

Values are system settings and can be overridden per clinic. A clinic without an override inherits
the tenant baseline. A legacy schemaVersion 1 payload without thresholds decodes to `POLICY_DISABLED`
and does not change existing booking behavior.

## Evaluation flow

1. Convert trusted appointment outcomes into typed attribution events.
2. Dedupe by `(tenant, clinic, member, eventId, sourceVersion)` and keep the highest source version.
3. Count patient-responsible no-shows and late cancellations in the bounded lookback.
4. When a threshold is reached, apply the clinic restriction mode: exclude automatic same-day offers
   or require staff approval.
5. Persist policy version/hash, counts, reason codes, trigger IDs, expiry, and a decision digest as an
   immutable decision row.
6. An active cooling-off is retained until `expiresAt`; expiry without a new qualifying event yields
   `COOLING_OFF_EXPIRED` and does not renew the restriction. `OFF` preserves existing booking behavior,
   `SHADOW` observes without blocking, and `ENFORCE`
   fails closed on restrictions, stale snapshots, and unavailable decisions.

## Privacy and authorization

The decision API exposes only the opaque `memberId` and bounded appointment IDs. Names and phone
numbers remain behind the member-management service's separate authorization boundary and are not
replicated into the booking reliability store. Preview requires `booking-reliability:read`, audit
requires `booking-reliability:audit`, and override/clear require `booking-reliability:write` plus
exact clinic membership. Actor identity comes from the verified principal, never the request body.

## Failure and rollback

- If schema/table/index readiness is incomplete, the worker does not start and an `ENFORCE` gate
  returns `BOOKING_DECISION_UNAVAILABLE`.
- A policy snapshot mismatch returns `BOOKING_DECISION_STALE`; clients reload and retry.
- Expired DB leases are reclaimed with owner fencing. Retries use bounded exponential backoff and a
  maximum attempt count; exhausted jobs enter `DEAD_LETTER`, and operators can `PAUSED`/`resume` a
  durable member-level job. Coroutine cancellation is not converted into a retry.
- Promote canaries `OFF → SHADOW → ENFORCE`. Require at least 24 hours and 1,000 decisions (the
  later condition), p95 ≤250ms/p99 ≤500ms, zero duplicate/unavailable/raw-PII findings, and less
  than 1% missing attribution. On failure, empty the clinic allowlist and return to `SHADOW` or `OFF`.

## Related documents and visuals

- [API contract](api/booking-reliability.md)
- [Operations runbook](runbooks/booking-reliability.md)
- [Canary evidence template](runbooks/booking-reliability-canary-evidence-template.md)
- [Workflow HTML and PNG](visual-companions/README.md#booking-reliability-workflow)
- [Approved design](superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md)

`#170` waitlist/offer candidate generation and response consumption are not implemented here; this
change exposes the read-only port and commitment stamp contract for that follow-up.

<!-- booking-reliability-workflow-en-light.html / booking-reliability-workflow-en-dark.html -->
<!-- booking-reliability-workflow-ko-light.html / booking-reliability-workflow-ko-dark.html -->
