# Issue #41 예약 메시징 transactional outbox 설계 명세

## 상태

- 이슈: `#41 [mq] appointment-messaging 모듈 생성 및 도메인 이벤트 발행`
- 상위 결정: `#40`의 Kafka4 전용 메시징 결정
- 승인: 2026-08-05 사용자 승인
- 분류: Type A (새 모듈, 저장 스키마, 비동기 relay, 기존 쓰기 경로 변경)
- 문서 언어: 내부 설계 명세이므로 한국어로 작성한다.

## 1. 문제와 현재 증거

현재 예약 API는 aggregate와 알림 outbox를 `transaction {}`에서 기록한 뒤, 트랜잭션이 반환된 후 `ApplicationEventPublisher.publishEvent`로 `Created`, `StatusChanged`, `Cancelled` 이벤트를 발행한다. 따라서 프로세스가 커밋 직후 종료되거나 이벤트 listener가 실패하면 예약 상태는 커밋됐지만 외부 메시지는 누락될 수 있다. `Rescheduled` 이벤트는 `ClosureRescheduleService`의 caller transaction을 공유하는 알림 port 경로만 존재하고, Kafka 메시지 outbox 기록은 없다.

저장소에는 이미 `scheduling_outbox_events`가 존재한다. 이 테이블은 `event_id`, `causation_event_id`, `correlation_id`, tenant/clinic 경계, `event_type`, `schema_version`, `payload_json`, `PENDING/PUBLISHED/FAILED` lifecycle을 제공하며, V9 이후 generic `aggregate_type`/`aggregate_id`를 dual-write한다. 기존 plan/policy writer와의 호환성을 위해 일부 identity가 nullable인 상태이므로 새 writer는 필요한 모든 identity와 relay metadata를 채워야 한다.

## 2. 목표와 불변식

### 목표

1. 예약 aggregate 변경과 예약 도메인 이벤트 outbox row를 하나의 Exposed transaction에서 원자적으로 커밋한다.
2. `appointment-messaging` 모듈을 추가하고 Kafka4 relay, envelope codec, partition-key 정책, readiness를 한 경계에 둔다.
3. `Created`, `StatusChanged`, `Cancelled`, `Rescheduled` 네 이벤트를 동일한 envelope 규칙으로 발행한다.
4. broker 장애, process 종료, lease 만료, Kafka ack 후 DB update 실패를 at-least-once 모델로 안전하게 복구한다.
5. tenant/clinic/aggregate identity와 correlation/causation lineage를 누락하거나 서로 다른 scope로 섞지 않는다.

### 불변식

- DB commit이 이벤트 전달의 유일한 원자성 권위다. DB transaction 안에서 `KafkaTemplate` 또는 Kafka producer를 호출하지 않는다.
- outbox row가 commit되지 않으면 broker 메시지도 존재하지 않아야 한다.
- relay는 DB lease를 소유한 동안에만 claim row를 terminal 상태로 바꿀 수 있다. owner와 token을 잃은 늦은 worker는 성공/실패를 기록할 수 없다.
- Kafka ack를 받은 뒤 terminal DB update가 유실되면 새 `eventId`를 만들지 않고 같은 row의 같은 `eventId`를 재전송한다.
- 모든 예약 이벤트는 양수 `tenantGroupId`, 양수 `clinicId`, `aggregateType=APPOINTMENT`, 양수 `aggregateId`를 가진다.
- writer는 caller가 제공한 scope를 권위로 신뢰하지 않는다. 같은 Exposed transaction에서 appointment와 clinic의 `(tenantGroupId, clinicId)` 소속을 재조회하고, 불일치하거나 없는 row이면 outbox를 기록하지 않는다. reschedule은 원본과 replacement 양쪽을 모두 검증한다.
- payload는 예약 식별자와 상태 전이처럼 공개 계약에 필요한 allow-list 값만 포함한다. 환자 이름/전화번호, credential, bearer token, idempotency key, 자유 입력 원문 사유는 포함하지 않는다.
- `correlationId`는 필수인 bounded trace metadata이지만 caller가 제어할 수 있는 untrusted 값이다. authz/audit identity, deduplication key, scope 판단에 사용하지 않는다. `eventId`는 서버가 생성한 immutable row 값이다.
- tenant/clinic/aggregate ID는 metric tag나 일반 로그에 직접 올리지 않는다. raw payload, credential, exception/header 원문도 로그·metric·quarantine에 기록하지 않고 bounded failure code와 payload hash만 남긴다.

