# 예약 messaging 운영

## 범위와 불변 조건

writer는 legacy mutation과 동일한 database transaction에서 비식별화한 예약
이벤트를 기록합니다. relay는 at-least-once 방식이므로 `eventId`를 다시 보낼 수
있으며, consumer는 자체 idempotency 처리에 event ID를 사용해야 합니다(Issue #42).

Issue #41의 stream은 의도적으로 부분 범위만 다룹니다. commitment-v2나 closure의
`PENDING_RESCHEDULE` 전이가 포함된 것으로 해석하지 않습니다.

durable outbox write를 사용할 수 없으면 API는 제한된 `Retry-After` header와 함께
`503`을 반환합니다. 안내된 간격이 지난 뒤 동일한 idempotency key로 재시도하며,
대체 예약이나 event ID를 만들지 않습니다.

## Hold and recovery (보류와 복구)

1. relay를 **paused**와 **held** 상태로 설정합니다. 두 gate가 새 claim을 멈춥니다.
2. 진행 중인 전송이 최대 10초 동안 drain되도록 기다립니다. 취소되었거나 lease를
   잃은 전송에는 terminal state를 기록하지 않습니다.
3. `appointment_outbox_pending`, `appointment_outbox_oldest_age_seconds`,
   `appointment_outbox_partition_skew`, lease 만료, retry age, broker error code를
   확인합니다. gauge는 제한된 aggregate 신호이므로 row 단위 조사는 제한된 SQL
   런북 쿼리를 사용하고, tenant·clinic·appointment·partition 값을 metric label에
   넣지 않습니다.
4. readiness snapshot에서 `enabled`, `configurationValid`, `schemaValid`,
   `serializerValid`, `brokerAvailable`, `relayPaused`, `relayHeld`를 확인합니다.
   Spring Kafka publisher는 claim 전에 allow-list된 모든 topic의 metadata를 probe하며
   `producer-metadata-timeout`이 probe 시간을 제한합니다. 따라서 topic 없음,
   ACL 거부, TLS/SASL 실패가 lease churn을 만들지 않고 row를 그대로 둡니다.
   configuration 오류는 fail-closed이며, 유효한 writer도 broker 장애 중에는 row를
   `PENDING`으로 남길 수 있습니다.
5. broker/TLS/SASL 또는 schema configuration을 수정합니다. 운영 producer 설정은
   `acks=all`, idempotence 활성화, auto-create 비활성화, request/delivery/metadata
   timeout 제한을 유지해야 합니다. broker에 `auto.create.topics.enable=false`를
   설정하며 producer는 이 broker 정책을 덮어쓸 수 없습니다. 보안 protocol에서는
   `AppointmentKafkaCredentialResolver` reference와 최소 권한의
   `ssl.*`/`sasl.*` 출력을 확인하되 secret 값을 로그에 복사하지 않습니다.
6. 기존 `event_id`로 claim을 재개합니다. payload를 수정하거나 대체 ID를 만들지 않습니다.

## Redrive and rollback (재처리와 롤백)

수동 redrive는 운영자 작업입니다. row의 event ID를 보존하고 reason code를 change
log에 기록합니다. V22 rollback 전에 relay를 pause합니다. V22 column은 additive이므로
삭제해서는 안 됩니다. 오래된 owner/token update의 영향 row 수는 0이어야 합니다.

## 에스컬레이션

수동 oldest-age query가 180초를 넘거나 lock wait p95가 50 ms를 넘을 때, lease churn
또는 broker pause가 지속될 때, 허용하지 않은 topic/key가 관찰될 때, 같은 aggregate의
순서가 깨질 때 에스컬레이션합니다. relay는 `appointment_outbox_lease_lost_total`,
`appointment_outbox_contract_rejected_total`, `appointment_outbox_failed_total`,
`appointment_outbox_retry_total`(allow-list된 안정적인 failure code만 tag),
`appointment_outbox_broker_pause_total`을 노출하지만 tenant, clinic, appointment,
raw payload label은 노출하지 않습니다.
metric에는 제한된 backlog/oldest-age/partition-skew gauge와 event type,
outcome/status, attempt, 안정적인 failure code counter가 포함됩니다. 제한된 change
log에서 opaque event ID를 참조할 수는 있지만 tenant, clinic, appointment,
partition-key 값을 metric이나 일반 log에 복사하지 않습니다. 환자 데이터, credential,
원문 reason text, payload JSON도 첨부하지 않습니다.

## Consumer readiness와 운영 SLO

consumer 측은 aggregate low-cardinality 신호인 `appointment_consumer_lag`,
`appointment_consumer_oldest_age_seconds`, `appointment_consumer_lag_unavailable_total`,
`appointment_consumer_processed_total`, `appointment_consumer_duplicate_total`,
`appointment_consumer_retry_total`, `appointment_consumer_quarantined_total`,
`appointment_consumer_inbox_transaction_seconds`, `appointment_consumer_replay_total`,
`appointment_consumer_retention_deleted_total`을 노출합니다. tenant, clinic, partition,
request ID, payload label은 허용하지 않습니다.

초기 운영 threshold는 10분 동안 lag 100건 초과, oldest processing age 180초 초과,
10분 동안 retry/quarantine 증가, inbox transaction p95 50 ms 초과입니다. 이 threshold는
alert 기본값이며 운영 SLO의 증거가 아닙니다. rollout gate를 닫기 전에 대상 broker,
pool size, CPU architecture, database lock-wait 측정값으로 배포 SLO를 확인합니다.

Retention은 기본적으로 비활성화되어 있습니다. 활성화하면 bounded retention service는
terminal 상태인 processed/quarantined/rejected row와 `DRY_RUN`/`EXECUTED`/`REJECTED`
replay-audit row만 삭제합니다. `PROCESSING` inbox row와 `REQUESTED` replay audit는
그대로 둡니다. 프로세스 내부에서 실행하려면 `appointment.messaging.retention.scheduler-enabled=true`도
설정합니다. 외부 CronJob을 사용하는 배포에서는 프로세스 내부 scheduler를 비활성화해야
합니다. 삭제 건수를 기록하고 raw Kafka 값, credential, request scope가 metric과 log에
없는지 확인합니다.

## MySQL 운영 metadata readiness

API migration suite는 H2, PostgreSQL, MySQL singleton fixture에서 V23 consumer metadata,
V24 aggregate lock, V25 replay hash 계약을 검증합니다.
배포 또는 staging MySQL endpoint는 credential을 별도 경로로 제공하면 동일한 계약을
**read-only metadata smoke test**로 실행할 수 있습니다.

```bash
APPOINTMENT_PRODUCTION_MYSQL_JDBC_URL='jdbc:mysql://host:3306/database?sslMode=VERIFY_IDENTITY' \
APPOINTMENT_PRODUCTION_MYSQL_USER='read_only_user' \
APPOINTMENT_PRODUCTION_MYSQL_PASSWORD='provided-by-secret-manager' \
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest.production MySQL metadata readiness is verified when endpoint is configured'
```

테스트는 JDBC metadata, V23~V25 column, primary key, index, 선택한 MySQL catalog를 읽으며
`Flyway.clean()`을 실행하거나 migration을 적용하지 않습니다. 운영에 V23~V25를 적용하는
것은 별도의 change-window 작업입니다. 먼저 migration preflight를 실행하고 Flyway
history와 DDL 출력을 수집한 뒤, 배포에서 승인한 account로 적용하고 metadata smoke
test를 다시 실행합니다. endpoint, username, password, tenant, clinic, patient 값은
source, CI log, metric에 넣지 않습니다. 해당 endpoint 증거를 rollout record에 첨부하기
전까지 운영 MySQL migration 검증은 `PENDING`입니다.

V25 적용 뒤에도 기존 replay audit row는 `hash_version=1`/`partition_number=NULL`로
남습니다. 이 row는 partition 범위를 복원할 수 없으므로 자동 재실행하지 않고, 대상
provenance를 다시 확인한 뒤 새 승인과 새 request id를 발급합니다.

애플리케이션이 인증된 `AppointmentReplayActor`를 제공하기 전까지 Replay는 library
boundary입니다. actor는 approver와 일치하고 요청한 tenant·clinic allow-list를 포함하며
`APPOINTMENT_REPLAY_OPERATOR` 권한을 가져야 합니다. 권한 없는 request는 audit row를
기록하기 전에 거부합니다. `KafkaAppointmentReplaySource`는 고정된 logical
consumer/stream identity, 선택적으로 하나의 partition, decode된 tenant/clinic scope에
바인딩됩니다. operations group offset을 변경하지 않고 request 전용 consumer group과
제한된 range/record/time limit을 사용합니다.
