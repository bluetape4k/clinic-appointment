# Waitlist delivery runbook

## Preconditions

1. Verify the Flyway version and additive V19 tables on the target dialect.
2. Verify the active clinic policy, adapter/schema readiness, oldest vacancy age,
   failed jobs, unknown deliveries, and Redis leader state.
3. Keep `appointment.waitlist.delivery.enabled=false` until the migration and
   recovery checks are green.

```bash
./gradlew :appointment-api:test --tests "*FlywayMigrationTest" --no-build-cache
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" \
  http://localhost:8080/actuator/health/waitlistDelivery
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" \
  http://localhost:8080/actuator/metrics/appointment_waitlist_oldest_vacancy_seconds
```

The management token must be supplied by the deployment secret store. Never put
it in a shell history, log, issue, or metric label.

## Rollout and rollback

1. Deploy V19 and verify table/constraint/index counts.
2. Run shadow preview and confirm no-mutation decision/audit samples.
3. Add one clinic to `clinic-allowlist`; confirm `UP`, oldest vacancy under two
   minutes, and failed job count zero.
4. Set `enabled=true` and observe dispatch, offer, notification, and suppression
   metrics for one bounded interval.

Rollback is `allowlist removal -> dispatch/new delivery zero -> in-flight DB
   lease expiry -> expiry/suppression/hold-reconcile drain`. Do not delete V19
   rows or downgrade the schema. Re-enable only after failed/unknown rows have an
   operator decision and health returns `UP`.

## Operator actions

- `PROCESSING` with an expired lease is reclaimed by the next leader; a lost Redis
  lease cannot pass the database fence.
- A failed job is requeued only with a version precondition and typed reason.
- An unknown provider result is marked for manual review or suppressed after
  provider evidence; it is never treated as acceptance.
- Expired/terminal offers suppress pending notifications and release holds.
- Retention purges only terminal, unresolved-free rows in bounded batches; active,
  legal-hold, and audit-hold rows are skipped.

## Health and alerts

`UP` requires adapter/schema/policy readiness, oldest vacancy under two minutes,
and no failed jobs. `DEGRADED` starts at provider failure ratio 5%, an oldest
vacancy of 2–5 minutes, or unknown delivery. `OUT_OF_SERVICE` is required for a
missing dependency/policy, oldest vacancy over five minutes, any failed job, or
expired backlog over 100. Alert rules are in
[`docs/alerts/waitlist-delivery.yml`](../alerts/waitlist-delivery.yml).

Record the dialect, Flyway version, metric snapshot, command correlation ID,
operator actor reference, action reason, and post-action health in the incident
ticket. Do not record raw member IDs, phone numbers, JWTs, or provider payloads.