## 3. 선택한 아키텍처

### 3.1 모듈 경계

```text
appointment-api  ──>  appointment-messaging  ──>  appointment-event  ──>  appointment-core
       │                       │
       └── same Exposed transaction ──> scheduling_outbox_events

appointment-messaging relay ──(DB claim transaction)─> Kafka4
                             └─(Kafka IO는 DB transaction 밖)
```

- `appointment-messaging`를 새 leaf-adapter 모듈로 추가한다. 현재 `appointment-event`가 소유한 `SchedulingOutboxEvents` table contract를 재사용하고, table을 두 개로 분리하지 않는다.
- `appointment-api`는 `AppointmentOutboxWriter`를 주입받아 aggregate mutation transaction 안에서 호출한다.
- `appointment-event`의 기존 `ApplicationEvent` logger는 호환용 local event 관찰자로 유지한다. 해당 listener를 broker outbox writer로 사용하지 않는다.
- `ClosureRescheduleService`는 기존 `AppointmentRescheduleNotificationWriter` port를 계속 사용한다. API 조립 계층의 lambda가 기존 notification writer와 새 messaging writer를 같은 caller transaction에서 함께 호출하도록 구성한다.
- consumer idempotency ledger, Schema Registry subject/compatibility, retry/DLT/quarantine/replay 운영은 Issue #42 범위로 남긴다.

### 3.2 쓰기 흐름

각 유스케이스는 다음 순서를 지킨다.

1. controller 또는 내부 caller가 `TenantClinicScope`, command `correlationId`, 그리고 root command가 아니면 직접 원인이 된 `causationId`를 application service에 전달한다. 누락된 correlation/causation은 writer가 조용히 생성하지 않고 fail-fast한다.
2. `transaction {}` 안에서 appointment를 insert/update하고 상태 이력과 기존 알림 outbox를 기록한다. writer는 같은 transaction에서 appointment와 clinic의 tenant 소속을 재조회해 scope를 증명한다. cross-tenant 또는 replacement scope 불일치이면 전체 transaction을 rollback한다.
3. 같은 transaction 안에서 `AppointmentOutboxWriter`가 새로운 `eventId`, envelope metadata, canonical payload, topic, partition key를 `scheduling_outbox_events`에 insert한다. `eventId`와 `occurredAt`은 이 시점에 한 번만 결정한다.
4. 임의 예외가 발생하면 aggregate, 상태 이력, 알림 outbox, 메시징 outbox가 함께 rollback된다.
5. transaction 반환 후 기존 local `ApplicationEvent`를 publish할 수 있으나, 그 성공 여부는 Kafka 전달 정합성에 영향을 주지 않는다.

`create`의 idempotency replay와 status CAS 실패는 기존 규칙을 유지한다. 새로운 outbox row는 실제 mutation이 성공한 transaction에서만 하나 생성하며, replay transaction에서는 생성하지 않는다. `Rescheduled`는 새 appointment insert와 원본 appointment의 `RESCHEDULED` 전환을 포함한 동일 transaction에 하나의 `AppointmentRescheduled` row를 추가한다. create/status/cancel/reschedule 모든 경로는 동일한 typed command context로 correlation/causation을 전달하며, reschedule callback에 HTTP correlation을 합성 문자열로 만들지 않는다. 기존 public JVM descriptor는 명시적 overload/delegation으로 보존하고, Kotlin default parameter만으로 호환성을 주장하지 않는다.

### 3.3 이벤트 계약

