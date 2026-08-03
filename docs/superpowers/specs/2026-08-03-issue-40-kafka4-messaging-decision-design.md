# Issue #40 Kafka4 메시징 도입 결정 설계

상태: 승인 완료, Step 2-R PASS (`P0=0`, `P1=0`)
작성일: 2026-08-03
대상 저장소: `bluetape4k/clinic-appointment`
관련 이슈: #40, #41, #42

## 1. 문제와 목표

현재 clinic-appointment는 `appointment-event`의 Spring application event와 DB 기반
outbox를 사용하지만, 프로세스 경계를 넘어 도메인 사건을 전달하고 재처리할 공통
메시지 broker 계약은 없다. Issue #40의 목표는 Kafka와 RabbitMQ를 다시 비교하는 데
그치지 않고, 후속 구현이 사용할 단일 runtime line과 전달 의미를 결정하는 것이다.

이 설계는 다음 결과를 고정한다.

1. 신규 broker 기반 메시징은 Kafka4만 지원한다.
2. 구현은 `bluetape4k-kafka4`, Spring Kafka 4, Jackson 3 조합을 사용한다.
3. DB transaction의 권위는 유지하고 durable outbox relay가 Kafka에 발행한다.
4. end-to-end 계약은 at-least-once이며 producer와 consumer 양쪽에서 중복을 제어한다.
5. 구체적인 module 구현은 #41, consumer와 schema/versioning 정책은 #42가 소유한다.

## 2. 범위와 비목표

### 포함

- Kafka4 선택 근거와 호환성 line
- DB outbox에서 Kafka로 이어지는 권위와 전달 의미
- partition key와 event envelope의 최소 계약
- 실패·재시도·중복·재처리·관측성 원칙
- #41과 #42 사이의 구현 책임 분리
- 후속 구현의 검증 전략과 수용 기준

### 제외

- Kafka3 또는 `bluetape4k-kafka` 지원
- RabbitMQ와 RabbitMQ Streams 지원
- broker-neutral abstraction 또는 runtime broker 교체 기능
- 이 이슈에서의 Kotlin, Gradle, module, Flyway, runtime 설정 변경
- topic 수, partition 수, retention 기간, Schema Registry compatibility mode의 운영값 확정
- consumer 업무 로직, DLT 처리기, replay 운영 API의 실제 구현
- DB와 Kafka를 하나의 원자적 transaction으로 묶는 전역 exactly-once 보장

## 3. 현재 근거

### 3.1 저장소 기준선

- `appointment-event`는 동일 프로세스의 Spring event와 DB outbox 책임을 가진다.
- clinic-appointment에는 Kafka 또는 RabbitMQ runtime 의존성과 broker consumer가 없다.
- 후속 이슈 #41은 메시징 module 도입, #42는 consumer와 schema/versioning을 다룬다.

### 3.2 bluetape4k 지원 기준선

`bluetape4k-projects/infra/kafka4`는 현재 저장소가 사용하는 Spring Boot 4 계열과
맞는 다음 기능을 제공한다.

- Kafka clients/streams 4.x와 Spring Kafka 4.x
- Jackson 3 기반 serializer/deserializer
- coroutine·Reactor 확장
- embedded KRaft test 지원
- Kafka, KafkaServer, Redpanda singleton Testcontainers 지원
- Redpanda Schema Registry port를 포함한 통합 테스트 경로

반면 RabbitMQ는 공용 version catalog와 Testcontainers launcher는 있지만, Kafka4와
동등한 runtime module과 Kotlin/coroutine/codec 통합 계층이 없다.

### 3.3 공식 문서 근거

Kafka는 partition 안의 순서, consumer offset 기반 재처리, retention/compaction,
idempotent producer와 transaction 기능을 제공한다. 이 특성은 예약 상태 변화, 알림,
스케줄링 파생 사건을 독립 consumer가 각자의 속도로 처리하고 필요할 때 다시 읽어야
하는 요구에 맞는다.

RabbitMQ queue는 ack/requeue와 competing consumer에 적합하지만, 장기 retention과
offset 기반 replay는 기본 queue와 다른 Streams 운영면을 추가한다. clinic-appointment가
두 broker 의미를 동시에 추상화할 이유가 없으므로 Kafka4 하나로 좁힌다.

참고한 공식 자료:

