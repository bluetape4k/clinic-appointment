# Booking Reliability Operations Runbook

Use this runbook to observe, promote, and roll back `booking.reliability` per clinic. The
[policy baseline](../booking-reliability-policy.md) is normative; the [API contract](../api/booking-reliability.md)
defines the HTTP shape.

## Pre-deployment checks

1. Confirm Flyway V17 created all four tables and the named unique/index contracts.
2. Confirm schema readiness is `ready=true`. If it is false, do not start the worker or enable
   `ENFORCE`.
3. Record tenant and clinic policy version/hash, lookback, thresholds, and cooling-off. A threshold
   `DISABLE` applies only to that threshold.
4. Run a denylist scan: names, phone numbers, email addresses, free text, and raw payloads must not
   appear in decisions, events, metrics, or logs.
5. With a precisely clinic-scoped operator token, exercise preview, audit, override, and clear.

## Staged rollout

| Stage | Settings | Expected behavior | Promotion gate |
|---|---|---|---|
| OFF | `booking.reliability.mode=OFF` | Existing booking behavior; no gate call | Additive migration and smoke checks pass |
| SHADOW | `mode=SHADOW` plus clinic allowlist | Persist/observe decisions; never block booking | Later of 24 hours or 1,000 decisions |
| ENFORCE | `mode=ENFORCE` plus a small allowlist | Fail closed on restrictions/stale/unavailable | All canary evidence passes |

Start with a small clinic allowlist and expand gradually. Empty the allowlist and return to
`SHADOW` if any of these conditions occurs:

- p95 latency >250ms or p99 >500ms;
- any duplicate decision, unavailable backlog, or raw-PII finding;
- attribution-missing ratio ≥1%;
- increasing lease loss or a `DEAD_LETTER` job;
- any state/resource mutation observed on an existing `CONFIRMED` appointment.

## Incident handling

### Decision unavailable or stale

`BOOKING_DECISION_UNAVAILABLE` indicates a DB/policy readiness or persistence failure. Check health
and the correlation ID, stop the worker, and lower the mode to `SHADOW`/`OFF`. Retry the same intent
with bounded backoff. For `BOOKING_DECISION_STALE`, reload the current decision and update both
`decisionId` and `evaluationDigest`.

### Worker backlog

Inspect `RETRY_WAIT`, `DEAD_LETTER`, `PAUSED`, and old `RUNNING` rows in `booking_reliability_reevaluation_jobs`
by clinic. Do not edit a row owned by another lease; wait for expiry and fencing. Retry obeys the
maximum attempt and delay bounds, and cancellation is never converted into retry. Redrive a
`DEAD_LETTER`/quarantine row only with an approved idempotency key and a bounded batch. Pause a
job before investigation; resume it only after the cause is documented and the same durable
cursor can be replayed safely.

### Incorrect attribution

An `UNKNOWN` event is never counted as patient responsibility. Correct responsibility in the source
appointment/operations system and publish a new `sourceVersion`. Keep the old event and decision;
preserve the new digest and audit trail.

### Privacy finding

Immediately lower the affected clinic to `OFF` and isolate the log/metric/response/row. Never copy
PII into a decision manually. Follow the member-management security incident process; retain only
bounded actor, correlation, and opaque references here.

## Staff override and clear

1. Read the current decision.
2. Fix `Idempotency-Key`, `decisionId`, and `evaluationDigest`.
3. Use the allowlisted `MANUAL_OVERRIDE` or `MANUAL_CLEAR` reason.
4. On a 409 stale response, reload; never reuse a key with a different payload.
5. Verify actor, effective/expiry, and result digest in audit.

An override does not retroactively change an existing `CONFIRMED` appointment. It is a bounded
decision for a reviewed new offer/commitment.

## Retention and rollback

The module exposes a bounded retention executor contract that checks `retentionClass` and legal hold
before each batch. A legal hold skips deletion. The default executor is intentionally a no-op;
production must inject a tenant/clinic-scoped executor that performs the approved deletion or
pseudonymization and emits its audit evidence. V17 is additive; rollback lowers the application to
`OFF`/`SHADOW` and never rewrites an already-applied migration.

## Evidence

Complete the [canary evidence template](booking-reliability-canary-evidence-template.md) for each
deployment. Record policy version/hash, mode, allowlist, decision volume, latency, backlog,
attribution-missing ratio, PII scan, rollback, and correlation IDs.
