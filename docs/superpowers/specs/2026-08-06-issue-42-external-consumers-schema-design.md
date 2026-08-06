# Issue #42 외부 시스템 구독 컨슈머와 이벤트 스키마 버전 관리 설계

## 목적

Issue #41이 생성한 Kafka 4 transactional outbox envelope을 알림과 통계 시스템이
독립적으로 구독하도록 한다. 소비는 at-least-once를 전제로 하며, 같은 이벤트가
재전달되어도 tenant/clinic 범위에서 부작용이 한 번만 적용되도록 consumer inbox를
둔다. 동시에 현재 Jackson 3 JSON wire 계약을 유지하면서 JSON Schema와 Schema
Registry 호환성 정책을 명시한다.

## 현재 상태와 범위

- `appointment-messaging`은 `AppointmentEventEnvelope`와 `schemaVersion=1` strict
  codec, Kafka 4 producer, transactional outbox relay를 이미 제공한다.
- `appointment-notification`은 legacy Spring `ApplicationEvent`를 durable
  notification outbox로 연결하는 `NotificationDirectDeliveryPort`를 제공한다.
- `appointment-api`의 dashboard 통계는 예약 테이블을 직접 집계한다. Issue #42에서는
  동일 이벤트를 받아 tenant-aware 통계 projection을 누적하고, projection이 준비된
  범위에서는 dashboard가 이를 읽을 수 있게 한다.
- 모든 DB 변경은 API가 소유하는 Flyway `V23` migration으로 H2/MySQL/PostgreSQL에
  동일하게 반영한다. 기존 `scheduling_*` 이름과 V22 migration은 변경하지 않는다.
- Kafka 4만 지원한다. Kafka 3, RabbitMQ, broker-neutral abstraction은 이 이슈의
  범위에서 제외한다.

## 선택한 접근: JSON Schema + bounded consumer runtime

현재 payload가 JSON이고 필드 allow-list/중첩 깊이/문자열 길이를 이미 Jackson 3
codec에서 제한하므로 Avro code generation으로 wire를 바꾸지 않는다. 대신 다음을
조합한다.

1. `appointment-event-envelope-v1.schema.json`을 source-of-truth로 둔다.
2. `AppointmentSchemaRegistry` 포트가 local schema version을 검증하고, endpoint가
   설정된 배포에서는 Schema Registry의 subject compatibility가
   `BACKWARD_TRANSITIVE`인지 startup/readiness에서 확인한다. 등록은 CI/운영 registry
   작업으로 수행하며 앱이 topic이나 schema를 자동 생성하지 않는다.
3. codec은 기존 strict JSON 검증을 계속 수행하고 schema registry 검증 실패는
   fail-closed로 처리한다. 알 수 없는 schema version, event type, field는 retry하지
   않고 metadata-only quarantine으로 보낸다.
4. 소비자별 handler는 `appointment-messaging`의 공통 runtime을 사용하지만, 알림과
   통계는 서로 다른 `logicalConsumerId`와 Kafka group을 갖는다.

Avro 전환은 code generation과 serializer/header 변경이 필요하고, 이미 배포된 JSON
 1 envelope과의 rolling compatibility를 깨므로 기각했다. local schema 파일만 두고
  registry를 확인하지 않는 방식은 compatibility 사고를 startup에서 발견하지 못하므로
  기각했다.

## 컴포넌트와 경계

### 1. `appointment-messaging`

- `AppointmentConsumerProperties`: topic, group/consumer identity, max attempts,
  retry backoff, quarantine topic, schema subject/registry URL, enabled flag를
  immutable constructor-bound 설정으로 보유한다. consumer는 명시적으로 enabled일
  때만 생성한다.
- `AppointmentConsumerInboxTable`과 `JdbcAppointmentConsumerInboxStore`:
  `logicalConsumerId`, `logicalStreamId`, `eventId`를 복합 primary key로 사용한다.
  topic/partition/offset/schemaVersion/tenant/clinic과 payload SHA-256, 상태, attempt,
  timestamps만 저장하고 raw value/PII는 저장하지 않는다. insert-if-absent가 성공하면
  NEW, 이미 terminal 처리된 행이면 DUPLICATE를 반환한다.
- `AppointmentConsumerRuntime`: record key와 envelope scope를 검증하고 schema를
  확인한 뒤 inbox를 선점한다. handler transaction이 성공하면 processed를 기록하고
  listener ack를 허용한다. 일시 실패는 bounded retry 예외로 다시 전달하고, schema,
  scope, attempt 소진은 quarantine 상태로 terminal 처리한다.
- `AppointmentConsumerQuarantinePublisher`: DLT/quarantine payload는 eventId hash,
  consumer/stream, topic/partition/offset, schemaVersion, failure code만 포함한다.
  원문 JSON을 DLT에 복사하지 않는다.
- `AppointmentKafkaConsumerConfiguration`: 기존 Spring Kafka 4 `ConsumerFactory`를
  사용해 manual ack container factory를 제공한다. auto-create topic은 금지하고,
  handler가 없는 상태에서는 consumer bean을 만들지 않는다.
- `AppointmentSchemaRegistry` 구현체는 local static validator와 선택적 HTTP registry
  readiness client로 나눈다. registry가 설정된 운영 환경은 호환성 확인 실패 시
  readiness를 false로 유지한다.

### 2. 알림 consumer (`appointment-notification`)

`NotificationAppointmentEventConsumer`는 `NotificationDirectDeliveryPort`만 호출한다.
`CREATED`, `CANCELLED`, `RESCHEDULED`, `STATUS_CHANGED(toState=CONFIRMED)`를 각각
기존 notification event type으로 매핑하며, recipient/provider payload를 읽거나
provider를 직접 호출하지 않는다. outbox worker와 기존 lease/fencing이 실제 전달을
담당한다. group은 `appointment-notification-v1`, logical stream은
`appointment-events`로 고정한다.

