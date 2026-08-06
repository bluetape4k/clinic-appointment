# appointment-notification

[English](README.md) | [한국어](README.ko.md)

Durable notification delivery runtime for committed appointment outbox records.
It claims work with database leases, resolves the member's current notification
profile at send time, renders a versioned template, and isolates provider calls
with bounded Resilience4j policies.

## Responsibilities

- **Does**: fair database claiming, lease recovery and fencing, send-time member
  profile resolution, typed template rendering, provider isolation, terminal
  data minimization, and bounded retention.
- **Does not**: perform appointment CRUD or persist names, contact details,
  rendered bodies, provider payloads, or raw exception messages in the outbox.
  During rollout, the transitional Spring event listener may claim the exact
  outbox row and execute the same privacy-safe delivery pipeline; it is not an
  independent raw delivery or history path.

## Core Classes

| Class | Role |
|---|---|
| `NotificationOutboxDispatcher` | Claims ready records fairly and enforces global and per-clinic concurrency. |
| `NotificationOutboxSchedulingRunner` | Runs the dispatcher after application readiness and at the worker interval. |
| `NotificationObservationSchedulingRunner` | Refreshes a bounded observation snapshot at a lower frequency independent of worker polling. |
| `NotificationOutboxWorker` | Applies fenced completion, retry, exhaustion, and expired-lease recovery. |
| `NotificationOutboxWorkStore` | Defines the transactional database boundary for outbox work. |
| `NotificationDeliveryRouteGate` | Maps `SHADOW`, `CANARY`, `ACTIVE`, and `PAUSED` to one provider route per clinic. |
| `NotificationDirectOutboxDelivery` | Lets the transitional event route conditionally claim the exact outbox row. |
| `MemberNotificationProfileResolver` | Resolves current contact, locale, and consent within bounded runtime policies. |
| `NotificationTemplateCatalog` | Owns supported template keys, versions, and channel-specific definitions. |
| `NotificationTemplateRenderer` | Renders typed parameters and runtime profile data with fail-closed validation. |
| `NotificationChannel` | Sends a provider-ready request and returns a privacy-safe result. |
| `ResilientNotificationChannel` | Applies CircuitBreaker, Retry, and Bulkhead without retrying coroutine cancellation. |
| `NotificationRetentionRunner` | Deletes terminal records in bounded pages using status-specific retention. |
| `NotificationSchemaReadiness` | Fails readiness when required schema, indexes, or crypto references are unavailable. |

## Delivery Flow

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../docs/requirements/assets/data-flow-05-notification-events-en-dark.png">
  <img src="../docs/requirements/assets/data-flow-05-notification-events-en.png" alt="Durable notification outbox route and privacy boundary">
</picture>

1. The appointment transaction commits a minimal outbox record containing
   member and appointment identifiers plus typed template parameters.
2. The dispatcher finds fair candidates and claims each record with a database
   lease and fencing token.
3. The delivery adapter resolves the member's current contact, locale, and
   consent. Missing contact or withdrawn consent is suppressed without sending.
4. The renderer selects an approved template version and produces the
   provider-ready body in memory.
5. The channel sends with a deterministic provider idempotency key.
6. The worker records only a fenced terminal result or a bounded retry
   decision. Terminal rows are later removed by the retention runner.

The database lease and fencing token are the delivery correctness boundary.
Redis leader election is reserved for a future reminder-recovery trigger; it is
not required for safe concurrent outbox delivery.

### Kafka appointment-event consumer (Issue #42)

When `appointment.messaging.consumer.enabled=true`, this module also exposes a
Kafka 4 manual-ack listener for the allow-listed appointment topic. It uses the
fixed group `appointment-notification-v1` and logical inbox identity
`notification/appointment-events`; the tenant/clinic scope is taken from the
validated envelope and stored as metadata only. A duplicate inbox key is
acknowledged without a second provider call, while retryable handler failures
leave the record unacknowledged for bounded redelivery. The listener delegates
to the existing `NotificationDirectDeliveryPort`, so it does not create a
second delivery or raw-payload history path.

```yaml
appointment:
  messaging:
    consumer:
      enabled: true
      topic: clinic.appointment.events
      max-attempts: 8
```

Statistics projection is a separate consumer group (`appointment-statistics-v1`)
and is enabled independently with `appointment.messaging.consumer.statistics.enabled=true`.
Both consumers use the same Kafka 4 manual-ack container configuration but never
share a group or inbox identity.

### Rollout routes

| Mode | Transitional event route | Background worker route |
|---|---|---|
| `SHADOW` (default) | All clinics | Disabled |
| `CANARY` | Clinics outside the allowlist | Allowlisted clinics only |
| `ACTIVE` | Disabled | All clinics |
| `PAUSED` | Disabled | Disabled |

