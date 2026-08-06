# Issue #42 외부 consumer와 schema evolution lesson

Issue #42에서는 Kafka 4 appointment event를 알림과 통계 consumer가 안전하게
구독하도록 계약·inbox·운영 경계를 함께 고정했다. 이 문서는 구현 후 재현 가능한
결정과 검증 근거만 남긴다.

## 결정

- schema 계약은 현재 Jackson 3 envelope와 parity를 갖는 JSON Schema v1 resource로
  저장하고, registry readiness는 `BACKWARD_TRANSITIVE` compatibility를 요구한다.
  registry 장애나 호환성 실패는 consumer를 시작시키지 않는다.
- dedup key는 Kafka physical 위치가 아니라
  `(logicalConsumerId, logicalStreamId, eventId)` composite key다. topic, partition,
  offset, schema version, tenant/clinic scope, payload SHA-256은 provenance metadata로만
  남긴다.
- inbox와 quarantine에는 payload나 예외 원문을 저장하지 않는다. retryable failure는
  bounded attempt를 사용하고, 소진 시 metadata-only quarantine 후 ACK한다.
- 알림 group은 `appointment-notification-v1`, 통계 group은
  `appointment-statistics-v1`로 분리했다. replay는 운영 group offset rewind가 아니라
  별도 replay group과 감사 row를 사용하는 dry-run-first 경로다.

## PostgreSQL kotlinx-benchmark 근거

`./gradlew :appointment-messaging-benchmark:mainBenchmark`를 PostgreSQL
`postgres:18-alpine`에서 실행했다. HikariCP `DataSource`를 Exposed `Database.connect`
에 연결하고 Flyway V23 전체 migration을 적용한 뒤 실제 JDBC inbox store를 호출했다.
JMH는 1 fork, warm-up 2회, 측정 5회이며 synthetic tenant `7`, clinic `31`만 사용한다.

| 작업 | inbox rows | p50 (ops/ms) | p95 (ops/ms) | p99 (ops/ms) |
|------|-----------:|-------------:|-------------:|-------------:|
| bounded cleanup | 10,000 | 0.044115 | 0.045486 | 0.045486 |
| bounded cleanup | 100,000 | 0.046692 | 0.048205 | 0.048205 |
| duplicate lookup | 10,000 | 0.567588 | 0.575034 | 0.575034 |
| duplicate lookup | 100,000 | 0.554740 | 0.571404 | 0.571404 |

원본 JSON과 실행 조건은
[`appointment-messaging-consumer-postgresql-baseline.json`](../benchmarks/appointment-messaging-consumer-postgresql-baseline.json)에
보존했다. 값은 로컬 Docker 환경의 benchmark evidence이며 배포 SLO가 아니다.
기존 outbox claim chart는 그대로 유지하고, consumer 수치는 전용 JSON과 README 표를
권위 있는 source-equivalent 문서로 사용한다.

## 미검증 항목과 후속 조치

- 실제 운영 Schema Registry endpoint와 인증 방식은 환경별 설정이 필요하므로 static
  registry와 HTTP client 계약까지만 자동 검증했다.
- 로컬 benchmark는 단일 thread와 Testcontainers PostgreSQL을 사용한다. 운영 rollout
  전에는 배포 CPU/architecture, connection pool, broker lag, lock wait를 포함한
  capacity run을 별도 수행해야 한다.
- 잘못된 envelope는 신뢰 가능한 tenant/clinic/event metadata를 만들 수 없으므로
  현재 runtime에서는 raw payload 없이 예외를 반환한다. 운영 ingest 계층에서 broker
  provenance를 확보하는 quarantine adapter가 필요하면 후속 issue로 분리한다.
