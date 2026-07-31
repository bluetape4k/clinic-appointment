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
- **Does not**: deliver directly from Spring appointment events, perform
  appointment CRUD, or persist names, contact details, rendered bodies, provider
  payloads, or raw exception messages in the outbox.

## Core Classes

| Class | Role |
|---|---|
| `NotificationOutboxDispatcher` | Claims ready records fairly and enforces global and per-clinic concurrency. |
| `NotificationOutboxWorker` | Applies fenced completion, retry, exhaustion, and expired-lease recovery. |
| `NotificationOutboxWorkStore` | Defines the transactional database boundary for outbox work. |
| `MemberNotificationProfileResolver` | Resolves current contact, locale, and consent within bounded runtime policies. |
| `NotificationTemplateCatalog` | Owns supported template keys, versions, and channel-specific definitions. |
| `NotificationTemplateRenderer` | Renders typed parameters and runtime profile data with fail-closed validation. |
| `NotificationChannel` | Sends a provider-ready request and returns a privacy-safe result. |
| `ResilientNotificationChannel` | Applies CircuitBreaker, Retry, and Bulkhead without retrying coroutine cancellation. |
| `NotificationRetentionRunner` | Deletes terminal records in bounded pages using status-specific retention. |
| `NotificationSchemaReadiness` | Fails readiness when required schema, indexes, or crypto references are unavailable. |

## Delivery Flow

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
    worker:
      enabled: true
      max-attempts: 6
      max-elapsed: 24h
      provider-attempts-per-lease: 1
      catch-up-window: 30m
      lease-duration: 60s
      provider-timeout: 30s
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
          provider-timeout: 30s
          rate-limit-per-second: 100
          circuit-breaker-failure-rate-threshold: 50
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
member resolver, or provider capacity.

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
