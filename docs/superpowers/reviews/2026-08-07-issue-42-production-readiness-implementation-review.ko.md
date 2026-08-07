# Issue #42 production readiness 구현 리뷰

## 리뷰 범위와 기준

현재 worktree의 Issue #42 후속 diff를 승인된 설계·계획과 대조했다. 대상은
`appointment-api`, `appointment-messaging`, `appointment-messaging-benchmark` 및
운영 문서/CI이다. Type-A Step 6-R의 여섯 관점과 현재 세션 통합 리뷰를 적용했으며,
운영 환경 자격증명 없이 local/singleton 증거를 production 증거로 승격하지 않았다.

## Verifier traceability

| 요구사항 | 구현·문서 근거 | 현재 증거 | 상태 |
|---|---|---|---|
| MySQL V23 migration metadata | `AppointmentMessagingMigrationTestSupport`, `FlywayMySQLMigrationTest`, operations runbook | MySQL 8 singleton Flyway clean→V23 및 metadata contract 통과; endpoint metadata-only hook은 미설정 시 skip | PASS / production PENDING |
| Schema Registry endpoint/auth/fail-closed | typed binding, JDK reader, auto-configuration, readiness validator | static/HTTP path·Basic auth·URI 정책·unavailable/incompatible negative tests 통과 | PASS / production endpoint PENDING |
| Kafka manual ack/crash/rebalance | `AppointmentKafkaConsumerListener`, error handler, integration tests | Kafka singleton에서 crash 전 redelivery와 second-member rebalance recovery 통과 | PASS / production broker PENDING |
| lag/retry/quarantine/retention metrics | `AppointmentConsumerMetrics`, listener lag sampler, retention service/scheduler, alerts/runbooks | bounded labels, retry/no-ack, cleanup and metric tests 통과 | PASS / deployment SLO PENDING |
| PostgreSQL lock contention evidence | `PostgreSqlAppointmentConsumerBenchmark`, collector/validator/chart, EN/KO README | `kotlinx-benchmark` smoke report와 chart source 검증 통과 | PASS / deployment lock-wait PENDING |
| replay adapter/auth/scope | `KafkaAppointmentReplaySource`, `AppointmentReplayAuthorizer`, `AppointmentReplayService` | dry-run/claim/idempotency, tenant+clinic+identity+partition scope tests 통과 | PASS / authenticated production wiring PENDING |

## Six-lens findings

| 관점 | 최신 결과 | 근거와 처분 |
|---|---|---|
| Performance | P0=0, P1=0, P2=2 | projection version fence, lease CAS, bounded replay/cleanup, non-blocking lag sampler를 반영했다. PostgreSQL benchmark는 lock contention smoke만 제공하며 uncontended baseline과 regression threshold, 배포 lock-wait는 별도 측정이 필요하므로 P2/PENDING으로 남긴다. |
| Stability | P0=0, P1=0, P2=1 | lease expiry/reclaim, repeated crash quarantine, stale completion fence, retryable registry outage, Kafka crash/rebalance를 테스트했다. production broker crash/rebalance와 listener별 recoverer identity wiring은 target deployment 확인 전까지 P2/PENDING이다. |
| Security | P0=0, P1=0, P2=0 | registry credential 비로그·bounded response, fail-closed unavailable, replay approver/tenant/clinic/identity/partition 검증, raw payload 미저장을 테스트·runbook으로 고정했다. |
| Operator/Ops | P0=0, P1=0, P2=2 | readiness/lag/retry/quarantine/replay/retention metric과 scheduler 중복 실행 방지를 문서화했다. 실제 endpoint 인증, deployment SLO, lag, lock-wait, retention deletion 관측은 target 환경이 없어 PENDING이다. |
| Developer/API | P0=0, P1=0, P2=0 | Kotlin 불변 value/typed exception, backward-compatible overload, Exposed transaction 경계를 유지하고 API/KDoc/runbook을 실제 symbol에 맞췄다. |
| User/Caller | P0=0, P1=0, P2=0 | replay 실행 순서, stuck `REQUESTED` 재실행 금지, scope mismatch 의미, MySQL metadata smoke 명령과 production non-goal을 README/runbook에 기록했다. |

## 통합 판정

- P0: **0**
- P1: **0**
- P2: **5** (production endpoint/cluster evidence, benchmark regression baseline, listener별 recoverer identity)
- P3: **0**

P2 항목은 구현 결함으로 숨기지 않고 rollout 전 외부 증거가 필요한 후속으로 분리했다.
현재 diff에서 production credential을 생성하거나 외부 broker/database를 변경하는 작업은
수행하지 않았다. MySQL endpoint test는 `Flyway.clean()`이나 migration apply를 호출하지
않으며, read-only metadata assertion만 실행한다.

## Fresh verification

- `./gradlew :appointment-messaging:test --no-daemon --console=plain` — **103 tests passed** (including concurrent first-claim replay and listener crash/rebalance coverage).
- `./gradlew :appointment-api:test --no-daemon --console=plain --tests '*FlywayMySQLMigrationTest*'` — **3 passed, 1 skipped** (optional production endpoint absent).
- `./gradlew :appointment-api:test --no-daemon --console=plain --tests '*FlywayMigrationTest*' --tests '*FlywayMySQLMigrationTest*' --tests '*FlywayPostgreSQLMigrationTest*' --tests '*AppointmentStatsProjectionConsumerTest*'` — **11 passed, 1 skipped**.
- `./gradlew :appointment-notification:test --no-daemon --console=plain` — **133 tests passed**.
- Kafka singleton integration — crash-before-ack/rebalance and duplicate redelivery passed in the full messaging run.
- PostgreSQL `kotlinx-benchmark` smoke — report/chart source already generated and validated; values remain benchmark evidence, not deployment SLO.
- `./gradlew :appointment-api:test --no-daemon --console=plain` — Context Mode 300-second tool limit cancelled the long-running process before a result was returned; this is recorded as **not-tested**, not as a test failure. The targeted migration and projection checks above remain the available API evidence.
- `git diff --check`, YAML parsing, `actionlint`, benchmark script tests, and chart/validator parity are final gates after the last documentation edit.

## Verdict

**PASS for implementation review; production rollout evidence remains PENDING.** PR creation may
continue after final diff/static checks. Merge requires a fresh approval tied to the exact PR head
after CI and review state are re-read.