| 이벤트 | `eventType` | `aggregateId` | payload allow-list |
|---|---|---|---|
| 생성 | `AppointmentCreated` | 새 appointment ID | `appointmentId`, `version`, `status` |
| 상태 변경 | `AppointmentStatusChanged` | appointment ID | `appointmentId`, `version`, `fromState`, `toState`, 등록된 `reasonCode`(없으면 생략) |
| 취소 | `AppointmentCancelled` | appointment ID | `appointmentId`, `version`, 등록된 `reasonCode`(없으면 생략) |
| 재배정 | `AppointmentRescheduled` | 원본 appointment ID | `originalAppointmentId`, `replacementAppointmentId`, `originalVersion`, `replacementVersion` |

모든 이벤트의 `schemaVersion`은 최초 계약에서 `1`이다. 자유 입력 reason은 durable broker payload에서 제외하고, writer API는 `CancellationReasonCode?` 또는 등록된 bounded code만 받는다. 상태 문자열과 code는 닫힌 allow-list로 검증한다. envelope는 event type과 payload DTO가 불일치할 수 없는 sealed/typed mapping을 사용하며 raw `String` event ID와 caller-supplied clock을 public relay API로 노출하지 않는다. production clock은 DB clock abstraction으로 읽고, 테스트만 고정 clock/UUID generator를 주입한다.

### 3.4 envelope와 routing

relay가 `payload_json`과 metadata column을 조합해 Kafka value를 만든다.

```json
{
  "eventId": "uuid",
  "eventType": "AppointmentStatusChanged",
  "schemaVersion": 1,
  "occurredAt": "2026-08-05T08:30:00Z",
  "tenantGroupId": 7,
  "clinicId": 31,
  "aggregateType": "APPOINTMENT",
  "aggregateId": "924",
  "correlationId": "http-request-correlation-id",
  "causationId": "root-command-correlation-id",
  "payload": {
    "appointmentId": 924,
    "version": 3,
    "fromState": "REQUESTED",
    "toState": "CONFIRMED"
  }
}
```

- `occurredAt`는 event draft를 만든 UTC instant이며 DB `created_at`으로 대체하지 않는다.
- HTTP 진입점은 `X-Correlation-Id` filter가 만든 값을 전달한다. HTTP 외 호출은 workflow/command가 만든 bounded ID를 전달한다. correlation ID가 없으면 writer가 UUID를 생성하지 않고 insert를 거부한다. root command의 `causationId`는 그 command의 `correlationId`를 사용하고, root가 아닌 command/event 결과는 직접 원인이 된 command/event ID를 필수로 전달한다. correlation/causation은 trace lineage일 뿐 authz/audit identity가 아니다.
- 예약 mutation도 application command의 결과이므로 `causationId`를 null로 두지 않는다. reschedule의 원본 ID는 business payload이고, causation에는 reschedule을 직접 유발한 command/event ID를 기록한다.
- topic 기본값은 `clinic.appointment.events`; 설정으로 재정의할 수 있지만 row에 기록된 topic을 relay가 사용한다.
- partition key는 다음 규칙으로 저장한다.

  `tenant-{tenantGroupId}:CLINIC:clinic-{clinicId}:APPOINTMENT:apt-{aggregateId}`

  따라서 같은 tenant/clinic/appointment aggregate의 이벤트 순서는 같은 Kafka partition 후보로 수렴한다. reschedule은 원본 appointment ID를 aggregate key로 사용하고 replacement ID는 payload에만 둔다.

## 4. Outbox schema와 lease/fencing

### 4.1 기존 테이블 재사용

기존 `scheduling_outbox_events`를 유지하고 V22 additive migration을 세 dialect(H2/PostgreSQL/MySQL)에 추가한다. 기존 V9 nullable legacy column과 plan/policy row는 그대로 보존하며, 새 appointment writer row만 다음 필드를 모두 채운다.

추가 column 후보:

| column | 계약 |
|---|---|
| `occurred_at` | UTC event occurrence instant, non-null for new rows |
| `topic` | bounded Kafka topic, non-null for new appointment rows |
| `partition_key` | bounded routing key, non-null for new appointment rows |
| `lease_owner` | worker owner identifier, nullable when unclaimed |
| `lease_token` | unique fencing token, nullable when unclaimed |
| `lease_until` | DB-clock lease deadline, nullable when unclaimed |
| `last_failure_code` | bounded stable reason code, nullable |
| `last_failure_at` | last retry/failure UTC instant, nullable |

