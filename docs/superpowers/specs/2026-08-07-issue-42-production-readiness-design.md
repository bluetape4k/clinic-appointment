# Issue #42 production readiness follow-up 설계

## 목적

Issue #42의 Kafka 4 외부 consumer 계약을 실제 배포 경계까지 닫는다. 이번 후속은
이미 구현된 inbox/runtime/V23/schema resource를 다시 설계하지 않고, production에서
실패하기 쉬운 연결·운영 경계를 검증 가능한 코드와 문서로 보강한다.

## 범위와 비범위

### 범위

1. MySQL Flyway V23을 실제 MySQL 8 singleton launcher로 검증하고 migration contract에
   필요한 테이블·컬럼·index·schema metadata를 고정한다.
2. Schema Registry base URI, subject, timeout, HTTPS/loopback 정책과 credential resolver를
   Spring Boot binding으로 연결한다. registry endpoint 또는 인증 실패는 consumer가
   준비된 것으로 보고하지 않는다. Basic credential 자체는 설정 값이나 log에 저장하지
   않는다.
3. `ConcurrentMessageListenerContainer`를 실제 Kafka 4 singleton으로 기동해 manual ack,
   retry/recovery, consumer crash와 second-member rebalance를 검증한다. 운영 group offset을
   되감는 API는 만들지 않는다.
4. consumer 처리·lag·retry/quarantine/replay·DB lock contention·retention을
   low-cardinality Micrometer metric과 bounded cleanup으로 노출한다. deployment SLO는
   계약·측정 명령·chart를 제공하되, production 클러스터 값은 자격증명이 없으면 PENDING으로
   남긴다.
5. replay를 production Kafka source adapter와 authenticated actor/tenant authorization
   port에 연결한다. audit는 먼저 기록하고 dry-run을 통과한 별도 replay group만 사용한다.

### 비범위

- 실제 production MySQL/Kafka/Schema Registry에 접속하거나 credential을 생성하는 작업.
- Kafka 3, RabbitMQ, broker-neutral abstraction, offset rewind, raw payload replay.
- 현재 API의 JWT claim 모델을 재설계하는 작업. 기존 `SecurityContext`에서 얻은 actor와
  tenant scope를 replay authorization port에 전달하는 최소 wiring만 한다.

## 설계 결정

### MySQL migration

Flyway V23 SQL은 기존 `scheduling_*` 이름을 유지한다. H2/MySQL/PostgreSQL migration
support가 공통 contract를 호출하고, MySQL test는 `MySQLServer.Launcher` singleton에서
clean/migrate 후 `information_schema`를 조회한다. production 검증은 동일 command에
`JDBC_URL`, `DB_USER`, `DB_PASSWORD`를 주입할 수 있는 별도 운영 절차로 문서화하되,
기본 CI는 local singleton만 실행한다.

### Schema Registry

`AppointmentSchemaRegistryBindingProperties`는 `enabled`, `baseUri`, `subject`, `timeout`,
`authentication`을 immutable constructor-bound로 가진다. `enabled=false`면 local static
validator만 사용하고, `enabled=true`면 URI 정책·credential resolver·`/config/{subject}`
조회가 모두 성공해야 한다. 기본 허용은 HTTPS이며, 테스트용 loopback HTTP만 명시적으로
허용한다. resolver는 `Authorization` header를 요청 직전에 만들고 값은 예외·log·metric에
포함하지 않는다. 호환성은 `BACKWARD_TRANSITIVE` exact match로 제한한다.

### Listener lifecycle

공통 `AppointmentKafkaConsumerListener`는 container의 `ConsumerRecord`와
`Acknowledgment`를 runtime으로 넘긴다. error handler는 기존 bounded backoff와
metadata-only quarantine을 유지한다. integration test는 첫 consumer가 handler에서
한 번 crash한 뒤 recover되는 경로와 같은 group의 두 번째 container가 partition을
재할당받는 경로를 실제 broker에서 확인한다.

### Observability, SLO, retention

metric 이름은 `appointment_consumer_*` namespace 아래에 두고 label은 `consumer`,
`stream`, `outcome`, `failure_code`처럼 bounded 값만 허용한다. tenant/clinic/event,
payload, partition key는 label로 사용하지 않는다. 처리/재시도/quarantine/duplicate,
replay 결과, oldest age, lag, inbox transaction latency와 cleanup count를 기록한다.
processed/quarantined/replay audit는 status·cutoff·batch 조건을 함께 사용해 bounded
delete하며 `PROCESSING` 행은 삭제하지 않는다. chart는 PostgreSQL `kotlinx-benchmark`
결과와 source JSON을 단일 원천으로 사용하고, 실제 deployment SLO와 benchmark 수치를
혼동하지 않도록 표기한다.

### Replay authorization

`AppointmentReplayAuthorizer`는 authenticated actor, requested tenant/clinic scope,
consumer identity, offset range를 받아 승인 여부를 반환한다. `AppointmentReplayService`
는 audit insert 전에 authorizer를 호출하고, actor/tenant hash와 결과만 audit에 저장한다.
production `KafkaAppointmentReplaySource`는 request 전용 group id와 bounded poll/close를
사용하며 operations group을 수정하지 않는다. source가 없거나 authorization이 실패하면
side effect 없이 거부한다.

## 실패·보안·롤백 계약

- MySQL metadata가 기대 schema와 다르면 readiness와 migration test를 실패시킨다.
- registry URL이 HTTPS/loopback 정책을 위반하거나 auth/compatibility가 실패하면
  `registryReachable=false`로 fail-closed 한다.
- listener crash는 offset commit 전에 예외가 전파되어 redelivery/rebalance가 가능해야
  한다. retry exhausted record만 quarantine 후 ack한다.
- metric/log/audit/quarantine에는 raw payload와 PII를 저장하지 않는다.
- retention cleanup은 dry-run 가능한 bounded batch이며 rollback은 migration down이
  아니라 deployment hold와 forward migration으로 수행한다.
- production evidence가 없는 항목은 CI/local evidence로 대체하지 않고 PENDING으로
  기록한다.

## 완료 조건

1. MySQL V23 contract가 singleton integration test에서 통과한다.
2. Schema Registry Spring binding, auth header, endpoint path, fail-closed readiness가
   positive/negative test로 고정된다.
3. 실제 Kafka 4 container crash/rebalance test가 manual ack와 recovery를 증명한다.
4. Micrometer metric, bounded retention, replay authorization/source adapter가 unit 및
   integration test와 운영 runbook에 반영된다.
5. PostgreSQL `kotlinx-benchmark`가 duplicate/cleanup/lock contention을 측정하고 chart와
   EN/KO 문서가 source-equivalent로 갱신된다.
6. production credential/cluster를 사용할 수 없는 경우 해당 DoD를 명시적인 PENDING으로
   남긴다.

## 참고

- [Issue #42](https://github.com/bluetape4k/clinic-appointment/issues/42)
- [Issue #42 선행 설계](2026-08-06-issue-42-external-consumers-schema-design.md)
- [Confluent Schema Registry REST API](https://docs.confluent.io/cloud/current/sr/sr-rest-apis.html)
- [Schema Registry compatibility API](https://docs.confluent.io/platform/current/schema-registry/develop/api.html)
