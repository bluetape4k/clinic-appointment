# Waitlist delivery API contract

This document describes the phase-two staff boundary for issue #170. Patient
self-service, public magic links, payment, CRM attribution, and external
messaging brokers are not part of this contract.

## Scope and headers

The base path is `/api/{tenantCode}/clinics/{clinicId}/waitlist`. The JWT
principal, tenant membership, clinic membership, and capability are authoritative;
request bodies cannot override them. Every mutation requires
`Idempotency-Key` with 16–128 printable ASCII characters. Commands that change an
existing row also require `expectedVersion` in the JSON body.

Entry, offer, policy, adjustment, and appointment references are opaque strings.
The API decodes and scope-checks them before calling the internal `Long` ID port.
Malformed, wrong-kind, and wrong-clinic references are intentionally returned as
`404 WAITLIST_REFERENCE_NOT_FOUND`.

## Routes

| Method | Path | Capability | Result |
|---|---|---|---|
| `POST` | `/entries` | `waitlist:write` | Create a scoped waiting entry (`201`). |
| `GET` | `/entries`, `/entries/{entryRef}` | `waitlist:read` | Keyset page or one entry (`200`). |
| `POST` | `/entries/{entryRef}/withdraw` | `waitlist:write` | Versioned withdrawal (`200`). |
| `GET` | `/offers`, `/offers/{offerRef}`, `/offers/{offerRef}/decision` | `waitlist:read` | Offer or decision view (`200`). |
| `POST` | `/offers/{offerRef}/confirm` | `waitlist:write` | One replacement appointment (`201`). |
| `POST` | `/offers/{offerRef}/decline` | `waitlist:write` | Decline and release (`200`). |
| `GET` | `/policies/active`, `/policies/{policyRef}` | `waitlist:read` | Effective policy view (`200`). |
| `POST` | `/policies`, `/policies/{policyRef}/activate` | `waitlist:policy` | Versioned policy write/activation (`201`/`200`). |
| `POST` | `/restrictions`, `/restrictions/{restrictionRef}/release` | `waitlist:adjustment` | Bounded restriction change. |
| `POST` | `/recovery-credits`, `/recovery-credits/{recoveryCreditRef}/revoke` | `waitlist:adjustment` | Bounded recovery credit change. |
| `POST` | `/benefit-grants`, `/benefit-grants/{benefitGrantRef}/revoke` | `waitlist:adjustment` | Approved, capped benefit change. |

List responses use `{ "items": [...], "nextCursor": "..." }`. The default
page size is 50 and the maximum is 100. A cursor is bound to its filter, scope,
and ordering; a tampered or stale cursor returns `400 INVALID_CURSOR`.

## Confirm and replay contract

The confirm path reserves the idempotency command in a short transaction, then
locks and revalidates the offer, entry, hold, policy decision, and appointment
capacity in a separate business transaction. The result record is completed in a
third transaction. A process loss after appointment creation leaves the command
`PROCESSING`; a retry reconciles the existing replacement appointment by command
scope and request digest instead of creating another one.

| Situation | Status | Reason |
|---|---:|---|
| First confirm | `201` | `ACCEPTED` with opaque `appointmentRef`. |
| Same key and same request after success | `201` | Original result with `Idempotent-Replay: true`. |
| Same key while processing | `202` | `IDEMPOTENCY_IN_PROGRESS`, `Retry-After: 1`. |
| Expired/stale/occupied offer | `409` | `OFFER_EXPIRED`, `DECISION_STALE`, or `SLOT_OCCUPIED`. |
| Same key with another request digest | `409` | Stable idempotency conflict. |

Notification delivery is not acceptance. A provider failure or unknown result
records delivery state and cannot revive or accept an offer.

## Error and redaction contract

Errors contain only a safe message, `reasonCode`, `correlationId`, `retryable`,
and optional `retryAfterSeconds` (plus the compatibility `errorCode` alias). They
never include raw member IDs, contact details, clinical notes, policy score
vectors, JWT claims, SQL, or provider exception text.

| Status | Reason families |
|---:|---|
| `400` | `INVALID_IDEMPOTENCY_KEY`, `PAYLOAD_INVALID`, `INVALID_CURSOR` |
| `401` | `AUTH_UNAUTHENTICATED` from the shared security envelope |
| `403` | `WAITLIST_FORBIDDEN` / `AUTH_SCOPE_DENIED` |
| `404` | `WAITLIST_REFERENCE_NOT_FOUND` |
| `409` | stale version, terminal state, expiry, capacity, or idempotency conflict |
| `503` | `WAITLIST_UNAVAILABLE`, with `Retry-After` when retryable |

## Rollout

`appointment.waitlist.delivery.enabled=false` is the safe default. An optional
`clinic-allowlist` enables dispatch only for selected clinics. Turning the flag
off or removing a clinic stops new vacancy dispatch and notification delivery;
expiry, suppression, and stuck-hold reconciliation continue. Database fencing,
not the Redis leader lease, authorizes terminal writes.

Operational commands and evidence are in the [waitlist delivery runbook](../runbooks/waitlist-delivery.md).