- [Apache Kafka documentation](https://kafka.apache.org/documentation/)
- [Apache Kafka design](https://kafka.apache.org/43/design/design/)
- [Kafka topic configuration](https://kafka.apache.org/41/configuration/topic-configs/)
- [Kafka producer configuration](https://kafka.apache.org/41/configuration/producer-configs/)
- [Spring for Apache Kafka reference](https://docs.spring.io/spring-kafka/reference/kafka.html)
- [Spring Kafka transactions](https://docs.spring.io/spring-kafka/reference/kafka/transactions.html)
- [Spring Kafka exactly-once semantics](https://docs.spring.io/spring-kafka/reference/kafka/exactly-once.html)
- [Confluent Schema Registry](https://docs.confluent.io/platform/current/schema-registry/index.html)
- [Schema evolution and compatibility](https://docs.confluent.io/platform/current/schema-registry/fundamentals/schema-evolution.html)

자료 확인일은 2026-08-03이다. 실제 구현 시에는 bluetape4k governed catalog가 고정한
version을 다시 확인한다.

## 4. 검토한 접근과 결정

### 4.1 선택: `bluetape4k-kafka4` 기반의 Kafka4 전용 경계

후속 구현은 `bluetape4k-kafka4`를 사용해 Spring Kafka 4와 Jackson 3 계약을
clinic-appointment에 연결한다. version은 bluetape4k governed catalog를 따르며
clinic-appointment가 Kafka client나 Spring Kafka version을 독립적으로 override하지
않는다.

이 접근은 기존 coroutine, serializer, embedded KRaft, Testcontainers 지원을 재사용하고
Spring Boot 4/Jackson 3 line을 단일화한다.

### 4.2 기각: Spring Kafka 4 직접 사용

기술적으로 가능하지만 bluetape4k가 이미 제공하는 codec, coroutine, test 지원을
clinic-appointment에서 다시 구성하게 된다. 서로 다른 기본값과 test harness가 생길
가능성이 높으므로 기각한다.

### 4.3 기각: broker-neutral abstraction

partition key, offset, consumer group, replay, tombstone처럼 Kafka 고유 의미가 핵심
설계에 포함된다. 공통 `MessageBroker` 같은 얇은 추상화는 이 의미를 숨기고 사용하지
않는 RabbitMQ 교체 가능성만 추가하므로 YAGNI 원칙에 따라 기각한다.

### 4.4 기각: Kafka3와 Kafka4 병행

Kafka3 module은 Spring Boot 3/Jackson 2 line이며 Kafka4 module과 같은 package 경계를
사용한다. 병행하면 dependency와 serializer 선택이 모호해지므로 지원하지 않는다.

### 4.5 기각: RabbitMQ 또는 RabbitMQ Streams

현재 로컬 runtime 지원이 Kafka4보다 얕고, 기본 queue와 Streams를 함께 고려하면
운영면이 둘로 나뉜다. replay와 event backbone 요구에는 Kafka4가 더 직접적이므로
지원하지 않는다.

## 5. 권위와 데이터 흐름

DB가 예약과 outbox 상태의 유일한 transaction authority다. 도메인 변경과 outbox
record는 같은 Exposed transaction에서 기록한다. broker publish를 이 transaction 안에서
직접 수행하지 않는다.

```text
domain command
    -> Exposed transaction
        -> aggregate/state update
        -> durable outbox insert
    -> commit
    -> Kafka4 outbox relay claim
    -> serialize validated event envelope
    -> Kafka publish
    -> broker acknowledgement
    -> outbox published/attempt state update
```

DB commit 후 publish 전에 프로세스가 종료되면 relay가 다시 claim한다. Kafka
acknowledgement 후 outbox 상태 기록 전에 종료되면 같은 event가 재발행될 수 있다.
따라서 중복은 예외가 아니라 정상 failure model이다.

outbox claim은 DB row 상태, 변경 불가능한 owner token과 DB 시각 기준 lease deadline을
함께 사용한다. lease가 유효한 동안 다른 relay는 같은 row를 publish할 수 없으며 모든
terminal update는 owner token과 유효 lease를 predicate로 확인한다. relay crash,
cancellation, graceful shutdown 뒤에는 lease가 만료되거나 명시적으로 반환된 stale
claim만 회수한다. fence를 잃은 relay는 publish 완료를 기록하지 않는다. 이미 Kafka
acknowledgement를 받은 뒤 fence 또는 DB update를 잃은 경우에는 같은 `eventId`의
재발행으로만 복구하고 새 event ID를 만들지 않는다.

Spring Kafka transaction이나 Kafka exactly-once semantics는 Kafka 내부 consume-process-
produce 구간을 보강할 수 있지만 DB와 Kafka를 하나의 원자적 commit으로 만들지 않는다.
후속 구현과 문서는 전역 exactly-once를 주장해서는 안 된다.

## 6. 전달 계약

### 6.1 보장 수준

- producer: stable event ID, idempotent producer 설정, bounded retry
- broker: partition 안의 순서와 configured retention
- consumer: at-least-once 수신, stable event ID 기반 멱등 처리
- application: 업무 상태 변경과 처리 ledger를 가능한 한 같은 DB transaction에 기록
- replay: consumer group offset 또는 승인된 별도 replay group으로 수행

consumer가 멱등 처리 근거를 저장하지 못하는 side effect를 호출할 때는 해당 adapter가
provider idempotency key나 별도 delivery ledger를 가져야 한다.

### 6.2 partition key

기본 key는 scope 종류를 포함해 다음 요소를 안정적으로 결합한 aggregate scope다.

```text
tenantGroupId:CLINIC:clinicId:aggregateType:aggregateId
tenantGroupId:TENANT:_:aggregateType:aggregateId
```

같은 aggregate의 사건은 같은 partition으로 보내 순서를 보존한다. tenant와 clinic을
key에 포함해 cross-tenant collision을 방지한다. clinic-scoped record는 양의 clinic ID가
필수이고 tenant-scoped record만 `TENANT:_` form을 사용한다. 빈 문자열이나 `null`의
암묵적 문자열 변환은 금지한다. #41의 appointment event는 clinic-scoped이므로 clinic ID
누락을 publish 전에 거부한다. key에 환자 이름, 연락처, 임상 note, 자유 형식 문자열을
넣지 않는다.

모든 clinic 사건을 하나의 clinic key에 모으는 방식은 hot partition을 만들 수 있으므로
기본값으로 사용하지 않는다. 여러 aggregate를 원자적으로 정렬해야 한다는 요구는 이
설계에서 지원하지 않으며 별도 도메인 coordination으로 다룬다.

단일 aggregate 자체가 hot key가 되더라도 ordering을 깨는 임의 key salting은 하지
않는다. #41은 단일 aggregate burst와 다수 clinic burst fixture로 partition별 record/byte
분포와 lag skew를 측정하고 alert 기준을 수치로 고정해야 한다. 임계값을 넘으면 consumer
capacity, event type/topic 분리와 producer load shaping을 먼저 검토한다. partition 증설은
단일 hot aggregate의 해결책이 아니며 기존 key의 이후 record를 다른 partition으로 remap해
ordering을 끊을 수 있다. partition 수 또는 key 변경이 필요한 경우 producer pause와
outbox relay hold, 기존 partition drain/checkpoint 또는 새 topic migration, dual-read와
offset 전환, aggregate별 ordering 증명을 별도 설계하고 승인한다.

### 6.3 event envelope

공유 topic에 발행되는 event는 최소한 다음 metadata를 가진다.

| 필드 | 계약 |
|---|---|
| `eventId` | 전역적으로 안정적인 중복 제거 ID |
| `eventType` | allowlist 기반의 명시적 type 이름 |
| `schemaVersion` | 양의 정수 schema version |
| `occurredAt` | UTC instant |
| `tenantGroupId` | tenant 권위 scope |
| `clinicId` | clinic 권위 scope |
| `aggregateType` | bounded aggregate 분류 |
| `aggregateId` | opaque aggregate 식별자 |
| `correlationId` | 요청·workflow 추적 ID |
| `causationId` | 직접 원인이 된 command/event ID |
| `payload` | event type/version별 Jackson 3 DTO |

필드 권위와 생성 시점은 다음과 같다.

| 필드 | 소유자와 생성 시점 | 필수·불변 계약 |
|---|---|---|
| `eventId` | domain/outbox writer가 최초 outbox insert 때 생성 | 필수, 재발행에서 동일 값 재사용 |
| `eventType`, `schemaVersion` | 등록된 producer codec이 outbox insert 때 고정 | 필수, allowlist와 양의 version 검증 |
| `occurredAt` | domain 사건이 발생한 UTC instant를 writer가 기록 | 필수, relay 시각으로 재작성 금지 |
| tenant/clinic/aggregate scope | domain command와 aggregate 권위에서 writer가 기록 | 필수, relay override 금지 |
| `correlationId` | ingress 또는 application command가 생성·전파 | 필수, 누락 시 outbox insert fail-fast |
| `causationId` | 원인 command/event ID를 application service가 기록 | 최초 root command만 자기 correlation ID 사용, 그 외 필수 |
| `payload` | 등록된 event DTO를 writer가 만들고 producer codec이 검증 | 필수, insert 이후 의미 변경 금지 |

serializer는 명시적 DTO allowlist를 사용하고 unsafe default typing이나 임의 class name
header를 허용하지 않는다. shared topic의 tombstone/null payload는 기본적으로 금지한다.
compacted topic이 필요해 tombstone을 도입할 경우 topic별 계약으로 별도 승인한다.

broker 입력은 domain mapping 전에 fail-closed로 경계를 검사한다. #41/#42의 구현 spec은
Kafka record byte, 전체 header byte와 header count, 개별 identifier/string 길이, JSON
nesting depth, collection size의 숫자 상한을 반드시 고정해야 한다. control character가
포함된 식별자, 알 수 없는 envelope field, 허용되지 않은 event type/version, 상한을 넘는
record는 업무 handler에 전달하지 않는다. static DTO topic에서는 Java/Kotlin FQN
value-type header를 사용하지 않는다. quarantine/DLT는 raw PHI를 복제하지 않고 bounded
metadata, payload hash와 승인된 encrypted reference만 보존한다.

정확한 JSON schema, Schema Registry subject naming, compatibility mode와 version 전환
절차는 #42가 소유한다. #42는 이 envelope의 식별·scope·추적 필드를 제거할 수 없다.

비실행 예시는 다음 계약을 설명하며 실제 topic 이름이나 schema 확정본은 아니다.

```json
{
  "key": "tenant-7:CLINIC:clinic-31:APPOINTMENT:apt-924",
  "value": {
    "eventId": "018f4f12-5c3a-7d40-9f15-78e19f94bd61",
    "eventType": "AppointmentConfirmed",
    "schemaVersion": 1,
    "occurredAt": "2026-08-03T05:30:00Z",
    "tenantGroupId": "tenant-7",
    "clinicId": "clinic-31",
    "aggregateType": "APPOINTMENT",
    "aggregateId": "apt-924",
    "correlationId": "corr-810",
    "causationId": "cmd-441",
    "payload": { "appointmentId": "apt-924" }
  }
}
```

PII가 포함된 key, `null` payload/tombstone, Java/Kotlin FQN type header, allowlist에 없는
`eventType`, raw PHI를 복제한 DLT record는 모두 invalid다.

## 7. 실패 처리와 복구

| 실패 모드 | 기대 동작 | 후속 검증 책임 |
|---|---|---|
| DB commit 후 Kafka publish 실패 | outbox를 미완료로 유지하고 bounded backoff로 재시도 | #41 relay integration test |
| Kafka ack 후 outbox 완료 기록 실패 | 동일 `eventId` 재발행 허용, consumer가 중복 제거 | #41 producer test, #42 consumer test |
| consumer 처리 중 crash | offset commit 전에 실패시키고 재수신 | #42 integration test |
| incompatible 또는 malformed payload | 업무 처리 금지, bounded DLT/quarantine evidence 기록 | #42 schema/DLT test |
| hot partition 또는 consumer lag | partition skew, lag, retry age를 관측하고 capacity 조정 | #41/#42 operational test |
| poison event 무한 재시도 | bounded retry 뒤 DLT/quarantine, 원본 topic 진행 복구 | #42 retry test |
| broker 장기 장애 | DB 업무 transaction은 유지하고 outbox backlog/oldest age로 장애 표면화 | #41 recovery test |
| 권한 없는 producer/consumer | broker ACL과 application scope 검증으로 거부 | #41 config, #42 negative test |

DLT 또는 quarantine payload에는 원문 개인정보를 무제한 복제하지 않는다. bounded
metadata, payload hash, failure code와 승인된 encrypted payload reference만 기록한다.

#41의 relay는 claim page/batch, 동시 publish 수, local queue capacity를 모두 bounded
configuration으로 두고 시작 시 양수·상호 일관성을 검증한다. broker acknowledgement가
늦어지면 무제한 적재하지 않고 claim/publish를 throttle 또는 pause한다. backlog count와
oldest age가 경보 기준을 넘으면 catch-up rate limit과 tenant/clinic 공정성을 유지하며
복구하고, 한 clinic의 backlog가 다른 clinic을 지속적으로 starvation시키지 않음을
검증한다. 구체적인 batch/concurrency/queue/alert 값은 #41 spec이 burst fixture와 운영
용량 근거로 수치화해야 하며, 수치가 없는 구현은 완료로 인정하지 않는다.

## 8. 보안과 운영 계약

- broker authentication/authorization은 환경 설정과 secret manager가 소유하고 저장소에
  credential을 커밋하지 않는다.
- producer와 consumer principal은 필요한 topic/action에만 접근한다.
- metric label에는 `eventId`, `aggregateId`, `tenantGroupId`, `clinicId` 또는 다른
  tenant/clinic 식별자를 넣지 않는다. tenant/clinic 필터는 권한이 제한된 dashboard와
  audit record에서만 사용한다.
- 최소 관측 항목은 publish success/failure, retry count, outbox backlog/oldest age,
  consumer lag, processing failure, DLT/quarantine count, partition skew다.
- log는 correlation/causation ID와 bounded technical context를 제공하되 환자 개인정보와
  raw payload를 기본 출력하지 않는다.
- replay는 별도 consumer group과 승인된 범위·시작 offset·dry-run evidence를 사용한다.
  운영 consumer group의 offset을 임의로 되감지 않는다.
- replay request와 audit record는 `topic`, `partition`, start offset, end offset 또는 time
  cutoff, event type/schema version, tenant/clinic scope, dry-run 결과, side-effect mode,
  approver와 audit ID를 포함한다. side effect adapter는 멱등성 또는 명시적 no-effect
  dry-run을 증명해야 한다.
- rollout은 feature flag 또는 clinic allowlist로 producer와 consumer를 독립 제어하고,
  rollback 시 producer disable, relay drain 또는 hold 결정, consumer drain 또는 disable
  순서로 진행한다. 이미 발행된 event는 멱등 consumer로 처리하거나 승인된 quarantine에
  격리하며 offset rewind와 topic 삭제로 숨기지 않는다. backlog, lag, in-flight,
  DLT/quarantine count가 사전에 정한 안전 기준 이하이고 audit evidence가 남아야 rollback을
  완료로 판정한다.
- retention은 승인된 replay window, consumer outage SLO, outbox 최대 retry/backlog age보다
  짧게 설정할 수 없다. #41/#42가 실제 값을 함께 고정하고 migration/rollback 검증을
  수행한다.

운영 소유권은 다음과 같이 나눈다.

| 영역 | 권위와 책임 |
|---|---|
| application | typed configuration binding, fail-fast validation, envelope/serializer self-check |
| platform/infra | topic provisioning, ACL/principal, secret rotation, broker/Schema Registry endpoint, partition/retention 변경 |
| CI/deploy | 기대 topic/config/ACL과 실제 환경의 drift 검사, rollout hold evidence |
| service operator | dashboard, alert owner, relay pause/hold와 승인된 replay 실행 |

application의 topic auto-create는 금지한다. 시작 또는 readiness 검사에서 broker metadata,
authentication/authorization, 필수 topic 존재와 호환 config, serializer/envelope self-check,
relay의 `enabled|paused|held` 상태를 확인한다. 구성 자체가 잘못되면 시작을 fail-fast한다.
일시적인 broker outage는 process liveness를 죽이지 않고 readiness를 degraded 또는 not-ready로
표시하며 backlog/oldest-age와 함께 복구 상태를 노출한다.

#41/#42는 relay publish error rate, oldest outbox age, claim lease churn, consumer lag와
catch-up ETA, DLT 증가, partition skew, replay active state를 포함한 dashboard를 제공한다.
각 alert는 수치, severity, page/ticket 기준, owner, silence/escalation 절차와 rollout
halt/rollback 조건을 가진다.

replay runbook은 preflight snapshot/checkpoint, dry-run, 승인, execution window, abort
threshold, side-effect mode, partial failure 재개 위치, post-run reconciliation,
quarantine/rollback과 audit retention을 순서대로 요구한다. side-effect mode는 다음 셋만
허용한다.

- `DRY_RUN_NO_EFFECT`: 외부 adapter를 호출하지 않는다.
- `IDEMPOTENT_REPLAY`: ledger와 provider idempotency key가 있는 side effect만 허용한다.
- `QUARANTINE_ONLY`: 업무 side effect 없이 선택된 record를 검토 queue로 격리한다.

topic/partition/retention 변경 runbook은 config snapshot과 drift precheck, capacity review
owner, rollout/abort 기준, consumer assignment·replay·catch-up 영향, 변경 후 검증을 가진다.
retention 축소는 최소 보존 조건을 위반하면 금지한다. partition 증설은 기존 record의
partition을 바꾸지 않지만 같은 key의 이후 record를 다른 partition으로 remap할 수 있다.
따라서 producer pause/relay hold와 drain/checkpoint 또는 새 topic migration, dual-read/offset
전환 및 ordering 증명 없이는 실행하지 않는다. 이는 되돌릴 수 없는 변경으로 취급하며
단순 partition 감소를 rollback으로 제시하지 않는다.

## 9. 후속 이슈 책임

### #41: Kafka4 module과 producer/outbox relay

- governed catalog의 `bluetape4k-kafka4` 연결
- module dependency와 Spring configuration
- outbox claim, publish, acknowledgement, retry/recovery
- owner-token lease/fencing, stale-claim 회수, graceful shutdown
- bounded batch/concurrency/local queue와 backlog fairness/backpressure
- outbox row에서 minimum envelope DTO를 구성하고 필드·producer type allowlist를 검증한 뒤
  Jackson 3로 직렬화하는 producer-side codec
- partition-key builder와 clinic/tenant scope validation
- H2/PostgreSQL/MySQL outbox migration: claim owner token, lease deadline, fenced lifecycle
  상태와 claim/recovery index; 세 dialect의 의미와 repository predicate를 동일하게 검증
- topic/partition 기본 설정과 producer observability
- typed config/readiness/health와 environment drift 검증 연결
- Embedded KRaft 및 singleton Kafka/Redpanda 통합 테스트
- 기존 Spring application event와 Kafka 발행 경계의 명확한 분리

### #42: consumer, schema/versioning, DLT와 replay

- producer minimum envelope와 호환되는 consumer codec hardening과 event type allowlist 확장
- record/header/identifier/JSON depth의 숫자 상한과 oversize fail-closed 처리
- Schema Registry format, subject naming, compatibility mode
- consumer idempotency ledger와 offset commit 경계
- replay/SLO window에 맞춘 ledger retention·cleanup/compaction, index/partition 전략,
  cardinality/저장 용량 상한과 초과 시 fail-closed 또는 backpressure 동작
- retry/DLT/quarantine와 poison-event 처리
- replay 운영 절차와 schema migration 검증
- consumer lag·처리 실패·DLT 관측성

consumer 처리 순서는 `(logicalConsumerId, logicalStreamId, eventId)` unique ledger 확인,
handler의 DB transaction 또는 side-effect ledger 기록, 처리 완료 기록, offset commit이다.
`logicalStreamId`는 topic 교체나 partition 증설 전후에도 같은 event lineage에 대해 안정적이어야
한다. topic, partition과 offset은 수신 provenance로 기록하되 중복 제거 unique key에는 넣지
않는다. replay용 consumer group이 동일 side effect를 실행한다면 기존
`logicalConsumerId`의 dedup scope를 공유하고, 공유하지 않는 replay는 shadow output 또는
별도 승인된 격리 target만 사용할 수 있다. 외부 provider 호출은 provider idempotency key와
결과 ledger를 먼저 확보해야 한다. 성공 응답 뒤 crash가 발생해도 ledger로 reconcile할 수
없는 non-idempotent side effect는 지원하지 않으며 quarantine 또는 별도 승인된 adapter
설계로 보낸다.

#41은 임의 schema registry 정책이나 업무 consumer를 구현하지 않는다. #42는 Kafka3,
RabbitMQ 또는 broker-neutral facade를 추가하지 않는다.

## 10. 검증 전략

Issue #40 자체는 문서 변경만 수행한다.

1. 공식 문서 URL과 로컬 `bluetape4k-kafka4` 지원 근거를 다시 확인한다.
2. spec과 ADR이 Kafka4-only, outbox-first, at-least-once 경계를 동일하게 표현하는지
   검토한다.
3. 2-R의 performance, stability, security, operator/Ops, developer/API,
   user/caller 관점과 main integration에서 P0=0/P1=0을 달성한다.
4. 구현 계획은 #40 문서/이슈 closeout만 대상으로 작성하고 Kotlin 변경을 포함하지 않는다.
5. `git diff --check`와 Markdown 링크·placeholder 검사를 수행한다.

후속 #41/#42는 각각의 구현 spec과 plan에서 다음 검증을 구체화한다.

- focused serializer/partition-key unit tests
- Embedded KRaft producer/consumer tests
- Kafka/Redpanda Testcontainers recovery and Schema Registry tests
- duplicate delivery, restart, retry exhaustion, incompatible schema, replay tests
- concurrent relay fencing, stale-claim recovery, cancellation/graceful-shutdown tests
- oversized record/header/deep JSON rejection과 bounded quarantine tests
- module build와 repository CI

#41/#42 spec은 구현 전에 다음 성능 수용값을 수치화한다. outbox burst fixture와 지속
부하율, publish-to-ack p95/p99, consumer lag catch-up time, retry exhaustion latency,
backlog oldest-age ceiling, broker outage recovery time, partition skew, heap/thread 증가
상한이다. #42는 target ledger cardinality에서 duplicate lookup p95, storage ceiling,
retention cleanup/compaction latency와 replay·republish·partition/topic migration 뒤의
dedup 성능도 측정한다. 또한 serializer allocation/latency smoke와 static DTO topic의
type-header 부재를 검증한다. 이 값과 재현 명령이 없거나 측정 결과가 기준을 넘으면 후속 이슈의
완료와 PR 진행을 막는다.

## 11. 수용 기준과 DoD

- [ ] Kafka4가 유일하게 지원되는 broker/runtime line으로 명시된다.
- [ ] `bluetape4k-kafka4`, Spring Kafka 4, Jackson 3와 governed catalog 권위가
  명시된다.
- [ ] DB outbox authority와 Kafka publish 경계가 분리되고 전역 exactly-once를 주장하지
  않는다.
- [ ] aggregate partition key, event envelope, at-least-once와 consumer idempotency가
  후속 구현의 필수 계약으로 정의된다.
- [ ] 실패 모드, 보안, 관측성, replay와 rollback 원칙이 검증 가능한 형태로 정의된다.
- [ ] relay lease/fencing, bounded backpressure와 stale-claim 복구가 #41의 필수 계약이다.
- [ ] broker 입력 크기/depth/header 상한과 oversize fail-closed 처리가 #42의 필수
  계약이다.
- [ ] #42가 dedup ledger retention/cleanup, index/partition 전략, cardinality/storage 상한과
  target-cardinality duplicate lookup p95를 구현 전에 수치화한다.
- [ ] #41/#42가 성능·복구 수용값과 재현 명령을 구현 전에 수치화하도록 차단 gate가
  정의된다.
- [ ] #41과 #42의 책임과 비목표가 겹치지 않게 분리된다.
- [ ] Kafka3, RabbitMQ, broker-neutral abstraction이 명시적으로 기각된다.
- [ ] exact spec에 대한 2-R 결과가 P0=0/P1=0이다.
- [ ] Kotlin, Gradle, module, Flyway, runtime 설정 변경이 없다.

## 12. 호환성과 변경 관리

이번 이슈는 production behavior를 바꾸지 않으므로 runtime rollback은 없다. 문서 결정이
잘못되었을 때는 spec과 ADR commit을 revert하고 #41/#42 구현을 시작하지 않는다.

후속 구현은 다음 compatibility 조건을 지킨다.

- Kafka4 version은 governed catalog에서만 올린다.
- `bluetape4k-kafka`와 `bluetape4k-kafka4`를 같은 runtime에 섞지 않는다.
- public event의 기존 version 의미를 변경하지 않고 새 version을 추가한다.
- consumer가 지원하지 않는 version은 관대하게 역직렬화하지 않고 명시적으로 격리한다.
- topic/partition/retention 변경은 운영 migration과 rollback 증거를 가진다.