새 index는 appointment discriminator를 선행해 legacy plan/policy row를 ready scan에서 빠르게 제외한다. PostgreSQL partial index는 `status='PENDING' AND aggregate_type='APPOINTMENT' AND event_type IN (...)` 조건으로 `(next_attempt_at, lease_until, created_at, id)`를 만들고, H2/MySQL은 `(status, aggregate_type, event_type, next_attempt_at, lease_until, created_at, id)` 및 `(status, aggregate_type, event_type, lease_until, id)` 복합 index를 만든다. `topic IS NOT NULL`과 `partition_key IS NOT NULL`은 residual predicate로 재확인한다. migration은 기존 row를 backfill하지 않고 nullable로 추가하며, readiness는 appointment event row에 필요한 값이 모두 존재하는지 별도로 검사한다. 각 dialect의 `EXPLAIN`과 mixed legacy/appointment backlog lock-wait 결과를 DoD evidence로 남긴다.

`status`에는 새 `CLAIMED` 값을 추가하지 않는다. 기존 `PENDING` row에 lease metadata가 있으면 claim 중인 것으로 해석한다. 이렇게 하면 기존 status enum/check와 retention query를 깨지 않으면서 owner/token predicate로 fencing할 수 있다.

### 4.2 claim/terminal protocol

1. relay는 짧은 Exposed transaction에서 DB clock을 읽고, `status=PENDING`, appointment event allow-list, `topic/partition_key IS NOT NULL`, `next_attempt_at`이 null 또는 현재 시각 이하이며 lease가 null/만료된 row의 bounded candidate page를 결정한다.
2. claim은 row별 select-then-update가 아니다. PostgreSQL/MySQL은 가능하면 `FOR UPDATE SKIP LOCKED`로 잠그고, 모든 dialect에서 `UPDATE ... WHERE id IN (...) AND status=PENDING AND (lease_until IS NULL OR lease_until <= databaseNow)` 형태의 단일 batch conditional update로 `lease_owner`, row별 random `lease_token`, `lease_until`을 설정한다. 영향받은 row만 같은 transaction에서 조회해 transaction 밖으로 가져간다. H2 fallback도 후보별 update를 반복하지 않고 bounded `IN` CAS batch를 사용한다.
3. payload/envelope 검증과 Kafka4 `KafkaTemplate` send는 transaction 밖에서 수행한다. send timeout과 retry는 worker의 bounded budget을 넘지 않는다.
4. 성공 시 별도 짧은 transaction에서 다음 predicate를 모두 만족할 때만 `PUBLISHED`, `published_at`, lease clear를 기록한다: row ID, owner, token, `lease_until > databaseNow`, 현재 status `PENDING`. retry attempt는 claim마다 한 번의 broker publish만 수행하며 Kafka client 내부 재시도도 send budget 안으로 제한한다.
5. 재시도 가능한 실패는 같은 fencing predicate로 `PENDING`, `next_attempt_at`, `last_failure_code`, lease clear를 기록한다. 최대 시도 횟수를 넘거나 payload/contract가 영구적으로 invalid하면 bounded `failure_code`와 payload hash만 남겨 `FAILED`로 terminal 전환한다. redrive는 별도 승인된 operator 절차에서 같은 `eventId`를 유지한다.
6. terminal update가 0 row이면 worker는 lease를 잃은 것으로 간주하고 DB를 다시 쓰지 않는다. Kafka ack 후 이 경우에는 row가 만료된 뒤 같은 event ID를 다시 publish한다. 두 relay가 같은 row를 동시에 선택해도 conditional batch update에서 하나만 lease를 얻어야 하며, 이를 two-relay race test로 검증한다.
7. `leaseDuration >= sendTimeout + kafkaClientRetryBudget + terminalDbUpdateBudget + safetyMargin`을 startup validation으로 강제한다. 기본 acceptance profile은 `leaseDuration=30s`, `sendTimeout=5s`, `kafkaClientRetryBudget<=10s`, `terminalDbUpdateBudget<=3s`, `safetyMargin>=10s`이며, claim 내부 broker publish는 한 번만 실행한다.

