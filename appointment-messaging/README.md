# appointment-messaging

`appointment-messaging` owns the Kafka 4 transactional-outbox delivery path for the
legacy appointment mutation stream.

## Contract

- `AppointmentOutboxWriter` is called inside the caller's Exposed `transaction {}`.
- The aggregate and the `scheduling_outbox_events` intent commit or roll back together.
- `AppointmentOutboxRelay` performs Kafka I/O outside a database transaction and uses a
  lease owner/token fence for terminal updates.
- Delivery is at-least-once. A broker acknowledgement followed by a database failure can
  publish the same immutable `eventId` again.
- The current stream is partial: create/status/cancel and final reschedule mutations are
  covered. Commitment-v2 controllers and the closure `PENDING_RESCHEDULE` transition are
  intentionally outside Issue #41.

## Installation

```kotlin
implementation(project(":appointment-messaging"))
```

Spring Boot discovers `AppointmentMessagingAutoConfiguration` from
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
The default topic is `clinic.appointment.events`; only explicitly allow-listed topics are
accepted. Invalid lease, timeout, claim, or topic settings fail before a writer is built.

The external binding prefix is `appointment.messaging`:

```yaml
appointment:
  messaging:
    topic: clinic.appointment.events
    allowed-topics: [clinic.appointment.events]
    enabled: true
    kafka-client-retry-budget: 5s
    producer-acks: all
    producer-enable-idempotence: true
    producer-allow-auto-create-topics: false
    producer-request-timeout: 5s
    producer-delivery-timeout: 15s
    producer-metadata-timeout: 5s
    producer-security-protocol: SSL
    producer-credential-reference: secret://kafka/appointment-producer
```

`PLAINTEXT` is intended only for local development. Production must use `SSL` or
`SASL_SSL` and provide a secret-manager reference; credential values are never stored in
the outbox or emitted in logs. The producer contract is fail-closed at `acks=all`, idempotence,
auto-create disabled, and bounded request/delivery timeouts. The broker must also set
`auto.create.topics.enable=false`; Kafka producer metadata has no client-side override for that
broker policy. The application disables Spring Kafka admin topic creation and uses its
non-creating describe path before the relay is created. For `SSL`/`SASL_SSL`, the application must
provide an `AppointmentKafkaCredentialResolver` bean; it resolves the reference into only
`ssl.*`/`sasl.*` client properties without exposing the secret value to logs or the outbox.
When the Spring Kafka publisher is active, the relay uses the required `KafkaAdmin` non-creating
metadata path before claiming any outbox rows. The probe is bounded by
`producer-metadata-timeout` and checks every allow-listed topic before claiming. This keeps
missing topics and ACL/TLS/SASL failures from turning into lease churn. Custom publishers must
implement the readiness contract; a publisher without it is fail-closed and cannot claim rows.
Readiness also exposes `enabled`, `schemaValid`, and `serializerValid`; the relay remains
not-ready until the V22 columns/indexes and codec self-check pass.
When Spring Boot Actuator is present, the module registers the
`appointmentMessagingHealthIndicator` health component. Broker outage, operator pause, or a
held relay reports `OUT_OF_SERVICE` for readiness while application liveness remains independent;
invalid configuration, schema, or serializer state reports `DOWN`. Configure the Actuator
readiness group to include this component (for example,
`management.endpoint.health.group.readiness.include=readinessState,appointmentMessagingHealthIndicator`).
The health details contain only bounded readiness booleans.

Micrometer integration publishes low-cardinality `appointment_outbox_pending`,
`appointment_outbox_oldest_age_seconds`, and `appointment_outbox_partition_skew` gauges alongside
publish/retry/failure counters. No tenant, clinic, appointment, partition key, payload, or credential
value is used as a metric label.

An HTTP `2xx` means the aggregate and durable outbox intent committed. It does not mean
Kafka acknowledgement; broker outage leaves the row `PENDING` for the relay.

## Operations

The relay must be paused and held before schema rollback or manual redrive. Release the hold
only after the V22 schema/index and broker readiness checks pass. See
`docs/runbooks/appointment-messaging-operations.md` and the alert rules in
`docs/alerts/appointment-messaging-rules.yml`.

Run the focused module checks with:

```bash
./gradlew :appointment-messaging:test
```
