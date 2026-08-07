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

## Consumer readiness and operating SLOs

The consumer side exposes aggregate, low-cardinality signals: `appointment_consumer_lag`,
`appointment_consumer_oldest_age_seconds`, `appointment_consumer_lag_unavailable_total`,
`appointment_consumer_processed_total`, `appointment_consumer_duplicate_total`,
`appointment_consumer_retry_total`, `appointment_consumer_quarantined_total`,
`appointment_consumer_inbox_transaction_seconds`, `appointment_consumer_replay_total`, and
`appointment_consumer_retention_deleted_total`. No tenant, clinic, partition, request id, or
payload label is permitted.

The initial operating thresholds are lag over 100 records for 10 minutes, oldest processing
age over 180 seconds, retry/quarantine increases over 10 minutes, and inbox transaction p95
over 50 ms. These thresholds are alerting defaults, not production SLO evidence. Confirm the
deployment SLO with the target broker, pool size, CPU architecture, and database lock-wait
measurements before closing the rollout gate.

Retention is disabled by default. When enabled, the bounded retention service deletes only
terminal processed/quarantined/rejected and `DRY_RUN`/`EXECUTED`/`REJECTED` replay-audit rows;
it leaves `PROCESSING` inbox rows and `REQUESTED` replay audits intact. To run it in-process,
also set `appointment.messaging.retention.scheduler-enabled=true`; deployments using an
external CronJob must keep the in-process scheduler disabled. Record deletion counts and
verify that raw Kafka values, credentials, and request scope are absent from metrics and logs.

## MySQL production metadata readiness

The API migration suite proves the V23 contract on the H2, PostgreSQL, and MySQL singleton
fixtures. A deployed/staging MySQL endpoint can run the same contract as a **read-only metadata
smoke test** when its credentials are supplied out-of-band:

```bash
APPOINTMENT_PRODUCTION_MYSQL_JDBC_URL='jdbc:mysql://host:3306/database?sslMode=VERIFY_IDENTITY' \
APPOINTMENT_PRODUCTION_MYSQL_USER='read_only_user' \
APPOINTMENT_PRODUCTION_MYSQL_PASSWORD='provided-by-secret-manager' \
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest.production MySQL metadata readiness is verified when endpoint is configured'
```

The test reads JDBC metadata, V23 columns, primary keys, indexes, and the selected MySQL
catalog; it does not run `Flyway.clean()` or apply a migration. Applying V23 to production is a
separate change-window operation: first run the migration preflight, capture Flyway history and
DDL output, then apply and rerun the metadata smoke test with the deployment's approved account.
No endpoint, username, password, tenant, clinic, or patient value belongs in source, CI logs, or
metrics. Until that endpoint evidence is attached to the rollout record, production MySQL
migration verification remains `PENDING`.

Replay is a library boundary until the application supplies an authenticated
`AppointmentReplayActor`. The actor must match the approver, contain the requested tenant and
clinic allow-list, and hold `APPOINTMENT_REPLAY_OPERATOR`; unauthorized requests are rejected
before an audit row is written. `KafkaAppointmentReplaySource` is bound to a fixed logical
consumer/stream identity, optionally one partition, and the decoded tenant/clinic scope. It
uses a request-only consumer group and bounded range/record/time limits without changing the
operations group offset.