## 5. Relay와 readiness

- relay는 `topic`과 `partition_key`가 채워진 appointment event allow-list row만 처리한다. 기존 plan/policy writer의 topic-null legacy row를 임의로 재해석하지 않는다.
- 한 tick의 claim 수(`<=32`), 동시 in-flight send(`<=32`), clinic별 batch 공정성(`<=4`), Kafka send timeout, lease duration, retry limit, retry backoff, poll interval은 configuration으로 bounded하게 두고 startup에서 양수·상호 일관성을 검증한다. broker ack 지연 또는 연속 3회 timeout이면 circuit breaker를 열어 최소 30초 pause하고, pause 중 local queue는 in-flight 상한을 넘지 않는다. 복구 시 jittered backoff와 catch-up rate limit을 적용하며 한 clinic이 다른 clinic을 starvation시키지 않는다.
- payload JSON은 Jackson 3 fixed DTO codec으로 parse한다. default typing/polymorphic typing, Kafka `__TypeId__`/FQN header, tombstone/null value를 비활성화·거부하고, unknown event/schema/field, duplicate key, trailing token, malformed UTF-8, control character, oversized value/header, depth/collection/string 상한을 fail closed 한다. acceptance cap은 value 64 KiB, 전체 header 8 KiB/32개, nesting depth 32, collection 128, string 4 KiB이다.
- writer는 topic을 `[A-Za-z0-9._-]{1,249}` 정규식과 사전 승인된 allow-list로만 허용한다. request/tenant 값으로 topic을 만들지 않으며 partition key는 양수 DB ID로 생성한 bounded ASCII canonical 값만 허용하고 CR/LF/control/oversize를 거부한다.
- Kafka config는 typed binding과 fail-fast를 사용한다. TLS 및 broker authn/authz를 활성화하고 SASL/SSL credential은 secret-manager reference로만 주입한다. producer principal은 지정 topic write/metadata 최소 ACL만 갖고 `allow.auto.create.topics=false`, `enable.idempotence=true`, `acks=all`과 bounded request/delivery timeout을 강제한다. readiness는 broker metadata, authz, 필수 topic 존재, serializer self-check, V22 columns/indexes, relay `enabled|paused|held` 상태를 모두 확인한다. 잘못된 config는 startup fail-fast하고 일시적 broker outage는 API transaction을 막지 않되 relay readiness를 degraded/not-ready로 표시한다.
- blocking Exposed claim/terminal transaction은 `Dispatchers.IO`로 격리하고 relay는 structured coroutine scope에서 실행한다. shutdown은 신규 claim을 중지하고 최대 10초 drain 후 in-flight send를 cancellation으로 종료한다. 취소된 worker는 terminal write를 하지 않으며 lease는 만료/owner CAS release 후 다음 poll이 회수한다.
- metrics는 `pending`, `oldest_age`, `publish_success`, `publish_retry`, `publish_failed`, `lease_lost`, `invalid_payload`, `broker_pause`, `partition_skew`처럼 낮은 cardinality만 사용한다. tenant/clinic/appointment/event ID와 raw payload를 tag/log에 쓰지 않는다. dashboard는 alert 수치·severity·owner·escalation·rollback 기준을 함께 제공하고, service operator가 pause/hold와 승인된 redrive를 수행한다.

## 6. 실패 모델과 복구

