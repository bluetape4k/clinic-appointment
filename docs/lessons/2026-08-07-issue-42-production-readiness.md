# Issue #42 production readiness lesson

Issue #42의 consumer production-readiness follow-up에서는 코드가 컴파일되고 단위
테스트가 통과하는 것만으로 운영 준비를 주장할 수 없다는 경계를 다시 확인했다.
MySQL migration, Schema Registry 인증, Kafka crash/rebalance, consumer lag와 DB
lock contention, replay 권한·보존은 각각 별도의 관측 증거가 필요하다.

## 결정과 결과

- V23 migration은 H2/MySQL/PostgreSQL singleton에서 동일한 table·column·PK·index
  계약으로 검증했다. 실제 production MySQL 연결 증거는 환경 자격증명 부재로
  `PENDING`이다. 배포/staging endpoint를 변경하지 않는 read-only metadata smoke
  명령과 Flyway apply 전환 절차를 operations runbook에 고정했다.
- Schema Registry는 endpoint URI와 Basic credential을 검증하고, HTTP는 loopback만
  허용하며 remote compatibility 실패는 fail-closed한다. production endpoint·TLS·인증
  wiring은 배포 환경 검증이 남아 있다.
- Kafka listener는 manual ACK 전에 runtime을 호출하고, crash-before-ack와 두 번째
  consumer의 rebalance integration으로 redelivery/recovery를 검증했다. 운영 broker의
  실제 crash/rebalance는 아직 실행하지 않았다.
- Micrometer consumer metrics는 lag, oldest processing age, outcome, retry/quarantine,
  inbox transaction, replay, retention을 low-cardinality aggregate로 제공한다. tenant,
  clinic, partition, payload를 label이나 로그에 넣지 않는다.
- replay는 operations group offset을 rewind하지 않는 `KafkaAppointmentReplaySource`와
  `AppointmentReplayActor` 기반 tenant/role authorization을 library boundary로 제공한다.
  인증된 application adapter와 실제 production replay authorization은 별도 wiring이
  필요하므로 endpoint를 자동 공개하지 않는다.
- `kotlinx-benchmark` PostgreSQL smoke는 `postgres:18-alpine`, seed `42`, 10,000/100,000
  rows에서 cleanup·duplicate lookup·동일 key 2-participant insert contention을 함께
  측정했다. contention p95는 각각 `6.983680 ms/op`, `6.188237 ms/op`였고, 이 값은
  deployment SLO가 아니다.

## 재사용할 guard

benchmark report와 chart는 raw JMH JSON에서 collector/validator/generator를 통해
생성한다. 보고서에는 `deploymentSloEvidence=false`를 명시해 local capacity evidence와
production SLO를 섞지 않는다. 운영 rollout 전에는 동일한 측정 항목을 target broker,
CPU/connection pool, MySQL lock-wait, consumer lag, retention deletion metrics와 함께
수집하고, 그 결과를 별도 deployment evidence로 보존한다.

## 남은 외부 증거

실제 production MySQL migration, Schema Registry endpoint/authentication, broker
crash/rebalance, deployment SLO·lag·lock contention, replay adapter/authorization 및
운영 metrics·retention은 대상 환경 접근과 승인된 synthetic data가 준비될 때까지
`PENDING`이다. 이 상태를 코드 테스트 성공으로 대체하지 않는다.