### 3. 통계 consumer (`appointment-api`)

`AppointmentStatsProjectionConsumer`는 event payload의 appointment id/status/version과
tenant/clinic scope만 사용해 `scheduling_appointment_stats_projection`을 갱신한다.
동일 aggregate의 낮은 version은 무시하고, 같은 event id는 inbox에서 제거한다.
projection repository는 모든 SQL을 `transaction {}` 안에서 실행하며, scope 없는
조회는 제공하지 않는다. dashboard service는 projection에 행이 있는 기간을
projection에서 읽고, migration 직후의 기존 데이터/빈 기간은 기존
`AppointmentStatsRepository` 집계로 fallback한다. 따라서 rolling deployment에서
기존 API 응답을 잃지 않는다. group은 `appointment-statistics-v1`이다.

## 데이터 흐름

```text
Kafka topic
  -> manual-ack listener
  -> strict codec + JSON Schema registry gate
  -> (consumerId, streamId, eventId) inbox insert-if-absent
  -> handler DB transaction / existing side-effect ledger
  -> inbox processed update
  -> Kafka ack

decode/scope/schema failure -> metadata-only quarantine + ack
handler transient failure   -> bounded container retry
retry exhausted              -> inbox quarantine + metadata-only quarantine record + ack
approved replay               -> separate replay group, dry-run/audit first, never rewind ops group
```

## 실패·보안·운영 계약

- tenantGroupId와 clinicId는 envelope와 handler의 동일 scope를 반드시 만족해야 한다.
  mismatch는 retry하지 않는 `SCOPE_MISMATCH`이다.
- consumer inbox의 unique key에는 topic/partition/offset을 넣지 않는다. 이 값은
  provenance로만 보존해 topic/partition migration 뒤에도 event idempotency를 유지한다.
- metric label, log, quarantine, replay audit에는 patient/PII 또는 raw payload를 넣지
  않는다. payload는 SHA-256만 저장한다.
- topic과 group은 allow-list/설정으로 고정하고 application auto-create를 금지한다.
- replay는 운영 group과 분리된 group에서만 허용한다. 요청에는 승인자, consumer,
  event scope/offset 범위, dry-run 여부가 필요하며 audit row를 먼저 기록한다.
  dry-run이 통과하지 않으면 side effect를 실행하지 않는다.
- inbox retention/cleanup은 processed/quarantined 행의 bounded age와 batch size를
  설정으로 제한한다. cleanup은 active processing 행을 건드리지 않는다.
- readiness는 codec, local schema, registry compatibility, DB inbox schema,
  broker/topic authorization을 모두 확인한다.

## 검증 전략과 완료 조건

- codec/schema: v1 round-trip, unknown field/type/version 거부, JSON Schema resource
  필드 parity, registry compatibility/readiness 실패 테스트.
- inbox: 신규 insert, duplicate, scope mismatch, processed/retry/quarantine 상태,
  tenant isolation, provenance 저장, concurrent duplicate lookup 테스트.
- consumer runtime: ack 순서, handler 실패 시 offset 미확정, retry exhaustion,
  metadata-only quarantine, replay dry-run/audit 테스트.
- notification: 네 가지 event mapping, confirmed 외 status 무시, 기존 durable
  outbox/worker만 호출하는 테스트.
- statistics: tenant/date/status/version monotonic upsert, duplicate 무시,
  projection-to-dashboard와 fallback 테스트.
- Kafka: repository singleton Kafka 4 launcher를 사용한 manual-ack contract smoke
  test를 추가한다. `@Testcontainers`는 사용하지 않는다.
- DB: V23 migration과 Exposed table contract를 H2, MySQL, PostgreSQL에 대해
  migration test로 확인한다.
- benchmark: 기존 `benchmark/appointment-messaging-benchmark`의 kotlinx-benchmark
  PostgreSQL 흐름에 duplicate lookup p95와 bounded cleanup 측정을 추가한다. 결과에는
  command, DB/version, rows, warm-up/iterations, p50/p95, caveat를 기록한다.
- 문서: `appointment-messaging/README.md`와 `.ko.md`, notification/api 운영 note,
  schema evolution/replay runbook을 source-equivalent로 갱신한다. README에 새
  시각 chart를 추가하지 않으며, 수치 표는 benchmark artifact와 재현 명령을 함께
  가리킨다.

## DoD

1. Kafka 4 알림/통계 consumers가 서로 독립된 group과 tenant-aware inbox를 사용한다.
2. JSON Schema v1 resource와 `BACKWARD_TRANSITIVE` registry compatibility gate가
   readiness에 포함된다.
3. 중복/재시도/DLT-quarantine/replay dry-run 경로가 raw payload 없이 동작한다.
4. H2/MySQL/PostgreSQL V23 migration, Exposed transaction 경계, 테스트와
   kotlinx-benchmark 결과가 통과한다.
5. EN/KO README와 운영 runbook이 구현과 일치한다.
6. Issue #42 metadata를 미러링한 English PR이 CI를 통과하고, fresh merge approval
   이후 merge 및 local develop/worktree sync-cleanup이 완료된다.

## 참고

- [Issue #42](https://github.com/bluetape4k/clinic-appointment/issues/42)
- [Issue #17](https://github.com/bluetape4k/clinic-appointment/issues/17)
- [Architecture requirements](../../requirements/architecture.md)
- [Issue #40 messaging decision](2026-08-03-issue-40-kafka4-messaging-decision-design.md)
- [Issue #41 transactional outbox design](2026-08-05-issue-41-transactional-outbox-messaging-design.md)
- [Confluent Schema Registry compatibility](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)