| 상황 | DB 상태 | broker 동작 | 복구 원칙 |
|---|---|---|---|
| aggregate transaction rollback | aggregate/outbox 모두 없음 | 없음 | caller 오류로 반환 |
| commit 성공 후 process 종료 | `PENDING` | 없음 | 다음 poll이 claim |
| Kafka send timeout | `PENDING`, retry schedule | 미확정 | 같은 event ID로 재시도 |
| Kafka send 성공 후 DB terminal update 실패 | `PENDING` 또는 만료 lease | 이미 한 번 전달 | duplicate 허용, 같은 event ID 재전송 |
| lease 만료 후 늦은 worker | 다른 owner/token | 늦은 worker 결과 무시 | 새 owner만 terminal write |
| malformed/unknown contract | `FAILED` | publish 금지 | bounded failure code와 payload hash만 기록하고 승인된 redrive/quarantine runbook으로 같은 event ID를 복구 |
| 유효한 topic/producer 설정 이후 broker outage 또는 ACL 일시 실패 | row는 commit 가능 | publish 금지 | relay readiness down/held, row는 유실하지 않음, secret/raw payload는 로그에 남기지 않음 |
| topic/producer 설정 자체 누락·문법 오류 | writer transaction 진입 전 fail-fast | publish 금지 | startup/readiness fail-fast, partial outbox row를 만들지 않음 |
| relay shutdown/cancellation | `PENDING` 또는 만료 lease | in-flight 전파 취소 | bounded drain 뒤 lease expiry/owner CAS release, 다음 worker가 같은 event ID를 claim |

전역 exactly-once는 목표로 삼지 않는다. consumer deduplication은 event ID를 기준으로 Issue #42에서 다룬다.

## 7. 롤아웃과 롤백

1. 먼저 V22를 additive 방식으로 배포하고 schema/index/readiness와 mixed-version writer compatibility를 확인한다.
2. 다음 배포에서 writer가 새 column을 채우도록 활성화한다. scope proof, correlation/causation, topic allow-list, codec self-check가 실패하면 API transaction은 fail closed하고 partial write를 만들지 않는다.
3. relay를 canary worker 수, `claimLimit<=32`, `maxInFlight<=32`, circuit-breaker pause와 함께 시작하고 pending age/retry/lease-lost/invalid payload/partition skew를 관찰한다.
4. 안정화 후 worker concurrency를 단계적으로 올린다. broker outage에서는 pause/throttle과 bounded catch-up을 유지하며, consumer/schema registry rollout은 #42의 별도 gate다.
5. rollback 시 relay를 먼저 `held`로 전환하고 in-flight drain/cancel 후 writer를 이전 코드로 되돌리되, V22 column은 삭제하지 않는다. 이미 Kafka에 전달된 event를 취소하거나 새 event ID로 보정하지 않는다. backlog/oldest-age/lease-churn이 사전 기준 이하이고 audit evidence가 있을 때만 rollback을 완료한다.

## 8. 범위와 제외

포함:

- `appointment-messaging` 모듈 및 Kafka4 dependency
- 네 appointment domain event의 transactional outbox writer
- envelope/allow-list/partition-key codec
- V22 세 dialect migration과 Exposed table metadata
- owner/token/DB-clock lease-fenced bounded relay
- readiness/metrics와 unit/integration/dialect 테스트
- 기존 create/status/cancel/reschedule 호출 경로의 writer 연결
- 범위는 현재 `AppointmentService`와 최종 `RescheduleController` mutation 경로로 한정한다. commitment-v2의 `AdminAppointmentController`/`CustomerAppointmentController`와 closure의 중간 `PENDING_RESCHEDULE` 전이는 이 issue에서 발행하지 않으며, consumer가 이를 전체 appointment stream으로 오해하지 않도록 README와 envelope contract에 partial-stream임을 명시한다.

제외:

- Kafka3, RabbitMQ 또는 transport abstraction
- consumer inbox/idempotency ledger
- Schema Registry subject/compatibility 정책
- retry/DLT/quarantine/replay 운영 UI
- global exactly-once transaction
- 예약 aggregate 모델이나 공개 URL versioning의 재설계
- commitment-v2/closure intermediate transition을 포함하는 complete appointment stream 전환

## 9. 검증과 DoD

