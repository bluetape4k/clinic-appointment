# 이슈 #42 production readiness follow-up 구현 계획

> **실행 계약:** 이 계획은 승인된 Type-A 후속 범위를 실행한다. 각 단계에서
> 먼저 실패하는 테스트/검증을 만들고, 영향을 받은 모듈을 순차적으로 검증한다.

## 목표

MySQL V23 migration, Schema Registry Spring wiring, 실제 Kafka 4 listener lifecycle,
consumer observability/SLO evidence, replay authorization/source/retention을 현재
Issue #42 구현에 최소 diff로 연결한다.

## 변경 파일 지도

| 영역 | 예상 파일 | 결과 |
|---|---|---|
| migration | `appointment-api/src/test/.../migration/*`, `appointment-api/src/main/resources/db/migration/mysql/V23*` | MySQL V23 metadata contract |
| registry | `appointment-messaging/src/main/kotlin/.../AppointmentSchemaRegistry*`, `AppointmentMessaging*Properties*`, auto-config | endpoint/auth/fail-closed binding |
| listener | `appointment-messaging/src/main/kotlin/.../AppointmentKafkaConsumer*`, integration tests | real container crash/rebalance proof |
| metrics | `appointment-messaging/src/main/kotlin/.../AppointmentConsumerMetrics*`, runtime/store/health | bounded SLO signals |
| replay | `AppointmentReplayService*`, new source/authorizer, API security adapter | actor/tenant authorization and production source port |
| retention | `AppointmentConsumerInboxStore*`, V23 index/migration tests, scheduled service | bounded cleanup and lock evidence |
| benchmark/docs | `benchmark/appointment-messaging-benchmark`, `docs/benchmarks`, `docs/images/readme-charts`, `appointment-messaging/README*`, runbook | PostgreSQL chart and production verification notes |

## 작업 순서

### 작업 1 — 승인된 계약 문서화 및 검토

- [x] code/API/URL만 영문으로 보존하면서 이 design과 plan을 한국어로 작성한다.
- [x] 여섯 관점(performance, stability, security, operator, developer, caller) review를
      실행하고 finding을 통합한다. 구현 전에 P0/P1을 0으로 만든다.
- [x] source를 편집하기 전에 Lore trailer가 있는 spec/plan을 commit한다.

명령: `git diff --check -- docs/superpowers/specs docs/superpowers/plans`.

### 작업 2 — MySQL V23 migration readiness 증명

- [x] table column, primary key, index를 검증하는 공용 V23 contract assertion을 추가한다.
- [x] Flyway H2, MySQL, PostgreSQL test에서 이를 호출한다. `MySQLServer.Launcher`와
      `PostgreSQLServer.Launcher` singleton fixture만 사용한다.
- [x] credential를 commit하지 않고 외부에서 제공한 JDBC endpoint를 받는 production
      verification command/runbook section을 추가한다. endpoint가 없으면 production
      실행을 PENDING으로 기록한다.
- [x] `scheduling_*` table 이름을 바꾸지 않고 MySQL metadata에서 readiness
      schema/catalog lookup을 검증한다.

대상 검증: `:appointment-api:test --tests '*FlywayMySQLMigrationTest*'`,
`:appointment-api:test --tests '*AppointmentConsumerMigrationContractTest*'`.

### 작업 3 — Schema Registry endpoint와 credential 연결

- [x] immutable binding/properties와 credential resolver port를 추가한다. registry가
      비활성화된 경우 기본 동작은 static local validation으로 유지한다.
- [x] bounded URI validation, endpoint path encoding, HTTPS/loopback policy,
      secret을 logging하지 않는 Basic `Authorization` injection을 JDK compatibility
      reader에 추가한다.
- [x] 올바른 auto-configuration phase에 conditional Spring bean을 등록하고 startup/
      readiness validation에 registry readiness를 포함한다.
- [x] endpoint path, timeout, positive Basic auth, missing/invalid auth,
      compatibility mismatch, disabled fallback, no-secret diagnostic test를 추가한다.

대상 검증: `:appointment-messaging:test --tests '*AppointmentSchemaRegistry*'` 및
`:appointment-messaging:test --tests '*AppointmentMessagingAutoConfigurationTest*'`.

### 작업 4 — 실제 Kafka 4 listener crash/rebalance 검증

- [x] manual `Acknowledgment`를 `AppointmentConsumerRuntime`에 전달하고 durable
      processing 전에 acknowledge하지 않는 runtime listener adapter를 추가한다.
- [x] 명시적 lifecycle, group/topic allow-list, shutdown, recovery assertion으로
      `ConcurrentMessageListenerContainer` factory를 강화한다.
