# Issue #41 예약 transactional outbox 메시징 교훈

## 배경

예약 변경과 Kafka 전달을 같은 호출 경계에서 처리하면 broker 장애가 예약 transaction의
성공 여부를 오염시키고, Spring event listener의 실패가 이미 저장된 aggregate와
엇갈릴 수 있다. Issue #41은 기존 `scheduling_outbox_events`를 재사용해 예약 mutation과
메시징 intent를 같은 Exposed transaction에 기록하고, Kafka4 전달은 별도 relay가
at-least-once로 수행하도록 고정했다.

## 결정

- `AppointmentCreated`, `AppointmentStatusChanged`, `AppointmentCancelled`,
  `AppointmentRescheduled`만 typed envelope로 허용한다. `schemaVersion=1`, bounded
  correlation/causation, tenant-clinic scope와 aggregate identity를 constructor와 codec에서
  함께 검증한다.
- partition key는
  `tenant-{tenantGroupId}:CLINIC:clinic-{clinicId}:APPOINTMENT:apt-{appointmentId}`로
  고정한다. aggregate의 모든 event가 같은 key를 사용해야 순서가 보존된다.
- outbox writer는 caller transaction을 열거나 commit하지 않는다. 예약 row, 상태 이력,
  notification intent와 appointment outbox 중 하나라도 실패하면 전체를 rollback한다.
  2xx는 Kafka ack가 아니라 durable intent commit만 의미한다.
- relay claim과 terminal CAS는 `owner + token + attempt + leaseUntil`로 fencing하며,
  due/lease 판단과 lease timestamp는 worker 시계가 아닌 DB `CURRENT_TIMESTAMP`를 사용한다.
  retry는 `Duration`으로 전달하고 저장소가 같은 DB 시각에서 `next_attempt_at`을 계산한다.
- PostgreSQL ready index는 appointment predicate를 포함한 partial index로 두고, H2/MySQL은
  동일한 discriminator-leading composite index로 맞춘다. 외부 Flyway schema를 재사용하는
  테스트 fixture가 portable Exposed metadata index를 다시 만들지 않도록 fixture 경계를
  분리한다.
- payload, state history reason, event logger와 API log에는 환자 데이터, raw reason,
  tenant/clinic/appointment 식별자를 durable 메시지나 운영 로그로 복제하지 않는다.
  취소 사유는 등록된 bounded reason code만 남긴다.

## 검증에서 드러난 보완점

- application `Clock`을 lease CAS에 사용하면 인스턴스 시계가 서로 다를 때 stale worker가
  늦게 terminal write를 할 수 있다. DB 시계를 transaction 안에서 한 번 읽는 helper로
  모든 claim/publish/retry/fail predicate를 통일해야 한다.
- `next_attempt_at`을 worker가 계산한 `Instant`로 받으면 clock skew가 retry 시점을 앞당길
  수 있다. retry delay를 port로 전달하고 DB transaction 안에서 미래 시각을 계산하면 이
  경계가 사라진다.
- broker metadata를 확인하지 않고 relay가 먼저 claim하면 topic 미생성, ACL, TLS/SASL
  오류를 불필요한 outbox lease churn으로 바꾼다. Kafka publisher가 metadata readiness를
  지원할 때는 모든 allow-list topic을 bounded timeout 안에 probe하고, 성공하기 전에는
  claim을 시작하지 않아야 한다. SSL/SASL 자격 증명은 reference resolver 경계를 통해서만
  producer factory에 주입해야 한다.
- Flyway가 만든 PostgreSQL partial index와 Exposed의 portable index가 같은 이름을
  공유하면 다음 테스트의 `SchemaUtils`가 중복 `CREATE INDEX`를 시도할 수 있다. migration
  계약을 바꾸지 말고 외부 schema가 이미 존재하는 경우에만 테스트 table creation을
  제외한다.
- Jackson map 숫자는 `Number.toLong()`만 사용하면 `3.5`가 `3`으로 조용히 잘릴 수 있다.
  payload integer field는 integral numeric type만 허용하고 fractional input은 거절해야 한다.
- readiness는 현재 JDBC catalog/schema의 V22 column/index와 serializer self-check를 함께
  확인해야 한다. 다른 schema의 동명 table을 성공으로 오인하지 않도록 metadata scope를
  연결의 현재 schema에 고정하고, DataSource가 있는 애플리케이션은 startup에서 fail-fast한다.
- operator pause와 broker circuit pause는 별도 상태여야 한다. 수동 pause를 circuit timer가
  지우면 schema rollback/redrive 중 신규 claim이 다시 시작될 수 있다.

## 재사용 지침

1. aggregate mutation과 outbox intent는 caller-owned transaction에서만 기록한다.
2. broker I/O는 transaction 밖에서 수행하고, Kafka ack 뒤 DB update가 실패해도 같은
   `eventId` 재전송을 허용한다.
3. 모든 lease/retry predicate에 DB clock과 fencing 조건을 반복하고, stale owner의 결과는
   affected row `0`으로 폐기한다.
4. partial/composite index의 dialect 차이를 migration test와 metadata fixture에서 동시에
   검증한다.
5. API/OpenAPI는 성공 의미를 durable intent commit으로 명시하고 비동기 broker delivery를
   보장으로 표현하지 않는다.

## 검증 증거

- `:appointment-messaging:test --clean --no-daemon --no-configuration-cache --max-workers=1`:
  66 test bodies passed, including concurrent claim fencing, codec limits, broker metadata readiness,
  producer contract, schema/serializer startup readiness, bounded backlog gauges, cancellation
  reclaim, forged-scope negative cases, and relay lifecycle.
- Fixed-seed production-claim evidence passed for a mixed 20,000-row H2 backlog: V22 index metadata
  and `EXPLAIN` showed a bounded ready/recovery index path without a table scan; the conditional
  lease update repeated due/lease and attempt-version predicates. Three warmup rounds and fifteen
  measured rounds exercised the actual `JdbcAppointmentOutboxStore.claim` path, including a
  two-thread contention sample with distinct claimed IDs. The raw-payload-free report records
  p50/p95/p99, `maxClaimed`, and contention samples in
  `build/reports/appointment-messaging/benchmark.json`.
- The latest focused API regression run passed 41/41 tests, covering V22 migration, auto-configuration
  ordering, persistence-failure mapping, controller privacy, invalid cancellation reasons, reschedule,
  and notification atomicity. Appointment/reschedule isolation and the historical shared-context
  403 interference remain documented repository test-isolation details.
  Running all three classes in one JVM still exposes a shared-context 403 interference (7 reschedule
  failures); this is recorded as a repository test-isolation gap, not a messaging assertion failure.
- Appointment messaging persistence failures now use a typed contract exception and a privacy-safe
  `503 + Retry-After` response. Credential values remain outside the public configuration snapshot.
- Full API suite remains environment-limited by unavailable external PostgreSQL/MySQL/Redis
  services and unrelated actuator security cases; this is recorded as a validation gap rather
  than treated as a messaging regression.
- The benchmark is a deterministic bounded claim contract on H2, not a production Kafka/DB SLO
  run. Production p95/p99, lock-wait, heap, and broker catch-up measurements remain deployment-
  environment evidence to collect before rollout; the local report intentionally sets
  `deploymentSloEvidence=false`. The Actuator health component now separates
  configuration/schema failures (`DOWN`) from broker/relay availability (`OUT_OF_SERVICE`) without
  exposing tenant, clinic, appointment, topic, or credential values.
