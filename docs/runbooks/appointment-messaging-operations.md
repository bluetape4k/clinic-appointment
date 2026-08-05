# Appointment messaging operations

## Scope and invariants

The writer records a redacted appointment event in the same database transaction as the
legacy mutation. The relay is at-least-once and may resend an `eventId`; consumers must use
the event id for their own idempotency work (Issue #42).

The stream is intentionally partial for Issue #41. Do not infer that commitment-v2 or the
closure `PENDING_RESCHEDULE` transition is covered.

If the durable outbox write is unavailable, the API returns `503` with a bounded
`Retry-After` header. Retry with the same idempotency key after the advertised interval;
do not create a replacement appointment or event id.

## Hold and recovery

1. Set the relay to **paused** and **held**; both gates stop new claims.
2. Allow in-flight sends to drain for up to 10 seconds. Do not write a terminal state for a
   cancelled or lease-lost send.
3. Check `appointment_outbox_pending`, `appointment_outbox_oldest_age_seconds`,
   `appointment_outbox_partition_skew`, lease expiry, retry age, and broker error code.
   The gauges are bounded aggregate signals; use the restricted SQL runbook query for row-level
   investigation and never put tenant, clinic, appointment, or partition values in metric labels.
4. Check the readiness snapshot: `enabled`, `configurationValid`, `schemaValid`,
   `serializerValid`, `brokerAvailable`, `relayPaused`, and `relayHeld`.
   The Spring Kafka publisher probes metadata for every allow-listed topic before claiming;
   `producer-metadata-timeout` bounds the probe. Topic absence, ACL denial, or TLS/SASL failure
   therefore leaves rows untouched rather than creating lease churn. Configuration errors are
   fail-closed; a valid writer may continue to leave rows `PENDING` during a broker outage.
5. Fix broker/TLS/SASL or schema configuration. Production producer settings must remain
   `acks=all`, idempotence enabled, auto-create disabled, and request/delivery/metadata timeouts
   bounded. Set broker `auto.create.topics.enable=false`; the producer cannot override this
   broker policy. For secured protocols, verify the `AppointmentKafkaCredentialResolver` reference
   and its least-privilege `ssl.*`/`sasl.*` output without copying secret values into logs.
6. Resume claims with the existing `event_id`; never edit payloads or create replacement IDs.

## Redrive and rollback

Manual redrive is an operator action. Preserve the row's event id and record the reason code
in the change log. Pause the relay before a V22 rollback; V22 columns are additive and must
not be dropped. A stale owner/token update must affect zero rows.

## Escalation

Escalate when the manual oldest-age query exceeds 180 seconds, lock wait p95 exceeds 50 ms,
lease churn or broker pause is continuous, a disallowed topic/key is observed, or the same
aggregate violates ordering. The relay exposes `appointment_outbox_lease_lost_total`,
`appointment_outbox_contract_rejected_total`, `appointment_outbox_failed_total`,
`appointment_outbox_retry_total` (tagged only by allow-listed stable failure code), and
`appointment_outbox_broker_pause_total`; it does not expose tenant,
clinic, appointment, or raw payload labels.
Metrics include bounded backlog/oldest-age/partition-skew gauges plus event type, outcome/status,
attempt, and stable failure code counters. A restricted change log may reference the opaque event id; never copy tenant,
clinic, appointment, or partition-key values into metrics or routine logs. Never attach patient
data, credentials, raw reason text, or payload JSON.
