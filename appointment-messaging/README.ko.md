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
Spring Boot Actuator가 있으면 `appointmentMessagingHealthIndicator` health component를
등록합니다. broker 장애, operator pause, relay hold는 readiness를 `OUT_OF_SERVICE`로
표시하고 애플리케이션 liveness와 분리하며, 잘못된 configuration/schema/serializer는
`DOWN`으로 표시합니다. Actuator readiness group에 이 component를 포함하도록
예를 들어 `management.endpoint.health.group.readiness.include=readinessState,appointmentMessagingHealthIndicator`
를 설정해야 합니다. health detail에는 제한된 readiness boolean만 포함됩니다.

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

모듈 집중 검증은 다음 명령으로 실행합니다.

```bash
./gradlew :appointment-messaging:test
```