- [x] singleton Kafka integration test를 확장한다. 한 handler가 ack 전에 throw하고,
      두 번째 container가 group에 합류하며, rebalance 또는 bounded retry 후 record가
      정확히 한 번 복구되는지 확인한다. in-memory call만 보지 말고 committed offset과
      quarantine metadata를 assertion한다.

대상 검증: sequential `:appointment-messaging:test --tests '*AppointmentKafkaConsumerIntegrationTest*'`
및 기존 consumer configuration/runtime test.

### 작업 5 — metric·lag/lock signal·retention·SLO 증거 추가

- [x] Noop과 Micrometer 구현을 포함한 `AppointmentConsumerMetrics`를 추가한다.
      outcome, retry/quarantine, lag/oldest age, inbox transaction latency, replay,
      cleanup에 대해 bounded counter/timer/gauge를 등록한다.
- [x] high-cardinality tenant/event/payload label 없이 runtime, inbox store, replay
      service, health detail을 계측한다.
- [x] processed/quarantined inbox와 replay audit row를 위한 bounded retention
      service/configuration을 추가한다. active `PROCESSING` row를 보호하고 cleanup
      result metric을 노출한다.
- [x] 기존 PostgreSQL `kotlinx-benchmark` consumer suite에 lock-contention sample과
      machine-readable SLO evidence report를 추가한다. benchmark 값으로 deployment
      SLO를 주장하지 않는다.
- [x] duplicate/cleanup/lock latency를 나타내는 source-backed chart(SVG→PNG)를
      하나 생성/갱신하고 full-size로 검사한다. EN/KO README 값은 source-equivalent로
      유지한다.

대상 검증: `:appointment-messaging:test`,
`:appointment-messaging-benchmark:test`, `:appointment-messaging-benchmark:mainSmokeBenchmark`.

### 작업 6 — production replay adapter와 authorization 구현

- [x] `AppointmentReplayAuthorizer`와 인증된 actor/tenant scope value object를 정의한다.
      audit 또는 source call 전에 blank actor, cross-tenant scope, invalid range,
      missing approval을 거부한다.
- [x] dedicated group, poll timeout, close-on-all-paths, runtime dispatch를 갖춘 bounded
      `KafkaAppointmentReplaySource`를 구현한다. operations group rewind는 사용하지 않는다.
- [x] replay 경계를 application adapter port로 유지한다. 아직 승인된 replay endpoint/
      claim mapping이 없으므로 새 public route는 추가하지 않는다. 기존 Spring Security
      integration은 명시적인 production wiring PENDING 항목으로 남긴다.
- [x] dry-run, approved execution, unauthorized, source failure, audit idempotency,
      retention test를 추가하고 production prerequisite를 한국어 replay runbook에
      갱신한다.

대상 검증: `:appointment-messaging:test --tests '*AppointmentReplayServiceTest*'`,
`:appointment-api:test --tests '*Security*'` for touched adapter paths.

### 작업 7 — Type-A 검증·PR·CI·종료

- [x] 모든 requirement에 대해 verifier traceability를 실행하고 승인된 non-goal과
      PENDING production gap을 명시적으로 표시한다.
- [x] module별 여섯 관점 code review와 performance/stability scan을 실행하고
      P0=0/P1=0으로 수렴한다.
- [x] 새 targeted test, 영향받은 module build, `git diff --check`, benchmark/report
      contract test를 실행한다. 사용할 수 없는 production endpoint/credential는
      unchecked로 기록한다.
- [x] 전체 `:appointment-api:test` Context Mode timeout을 code failure가 아니라
      verification gap으로 기록한다. targeted migration/projection 증거를 보존하고
      complete API suite가 통과했다고 주장하지 않는다.
- [ ] metadata를 맞춘 issue-linked 영문 PR을 생성하고 마지막에 `## DoD Status`
      섹션을 둔다.
- [ ] 정확한 PR head, CI, review thread, mergeability를 다시 확인한다. 해당 head에
      연결된 새 merge approval을 받은 뒤 merge하고 local `develop`을 sync하며,
      worktree를 제거하고 clean parity를 검증한 다음 workflow receipt를 종료한다.

## Rollback 및 위험 통제

- 이 branch에서는 destructive database operation이나 production dispatch를 수행하지 않는다.
- Registry wiring은 opt-in이며 fail-closed다. 비활성화하면 local/test environment의
  기존 static schema contract를 보존한다.
- Listener 변경은 manual ack와 기존 bounded recovery를 보존한다. integration 동작이
  불안정하면 test-only lifecycle harness를 유지하고 production crash evidence를 PENDING으로
  표시한다.
- Benchmark/chart artifact는 증거이지 SLO commitment가 아니다. 값과 환경은 JSON
  source에 기록한다.
