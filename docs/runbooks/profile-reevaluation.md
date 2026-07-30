# Profile Change Reservation Reevaluation Runbook

This runbook operates the bounded reevaluation of `PROPOSED` and `HELD`
reservations after a material CRM profile change. `CONFIRMED` reservations are
never changed automatically. The CRM owns the patient profile and the current
scheduling assessment; the reservation service stores only opaque references,
fingerprints, hashes, queue state, outcome classes, and minimal audit data.

<a id="ownership"></a>
## Ownership and safety boundary

| Area | Owner | First response |
|---|---|---|
| CRM profile, material-change decision, assessment projection | CRM team | Correct the source revision or restore the assessment endpoint |
| Event trust, inbox, quarantine | Integration on-call | Stop untrusted ingress and preserve encrypted evidence |
| Fair dispatch, reservation transaction, outbox | Scheduling on-call | Freeze mutation, inspect backlog and current reservation state |
| Manual redrive approval | Reservation operations administrator | Approve an exact bounded scope after preview |
| Privacy incident | Security and privacy on-call | Stop consumption, preserve evidence, restrict access, coordinate investigation |

The operator endpoint is `/actuator/profileReevaluation`. `SecurityConfig`
requires both the `ADMIN` role and the
`SCOPE_profile-reevaluation:operate` capability. It is not a general
appointment API and must not be exposed through `/api/v2/**`. Every write
request must include `tenantGroupId` and `clinicId`; the clinic must be in the
authenticated principal's allowlist.

<a id="rollout"></a>
## Rollout: disabled to full option B

The only supported order is:

1. Keep `appointment.profile-reevaluation.enabled=false` and
   `appointment.profile-reevaluation.mutation-mode=DISABLED` while schema,
   query-plan, privacy, and alert checks run.
2. Set `enabled=true`, select `DRY_RUN`, and add one clinic to
   `appointment.profile-reevaluation.clinic-allowlist`.
3. Continue only when dry-run parity is stable, quarantine is not repeating,
   and the `HELD`/`PROPOSED` p95 targets are met.
4. Change the mode to `APPLY_PROPOSED`. Observe one complete
   `appointment.profile-reevaluation.proposed-target` window.
5. Change the mode to `APPLY_PROPOSED_AND_HELD`. Observe one complete
   `appointment.profile-reevaluation.held-target` window before expanding the
   clinic allowlist.
6. Add clinics in small batches. Remove a clinic immediately if its failure,
   lease-expiry, or assessment-saturation signal crosses the stop condition.

An empty clinic allowlist means no clinic is eligible. It never means all
clinics. Keep these platform defaults unless a measured capacity review
justifies a change:

- `appointment.profile-reevaluation.held-target=5m`
- `appointment.profile-reevaluation.proposed-target=30m`
- `appointment.profile-reevaluation.auto-redrive-max=2`
- `appointment.profile-reevaluation.auto-redrive-cooldown=30m`

Each hospital can override the `HELD` and `PROPOSED` target through its
scheduling policy. The effective target is resolved clinic, tenant, then
platform; an already-created job is never postponed by a later, slower target.

Read the current operational snapshot before and after every rollout change:

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/actuator/profileReevaluation
```

Healthy continuation criteria:

- `drainState` is `ACTIVE` while enabled and becomes `DRAINED` after rollback.
- `consecutiveAssessmentFailures=0` and `leaseRenewalFailures=0`.
- oldest backlog stays below the effective target.
- `clinic.profile.reevaluation.dryrun.parity{result="different"}` does not
  increase during the observation window.
- `CONFIRMED` mutation count and duplicate active allocation count remain zero.

Stop rollout and move to `DRY_RUN` or `DISABLED` when any critical alert fires,
privacy evidence is suspected, or unexplained parity differences persist.

<a id="slo-burn"></a>
## SLO burn

The targets are queue objectives, not a completion promise for every job.
Evaluate `HELD` and `PROPOSED` separately with
`clinic.profile.reevaluation.fair.wait` and
`clinic.profile.reevaluation.processing.duration`.

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(clinic_profile_reevaluation_fair_wait_seconds_bucket{priority_class="held_present"}[10m])
  )
)
```

