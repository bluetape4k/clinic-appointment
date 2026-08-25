# appointment-messaging

`appointment-messaging`는 legacy 예약 mutation stream을 Kafka 4 transactional outbox로
전달하는 경로를 소유합니다.

## 계약

- `AppointmentOutboxWriter`는 caller가 소유한 Exposed `transaction {}` 안에서 호출합니다.
- aggregate와 `scheduling_outbox_events` intent는 함께 commit되거나 함께 rollback됩니다.
- `AppointmentOutboxRelay`는 DB transaction 밖에서 Kafka I/O를 수행하고 lease owner/token
  fence로 terminal update를 보호합니다.
- 전달 모델은 at-least-once입니다. broker ACK 뒤 DB update가 실패하면 동일한 불변
  `eventId`가 다시 발행될 수 있습니다.
- 현재 stream은 부분 범위입니다. create/status/cancel과 최종 reschedule mutation만
  포함하며, commitment-v2 controller와 closure의 `PENDING_RESCHEDULE` 중간 전이는
  Issue #41 범위 밖입니다.

## 모듈 API 경계와 재사용

Gradle의 `api(project(":appointment-core"))`는 `TenantClinicScope`,
`AppointmentRecord`, 상태·command context 같은 core 계약을 소비자에게 직접
재사용하게 합니다. `appointment-event`는 `implementation`으로만 연결해
`SchedulingOutboxEvents` 테이블과 `SchedulingOutboxStatus` 구현을 내부에서 재사용하고,
Kafka envelope와 writer API를 불필요하게 event 모듈 전체로 노출하지 않습니다.
공개 계약은 `AppointmentMessagingContracts.kt`와 `AppointmentOutboxWriter.kt`에,
물리적 outbox 테이블 정의는
`appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt`에
있습니다. 이 경계를 지키면 core 모델과 Exposed transaction 재사용은 유지하면서
producer 구현을 교체할 수 있습니다.

기존에 event 모듈의 타입을 messaging의 전이 의존성으로 직접 사용하던 소비자는
자신의 Gradle 선언에 `implementation(project(":appointment-event"))`를 명시해야 합니다.
`event.notification.CancellationReasonCode`를 사용하던 코드는
`commitment.CancellationReasonCode`로 import를 옮깁니다. messaging 공개 API만 사용하는
소비자는 기존 `implementation(project(":appointment-messaging"))` 선언을 유지하고 event
의존성을 추가하지 않습니다.

## 설치

```kotlin
implementation(project(":appointment-messaging"))
```

Spring Boot는 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에서
`AppointmentMessagingAutoConfiguration`을 자동 발견합니다. 기본 topic은
`clinic.appointment.events`이며 명시적으로 allow-list된 topic만 허용합니다. lease, timeout,
claim, topic 설정이 유효하지 않으면 writer를 만들기 전에 fail-fast합니다.

외부 설정 prefix는 `appointment.messaging`입니다.

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

`PLAINTEXT`는 로컬 개발에만 사용합니다. 운영에서는 `SSL` 또는 `SASL_SSL`과
secret-manager reference를 사용하며 credential 값은 outbox나 로그에 저장하지 않습니다.
producer 계약은 `acks=all`, idempotence 활성화, topic 자동 생성 금지, bounded request/delivery
timeout을 fail-closed로 강제합니다. broker도 `auto.create.topics.enable=false`로 설정해야
합니다. Kafka producer metadata에는 broker 정책을 덮어쓸 client 설정이 없기 때문입니다.
Spring Kafka admin의 topic 자동 생성을 끄고 자동 생성 없는 describe 경로를 relay 생성 전에
사용합니다. `SSL`/`SASL_SSL`에서는
`AppointmentKafkaCredentialResolver` bean이
필요하며, reference를 `ssl.*`/`sasl.*` client property로만 변환합니다. secret 값은 로그나
outbox에 노출하지 않습니다.
Spring Kafka publisher가 활성화되면 relay는 `KafkaAdmin`의 topic 자동 생성 없는 metadata
경로를 사용해 outbox row를 claim하기 전에 allow-list의 모든 topic을
`producer-metadata-timeout` 안에 probe합니다. topic 미생성이나 ACL/TLS/SASL 실패를 lease
churn으로 바꾸지 않습니다. custom publisher도 readiness 계약을 구현해야 하며, 구현하지
않으면 fail-closed되어 row를 claim하지 않습니다.
readiness에는 `enabled`, `schemaValid`, `serializerValid`도 포함되며, V22 column/index와
codec self-check가 통과하기 전에는 relay가 ready가 되지 않습니다.

### Kafka4 발송 adapter와 wire 경계

`SpringKafkaAppointmentPublisher`는 `io.bluetape4k.kafka.spring.suspendSend`를 사용해
Spring Kafka의 broker ACK를 기존 `CompletionStage` relay 계약으로 변환합니다. 반환 stage를
취소하거나 publisher를 닫으면 발송 coroutine과 underlying Kafka future를 함께 취소하고,
호출자의 timeout은 broker 결과가 확정되지 않은 상태로 남깁니다.

producer serializer는 기존 `StringSerializer`를 유지합니다. `KafkaCodecs.String`은
`bluetape4k.kafka.codec.value.type` type header를 추가하므로 이 경계에서 교체하면 기존
consumer wire 계약이 바뀝니다. Kafka integration test는 payload round-trip과 해당 header
부재를 검증합니다.