Every route must conditionally claim the same database row before invoking a
provider. `PAUSED` stops provider calls but keeps enqueue, recovery, and
retention active. Production canary activation is intentionally tracked outside
the code-transition PR; see the operations runbook before changing the mode.

## Privacy and Reliability Boundaries

- Contact details and consent remain owned by the member service and are
  resolved immediately before sending.
- Template parameters are sealed domain types, not arbitrary maps or stored
  rendered text.
- Runtime objects redact their string representation, and persisted failures use
  stable codes instead of provider messages or stack traces.
- Retry count, elapsed time, provider attempts per lease, lease duration, and
  concurrency are validated as one bounded configuration.
- Coroutine cancellation is propagated after one provider invocation and is
  never converted into a provider failure.

## Configuration Example

```yaml
clinic:
  notification:
    enabled: true
    crypto:
      active:
        key-id: notification-2026-q3
        secret-reference: env:CLINIC_NOTIFICATION_HMAC_KEY
        activated-at: 2026-07-01T00:00:00Z
        expires-at: 2030-01-01T00:00:00Z
    rollout:
      mode: SHADOW
      canary-scopes: []
      # Deprecated rolling bridge; use the same clinic set when present.
      canary-clinic-ids: []
    worker:
      enabled: true
      max-attempts: 6
      max-elapsed: 24h
      provider-attempts-per-lease: 1
      catch-up-window: 30m
      lease-duration: 60s
      provider-timeout: 30s
      poll-interval: 1s
      batch-size: 100
      global-concurrency: 4
      per-clinic-concurrency: 1
      db-claim-max-concurrency: 4
      member-resolver-max-concurrency: 4
      member-resolver-timeout: 5s
      member-resolver-rate-limit-per-second: 100
      member-resolver-circuit-breaker-failure-rate-threshold: 50
      channels:
        dummy:
          provider-max-concurrency: 4
          bulkhead-max-concurrent-calls: 4

`canary-scopes` is the authoritative `tenant-group-id`/`clinic-id` pair list for
`CANARY`; the clinic-only property is a deprecated bridge for old nodes and must
have the same clinic set. Direct delivery claims and permits use the same pair,
and `1:23` is intentionally different from `12:3`. During V21 rollout,
`NotificationSchemaReadiness` requires Flyway V21, the event-log tenant column,
and the tenant-leading direct lookup index before background traffic starts.
          provider-timeout: 30s
          rate-limit-per-second: 100
          circuit-breaker-failure-rate-threshold: 50
    observation:
      poll-interval: 10s
      limit: 10001
    retention:
      poll-interval: 1h
      sent: 7d
      suppressed: 7d
      exhausted: 30d
      page-size: 100
      max-pages-per-status: 10
      backpressure: 100ms
    resilience:
      circuit-breaker:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
      retry:
        max-attempts: 3
        wait-duration: 1s
      bulkhead:
        max-concurrent-calls: 10
```

The worker configuration is rejected at startup when a lease cannot cover the
bounded in-process provider call or when worker concurrency exceeds database,
member resolver, or provider capacity. A configured
`channels.<lowercase-channel-type>.provider-timeout` overrides the global
`worker.provider-timeout` for that channel; otherwise the global value applies.
`provider-timeout` bounds the actual
provider-call future; an overrun is cancelled and mapped to the stable
`PROVIDER_UNAVAILABLE` failure. Provider adapters must also configure native
connect/read/request timeouts no greater than this value; an SDK that ignores
interrupts must terminate through its own timeout. A missing or invalid active crypto key reference
makes notification readiness DOWN and blocks worker processing. Store only an
external secret location in `secret-reference`, never key material.

## Dependencies

- **Internal**: `appointment-core`, `appointment-event`
- **External**: Exposed JDBC, Resilience4j, Lettuce, and `bluetape4k-leader`

## Tests

```bash
./gradlew :appointment-notification:test
```

## Design Documents

- [Durable Notification Outbox Design](../docs/superpowers/specs/2026-07-31-issue-172-notification-outbox-design.md)
- [Implementation Plan](../docs/superpowers/plans/2026-07-31-issue-172-notification-outbox-plan.md)
- [Operations Runbook](../docs/runbooks/notification-outbox-operations.md)
- [Notification Data Flow](../docs/requirements/data-flow.md#5-알림-outbox-발송-흐름)

## Waitlist offer notification

Waitlist offer delivery uses the same durable notification lifecycle as other
channels. The worker claims a bounded row, resolves the current member profile,
performs a final offer-expiry CAS immediately before provider I/O, and records
the result with another fenced update. Provider latency is never held inside an
appointment transaction. An expired or terminal offer is suppressed; an unknown
provider result is manual-review state and never accepts or revives an offer.

Rollout is controlled by `appointment.waitlist.delivery.enabled` and the optional
`clinic-allowlist`. The default is global-off, while expiry, suppression, and
stuck-hold recovery remain active. See the [waitlist delivery API and operations
contract](../docs/api/waitlist-delivery.md).