The histogram records event-to-first-claim wait once per job and uses only the
closed `priority_class` tag. Continue when p95 is below the effective target
and no clinic is starved.
The dispatcher carries a clinic keyset cursor across ticks and wraps at the end,
so equal due times do not repeatedly favor lower clinic IDs. Each selected
clinic uses one `LIMIT 1` keyset lookup plus bounded `PENDING`, `RETRY_WAIT`, and
expired-lease queries. The configured global concurrency cap of 64 therefore
bounds poll query count independently of patient backlog size.
At 80% target consumption for 10 minutes, pause allowlist expansion. At 100%
for 10 minutes, return to `DRY_RUN`, preserve jobs, and inspect database,
worker, and assessment latency before resuming.

<a id="oldest-job"></a>
## Oldest job and backlog

The health endpoint reports `oldestBacklogAgeSeconds`. Use the database query
to identify bounded operational scope; do not copy fingerprints or assessment
references into tickets.

The `health_profile_reevaluation_oldest_backlog_age_seconds` series used by the
default alert contract is not a native application meter. Install that rule
only when the deployment converts the aggregate
`/actuator/health/profileReevaluation` detail into the named series.

```sql
SELECT id, tenant_group_id, clinic_id, target_revision, status,
       priority_class, due_at, next_attempt_at, attempt_count,
       redrive_count, last_failure_code
FROM scheduling_profile_reevaluation_jobs
WHERE status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
ORDER BY due_at, id
LIMIT 100;
```

Pause expansion when the oldest job reaches 80% of its target. Disable mutation
when it reaches the target or the backlog grows for three consecutive polling
windows. Preserve the rows; do not reset cursors or attempt counts.

<a id="failed-jobs"></a>
## Failed jobs

```sql
SELECT id, tenant_group_id, clinic_id, target_revision, attempt_count,
       redrive_count, redrive_generation, last_failure_code, updated_at
FROM scheduling_profile_reevaluation_jobs
WHERE status = 'FAILED'
ORDER BY updated_at, id
LIMIT 100;
```

Classify the failure before redrive. Authentication, trust, tenant/clinic
scope, schema, or privacy failures are terminal investigation paths. A
transient CRM or database failure may be redriven only after the dependency is
healthy, the cooldown has elapsed, and preview returns the intended bounded
scope.

Worker failures retain bounded diagnostic codes. `PROCESSING_DATABASE_FAILED`
is retryable. `PROCESSING_CONTRACT_FAILED`, `PROCESSING_STATE_FAILED`, and
`PROCESSING_UNEXPECTED_FAILED` are terminal and require code or data-contract
investigation. Logs include only job ID, revision, failure code, and exception
type; they must not include exception messages or profile data.

<a id="lease-expiry"></a>
## Lease expiry

`clinic.profile.reevaluation.operational{result="lease_lost"}` must normally be
zero. A single loss requires checking process restarts, GC pauses, DB clock,
and transaction duration. More than three losses in ten minutes is critical:
set `appointment.profile-reevaluation.mutation-mode=DISABLED`, wait for
`drainState=DRAINED`, and inspect expired owners. Never extend a lease or change
its owner with SQL.

<a id="assessment-saturation"></a>
## Assessment saturation and CRM dependency

Use `clinic.profile.assessment.inflight`,
`clinic.profile.assessment.requests{result="saturated"}`, and
`clinic.profile.reevaluation.assessment.latency`. Saturation for five minutes
blocks allowlist expansion. Sustained saturation or five consecutive failures
degrades health and requires `DRY_RUN` or `DISABLED`.

The client permits only HTTPS, a fixed host allowlist, public addresses, no
redirect, bounded response bytes, and a strict assessment schema. Do not bypass
these controls to recover throughput.

<a id="quarantine"></a>
## Repeated quarantine

Repeated quarantine for the same reason code means ingress is unsafe. Disable
the consumer path, preserve the encrypted envelope and append-only audit, and
compare producer, signature, issuer, audience, payload hash, schema version,
replay window, and tenant/clinic scope. Do not release quarantined profile
events through the failed-job redrive endpoint.

An envelope that exceeds the metadata or payload contract is rejected before
canonicalization and encryption to prevent resource amplification. It is not
written to quarantine; investigate the producer from bounded ingress metrics
and transport logs instead of retaining the oversized body.

<a id="redrive"></a>
## Bounded failed-job redrive

Preview first with the mandatory tenant and clinic scope. Add a revision when
the recovery approval targets one exact profile revision:

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/actuator/profileReevaluation \
  -d '{"action":"PREVIEW","reason":"CRM dependency restored","idempotencyKey":"reeval-preview-20260730-01","tenantGroupId":1,"clinicId":101,"targetRevision":42,"limit":50}'