- [ ] outbox row가 aggregate와 같은 transaction에서 commit/rollback됨을 테스트한다.
- [ ] 네 event type의 envelope 필드, privacy allow-list, partition key를 테스트한다.
- [ ] duplicate HTTP idempotency replay가 새 outbox row를 만들지 않음을 테스트한다.
- [ ] lease 경쟁, 만료 recovery, stale owner terminal write 차단을 테스트한다.
- [ ] atomic batch claim과 two-relay race, mixed legacy/appointment index plan, dialect `EXPLAIN`, lock-wait boundedness를 테스트한다.
- [ ] Kafka ack 후 DB update 실패가 같은 `eventId` 재전송으로 이어짐을 테스트한다.
- [ ] correlation/causation fail-fast와 root/non-root lineage를 테스트한다.
- [ ] writer가 appointment/clinic tenant scope를 같은 transaction에서 증명하고 cross-tenant/replacement mismatch를 거부함을 H2/PostgreSQL/MySQL에서 테스트한다.
- [ ] TLS/SASL/ACL, secret redaction, `allow.auto.create.topics=false`, serializer self-check와 strict Jackson duplicate/trailing/FQN-header/tombstone/size/depth 거부를 테스트한다.
- [ ] broker outage pause/backpressure/fairness, lease-budget boundary, `Dispatchers.IO` isolation, cancellation/graceful shutdown을 테스트한다.
- [ ] 고정 seed 20,000-row burst와 sustained relay benchmark를 실행한다. acceptance profile은 warmup 후 publish-to-ack p95<=500ms, p99<=2s, oldest-age catch-up<=180s, lock-wait p95<=50ms, partition skew<=2.0x, heap 증가<=256MiB, relay thread 증가<=32이며 재현 명령과 raw payload 없는 결과를 기록한다.
- [ ] create/status/cancel/reschedule의 correlation/causation 전파, typed reason allow-list, explicit JVM overload compatibility, reschedule trace continuity를 테스트한다.
- [ ] `appointment-messaging`가 CI path filter/job/Kover/nightly coverage와 root `README.md`/`README.ko.md` module catalog에 포함되고, Kafka4/Jackson3 catalog alias가 실제 compile resolution을 통과함을 검증한다. 새 module의 `src/test/resources/junit-platform.properties`와 `logback-test.xml`도 등록한다.
- [ ] correlation/event/aggregate/tenant 식별자는 기존 bounded value class 또는 동등한 typed wrapper를 재사용하고, public writer/relay API와 한국어 KDoc 예제가 raw `String`/caller clock을 노출하지 않음을 확인한다.
- [ ] H2/PostgreSQL/MySQL V22 migration과 Exposed metadata/readiness를 검증한다.
- [ ] `appointment-messaging` 모듈 build/test와 의존 모듈 build/test를 실행한다.
- [ ] 전체 Gradle test, lint/static checks, `git diff --check`, workflow DoD, PR CI를 fresh evidence로 기록한다.
- [ ] Issue #41과 PR의 assignee/milestone/labels/body parity를 확인하고, PR body 마지막에 `## DoD Status`를 둔다.

## 10. 결정과 거부한 대안

### 결정

직접 transactional outbox writer(A)를 선택한다. 현재 service transaction을 가장 적게 바꾸면서 DB commit과 message intent의 원자성을 명시적으로 보장하고, reschedule의 caller-transaction port에도 같은 writer를 연결할 수 있다.

### 거부한 대안

- `@TransactionalEventListener(BEFORE_COMMIT)`: 현재 create/status/cancel 이벤트는 transaction 반환 후 발행되며, listener 순서와 호출 누락에 의존한다.
- 별도 appointment 전용 outbox table: 이미 generic `scheduling_outbox_events`와 retention/tenant 규칙이 존재해 중복 schema와 dual-write drift를 만든다.
- DB transaction 안 Kafka send: broker latency/장애가 예약 commit을 붙잡고, DB와 Kafka의 전역 exactly-once를 제공하지도 않는다.

## 11. 후속 계획 입력

이 명세 승인 후 구현 계획은 다음 lane을 순서대로 수행한다.

1. catalog/module/schema contract와 failing tests
2. writer/envelope/partition-key 구현
3. API/reschedule transaction wiring
4. lease-fenced relay/readiness/metrics
5. dialect/build/integration 검증과 독립 review
6. PR/CI/merge/local sync/cleanup DoD