Spring Boot Actuator가 있으면 `appointmentMessagingHealthIndicator` health component를
등록합니다. broker 장애, operator pause, relay hold는 readiness를 `OUT_OF_SERVICE`로
표시하고 애플리케이션 liveness와 분리하며, 잘못된 configuration/schema/serializer는
`DOWN`으로 표시합니다. Actuator readiness group에 이 component를 포함하도록
예를 들어 `management.endpoint.health.group.readiness.include=readinessState,appointmentMessagingHealthIndicator`
를 설정해야 합니다. health detail에는 제한된 readiness boolean과 함께
`diagnostics`를 포함합니다. 진단 항목은 `operation`, bounded `target`, stable `code`,
sanitized `errorClass`, `retryable`로 구성됩니다. schema missing은 재시도하지 않는
계약 오류로, permission denied도 operator 설정 수정이 필요한 비재시도 오류로,
timeout/driver failure는 bounded 재시도 후보로 구분합니다. JDBC URL, exception
message, credential, payload, tenant·clinic·appointment 식별자는 응답과 startup log에
포함하지 않습니다.

Micrometer 연동은 `appointment_outbox_pending`, `appointment_outbox_oldest_age_seconds`,
`appointment_outbox_partition_skew` gauge와 publish/retry/failure counter를 제공합니다.
tenant, clinic, appointment, partition key, payload, credential 값은 metric label에 넣지 않습니다.

HTTP `2xx`는 aggregate와 durable outbox intent가 커밋되었다는 뜻입니다. Kafka ACK를
의미하지 않으며, broker 장애에서는 row를 `PENDING`으로 보존해 relay가 재처리합니다.

## 운영

schema rollback이나 수동 redrive 전에 relay를 pause하고 hold해야 합니다. V22 schema/index와
broker readiness 확인 후 hold를 해제합니다. 상세 절차는
`docs/runbooks/appointment-messaging-operations.md`, alert 규칙은
`docs/alerts/appointment-messaging-rules.yml`을 확인합니다.

## PostgreSQL 벤치마크

production schema claim 경로는 별도 `kotlinx-benchmark` 모듈에서 검증합니다. PostgreSQL
Flyway migration을 적용하고 Hikari와 Exposed를 통해 실제 store를 호출합니다. 저장소
루트에서 Docker 기반 smoke 또는 full 측정을 실행합니다.

```bash
./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark
./gradlew :appointment-messaging-benchmark:mainBenchmark
```

![PostgreSQL 예약 outbox benchmark](../docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-ko.png)

고정 seed, row 수, p50/p95/p99 throughput은 [baseline JSON](../docs/benchmarks/appointment-messaging-postgresql-baseline.json)에서
확인할 수 있습니다. 이 수치는 benchmark 근거이며 배포 SLO가 아닙니다.

| 백분위 | 처리량 |
|------------|------------:|
| p50 | 0.001783 ops/ms |
| p95 | 0.001815 ops/ms |
| p99 | 0.001815 ops/ms |

### PostgreSQL consumer inbox 벤치마크 (Issue #42)

같은 `kotlinx-benchmark` 모듈에서 PostgreSQL V23 schema의 tenant 범위 consumer inbox도
측정합니다. synthetic tenant `7`, clinic `31`을 사용하고 HikariCP와 Exposed를 통해
접속하며, JMH fork 1개·warm-up 2회·측정 5회로 실행합니다. duplicate 경로는
`(logicalConsumerId, logicalStreamId, eventId)` 키를 조회하고 cleanup은 호출당
처리 완료 metadata row를 최대 32건만 삭제합니다. inbox dataset은 10,000건과
100,000건으로 고정했습니다. 아래 값은 측정 근거이며 배포 SLO가 아닙니다.

| 작업 | inbox row 수 | p50 (ops/ms) | p95 (ops/ms) | p99 (ops/ms) |
|------|-----------:|-------------:|-------------:|-------------:|
| bounded cleanup | 10,000 | 0.109366 | 0.137452 | 0.137452 |
| bounded cleanup | 100,000 | 0.043797 | 0.045377 | 0.045377 |
| duplicate lookup | 10,000 | 0.520153 | 0.545037 | 0.545037 |
| duplicate lookup | 100,000 | 0.536926 | 0.578639 | 0.578639 |

raw payload를 포함하지 않는 [consumer baseline JSON](../docs/benchmarks/appointment-messaging-consumer-postgresql-baseline.json)에
PostgreSQL image, row 조건, batch 상한, benchmark 설정과 원본 report 경로를 기록했습니다.
`./gradlew :appointment-messaging-benchmark:mainBenchmark`로 재현할 수 있습니다.
위 chart는 outbox claim 시각화로 유지하고, consumer 수치는 이 표와 전용 artifact를
권위 있는 문서로 사용합니다.

V23 consumer contract에는 5분 processing lease도 저장합니다. 만료된 `PROCESSING` row는
reclaim할 수 있고, active duplicate는 ACK하지 않고 retry로 보냅니다. malformed/tombstone/
schema 거부 record는 broker provenance와 payload SHA-256만 rejected ledger에 남기며,
quarantine된 inbox row는 dedup tombstone으로 유지합니다.

모듈 집중 검증은 다음 명령으로 실행합니다.

```bash
./gradlew :appointment-messaging:test
```