```

Execute only when every preview row matches the approved scope:

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/actuator/profileReevaluation \
  -d '{"action":"EXECUTE","reason":"CRM dependency restored","idempotencyKey":"reeval-execute-20260730-01","tenantGroupId":1,"clinicId":101,"targetRevision":42,"limit":50}'
```

The audit actor is derived from the authenticated admin token. The request body
cannot override it. A tenant-wide or cross-clinic redrive is not supported by
this endpoint.

The service creates a new lineage attempt; it does not rewrite the failed row.
The same idempotency key is replay-safe only for the same request. Automatic
redrive is capped by `appointment.profile-reevaluation.auto-redrive-max` and
stops after the configured limit. Further recovery always requires a new
preview and an operator decision.

<a id="privacy-incident"></a>
## Privacy incident

If raw profile data, clinical detail, a feature, score, explanation, or a
reversible patient identifier appears in an event, table, log, metric, health
detail, outbox, or ticket:

1. Set the mode to `DISABLED`, remove affected clinics from the allowlist, and
   stop the profile event consumer.
2. Preserve the original database, log, encrypted quarantine, deployment, and
   configuration evidence under legal and security access control.
3. Do not paste the value into chat, an issue, a dashboard label, or a redrive
   reason.
4. Notify security/privacy on-call and the CRM owner. Determine where the
   disallowed value first crossed the boundary.
5. Resume only after containment, deletion/retention decisions, secret or key
   rotation where applicable, a clean privacy integration test, and written
   incident approval.

The reservation team investigates its persistence boundary. The CRM team
remains responsible for profile correction and assessment contents.

<a id="rollback"></a>
## Rollback and invariant checks

Rollback stops new mutation; it does not reverse completed valid reservation
transactions:

1. Set `mutation-mode=DISABLED` or `enabled=false`.
2. Remove clinics from `clinic-allowlist`.
3. Wait until the health snapshot reports `drainState=DRAINED` and
   `activeLeases=0`.
4. Verify that no `CONFIRMED` reservation changed and that pre-existing valid
   `HELD` allocations remain active unless their own atomic replacement
   transaction completed successfully.
5. Keep `scheduling_profile_reevaluation_jobs`, outcomes, inbox, quarantine,
   outbox, and audit rows. Do not drop V13 schema or delete failed jobs.
6. Resume with `DRY_RUN` and the smallest clinic allowlist.

```sql
SELECT commitment_status, COUNT(*) AS outcome_count
FROM scheduling_appointment_commitments
GROUP BY commitment_status
ORDER BY commitment_status;
```

Compare this read-only snapshot and active allocation uniqueness evidence from
immediately before rollout. Any unexplained `CONFIRMED` change or lost
pre-existing `HELD` allocation is a release blocker and incident.

<a id="unsupported"></a>
## Unsupported behavior

- Automatic modification, cancellation, or replacement of `CONFIRMED`
  reservations is not supported.
- Persisting the raw profile, objective feature values, score, explanation,
  correction detail, or CRM response body is not supported.
- The 5 minutes and 30 minutes values are p95 queue objectives, not a guarantee
  that every individual job completes within those times.
- Unattended redrive after the automatic retry/redrive limit is exhausted is
  not supported.
- Direct SQL status rewrites, cursor resets, lease-owner edits, and widening a
  redrive selector to “all failed” are not supported.

## Metric inventory

All labels are low-cardinality enums. Tenant, clinic, patient, appointment,
event, and correlation identifiers are forbidden as labels.

- `clinic.profile.reevaluation.events`
- `clinic.profile.reevaluation.jobs`
- `clinic.profile.reevaluation.outcomes`
- `clinic.profile.reevaluation.fair.wait`
- `clinic.profile.reevaluation.processing.duration`
- `clinic.profile.reevaluation.assessment.latency`
- `clinic.profile.reevaluation.operational`
- `clinic.profile.reevaluation.dryrun.parity`
- `clinic.profile.assessment.inflight`
- `clinic.profile.assessment.requests`

The operational result distinguishes `defer` from `retry`: `defer` means the
runtime gate or bounded tick postponed otherwise valid work, while `retry`
means a technical processing failure consumed retry policy.

The deployable default alerts are in
[`profile-reevaluation-alerts.yml`](profile-reevaluation-alerts.yml).
