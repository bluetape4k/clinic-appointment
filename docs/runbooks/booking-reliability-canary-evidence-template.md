# Booking Reliability Canary Evidence

Copy this template for one clinic rollout. Do not enter member names, phone numbers, email
addresses, free text, tokens, or raw payloads.

| Field | Value |
|---|---|
| Date / operator | |
| Tenant / clinic | opaque operational IDs only |
| Policy version / hash | |
| Mode and allowlist | `OFF` / `SHADOW` / `ENFORCE` |
| Observation window | UTC start/end |
| Decision count | ≥ 1,000 |
| p95 / p99 latency | p95 ≤ 250ms, p99 ≤ 500ms |
| Duplicate decisions | 0 |
| Unavailable backlog | 0 |
| Attribution-missing ratio | < 1% |
| Raw PII findings | 0 |
| Lease loss / failed jobs | 0 or explained |
| Existing `CONFIRMED` mutations | 0 |
| Health/readiness evidence | link or artifact ID |
| Query-plan evidence | link or artifact ID |
| Rollback tested | yes/no + evidence |
| Decision | promote / hold / rollback |
| Correlation IDs | bounded list |

## Promotion rule

Promote only when observation is at least 24 hours and decision count is at least 1,000, with every
numeric and privacy gate above passing. A missing field is a failed gate, not an implicit pass.
