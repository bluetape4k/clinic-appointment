# Appointment Consumer Replay 운영 Runbook

이 문서는 `appointment-notification-v1` 및 `appointment-statistics-v1` consumer의
quarantine event를 승인된 별도 replay group으로 재처리하는 절차를 정의한다.

## 안전 경계

- replay 요청은 `approver`, `tenantGroupId`, `clinicId`, `fromOffset`, `toOffset`,
  `dryRun`을 모두 포함해야 한다. 필요하면 단일 `partition`을 지정하며, request id는
  consumer identity/scope/partition/range/승인자 hash에 묶여 다른 범위로 재사용할 수 없다.
- `approver`와 tenant 권한은 request body를 신뢰하지 않는다. 인증된 보안 주체에서
  `AppointmentReplayActor`를 만들고, actor subject가 `approver`와 같으며
  `tenantGroupIds`에 요청 tenant가 포함되고 해당 tenant의 `clinicIdsByTenant`에 요청
  clinic이 포함되며 `APPOINTMENT_REPLAY_OPERATOR` 역할을 가져야 한다. 조건을 하나라도
  만족하지 않으면 audit row를 만들기 전에 거부한다.
- Kafka operations group의 offset을 rewind하지 않는다. 실행 source는
  `appointment-<consumer>-replay-<requestId>-v1` request 전용 group만 사용한다.
- inbox key는 원래 consumer의 `(logicalConsumerId, logicalStreamId, eventId)`를
  유지하므로 side effect는 기존 dedup 경계를 따른다.
- production source adapter는 allow-listed consumer/stream identity에 고정된다. 요청 identity를
  임의로 바꾸어 dedup 경계를 우회할 수 없으며, decode 후 tenant/clinic scope가 일치하지 않는
  record는 handler를 호출하지 않는다.
- replay audit에는 request id, scope, offset, 승인자, 상태와 시간만 저장한다.
  원문 Kafka value, 환자 식별자, recipient/provider payload는 저장하거나 출력하지 않는다.

## 실행 순서

1. quarantine metadata에서 대상 tenant/clinic과 offset 범위를 확인한다.
2. `AppointmentReplayRequest`를 만들고 bounded offset 범위(최대 100,000)를 지정한다.
3. 같은 request id로 `dryRun=true`를 먼저 실행한다. 이 단계는 audit를 `DRY_RUN`으로
   기록하고 replay source/handler를 호출하지 않는다.
4. dry-run 결과와 승인자/범위를 운영 기록으로 확인한 뒤, 같은 request id와 동일 범위로
   승인된 `dryRun=false` 요청을 실행한다. 서비스는 원자적인 execution claim을 기록한 뒤
   source를 호출한다. 같은 request id의 동시 실행은 두 번째 호출이 side effect 없이
   현재 audit 상태를 반환한다.
5. 결과가 `EXECUTED`인지 확인하고 `replayedRecords`를 기록한다. 실패하면 audit가
   `REJECTED`가 되며 예외 메시지에는 raw payload가 포함되지 않는다.

## 중단·복구

- schema readiness가 `DOWN`이거나 DB inbox migration이 없으면 replay를 실행하지 않는다.
- source가 오류를 반환하면 operations group을 건드리지 않은 채 `REJECTED`로 종료하고,
  원인을 로그의 bounded failure class로만 확인한다.
- `KafkaAppointmentReplaySource`는 `ConsumerFactory`로 request 전용 group을 만들고
  topic partition을 bounded offset 범위에 assign한다. 최대 100,000건·설정된 duration을
  넘으면 실패하며 operations group의 commit/rewind API를 호출하지 않는다.
- 같은 request id의 동일한 dry-run 재호출은 기존 audit 상태를 반환한다. 실행 claim 뒤
  프로세스가 종료되어 `REQUESTED`에 남은 경우 중복 실행을 막기 위해 자동 재실행하지
  않는다. 운영자는 원래 inbox dedup과 장애 원인을 확인하고 새 승인/request id를 발급한다.
  이미 `EXECUTED` 또는 `REJECTED`인 요청은 재실행하지 않는다.
- 범위가 잘못되었거나 다른 tenant/clinic이면 새 request id를 만들지 말고 요청을 폐기한
  뒤 접근 권한과 quarantine provenance를 재확인한다.

## 관찰 항목

- `scheduling_appointment_consumer_inbox`: consumer별 처리 상태와 attempt 수
- `scheduling_appointment_consumer_quarantine`: failure code와 provenance/hash
- `scheduling_appointment_consumer_replay_audit`: 승인·dry-run·실행 상태

세 테이블 모두 raw Kafka value를 포함하지 않는 것이 정상이다. payload가 보이는
로그/감사 출력은 보안 결함으로 간주하고 즉시 해당 sink를 격리한다.

## 운영 metrics와 보존

- `appointment_consumer_lag`, `appointment_consumer_oldest_age_seconds`는 broker
  partition/tenant를 label로 넣지 않는 aggregate gauge다. lag를 읽지 못한 경우
  `appointment_consumer_lag_unavailable_total`을 증가시키고 값을 0으로 위장하지 않는다.
- 처리 결과는 `appointment_consumer_processed_total`,
  `appointment_consumer_duplicate_total`, `appointment_consumer_retry_total`,
  `appointment_consumer_quarantined_total`로 확인한다. replay 결과는
  `appointment_consumer_replay_total{status=...}`, inbox transaction 시간은
  `appointment_consumer_inbox_transaction_seconds` histogram으로 확인한다.
- `appointment.messaging.retention.enabled=true`일 때 retention service가
  `processed`, `quarantined`, `rejected`, `quarantine`, `DRY_RUN/EXECUTED/REJECTED replay audit`
  terminal row를 bounded batch로 삭제한다. `PROCESSING` inbox와 `REQUESTED` replay audit는
  보존하며, 삭제량은
  `appointment_consumer_retention_deleted_total{table=...}`로 기록한다. payload와
  request scope는 metric label이나 로그에 넣지 않는다.
- Spring scheduler를 사용하려면 `appointment.messaging.retention.scheduler-enabled=true`를
  별도로 켜고, 외부 CronJob을 쓰는 배포에서는 두 실행기를 동시에 켜지 않는다.

현재 자동화 근거는 local PostgreSQL singleton, Kafka crash/rebalance integration,
static/HTTP Schema Registry contract, 그리고 `kotlinx-benchmark` lock-contention
smoke 결과까지다. 실제 production MySQL migration, Schema Registry 인증/endpoint,
broker crash/rebalance, deployment SLO·lag·lock-wait, 운영 replay adapter 연결은 대상
환경의 자격증명과 관측 데이터가 제공되기 전까지 `PENDING`이다.
