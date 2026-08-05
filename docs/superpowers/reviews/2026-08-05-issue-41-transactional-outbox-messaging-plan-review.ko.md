# Issue #41 Step 3-R 계획 리뷰 통합 기록

## 검토 범위

- 승인된 명세, 구현 계획, Step 2-R 통합 기록, 위험 등록부와 현재 `appointment-core`, `appointment-event`, `appointment-api` 연계 코드를 대조했다.
- 독립 read-only lane: `plan-performance-41`, `plan-stability-41`, `plan-security-41`.
- 검토 기준: Step 3-R 14개 필수 항목, `$bluetape-kotlin-patterns`, Exposed transaction 경계, Kafka4-only 및 repository의 singleton launcher 규칙.
- 무거운 테스트/빌드와 파일 변경은 리뷰 lane에서 수행하지 않았다. 계획 수정은 main lane이 리뷰 결과를 통합한 뒤에만 적용했다.

## 원본 findings

| 관점 | 원본 P1 | 핵심 차단 내용 |
|---|---:|---|
| performance | 6 | 실행 가능한 benchmark/query-plan artifact 부재, PostgreSQL partial index/EXPLAIN 불일치, batch token/race 미증명, relay resource assertion 부재, Kafka singleton launcher/CI 미매핑, suspend Exposed blocking 경계 미정 |
| stability/SRE | 6 | core-context 의존성 경계, attempt exhaustion, same-aggregate predecessor, retry due CAS, readiness context proof, Kafka launcher/CI 분리 및 운영 artifact 미정 |
| security | 4 | scope negative matrix, persisted topic/key allow-list 재검증, privacy side-channel redaction, 실제 reschedule 파일 경로/ownership 누락 |

공통 P0=0. 원본 P1은 계획만으로 구현을 안전하게 시작할 수 없는 계약·검증 누락으로 분류했으며, speculative blocker는 포함하지 않았다.

## 계획 수정 매핑

| 원본 P1 | 반영 위치 | 수정 결과 |
|---|---|---|
| benchmark/query-plan가 추상적 | 파일 지도, Task 2/9 | `AppointmentOutboxPerformanceTestSupport`, `AppointmentOutboxQueryPlanTest`, fixed seed/warmup/sample/baseline/report path와 수치 gate를 명시했다. |
| dialect index/EXPLAIN 불일치 | Task 2 | PostgreSQL partial ready index와 H2/MySQL composite index를 분리하고 exact claim SQL, chosen-index/no-full-scan, lock-wait assertion을 추가했다. |
| atomic claim token/race/backoff | Task 5 | DB-clock, due/version predicate를 SELECT와 CAS UPDATE에 반복하고, row별 token/attempt 원자 증가, batch당 aggregate 하나, barrier race·statement counter·retry-vs-claim 검증을 명시했다. |
| relay resource/dispatcher/shutdown | Task 4/6/7 | `Dispatchers.IO` 경계, max in-flight/queue/fairness, cancellation terminal-write 금지, `SmartLifecycle`/`@PreDestroy`, lease reclaim test를 추가했다. |
| Kafka launcher/CI | Task 1/8/9 | `bluetape4k-testcontainers` singleton KRaft launcher, serialized integration job, path/Kover/nightly/`ci-status` simulation을 파일·명령으로 고정했다. |
| core-context cycle | Task 1/4 및 파일 지도 | `appointment-core` 소유 `AppointmentCommandContext`를 만들고 API/messaging에서 명시적으로 매핑한다. core는 event/messaging에 의존하지 않는다. |
| attempt exhaustion/order | Task 5/6 및 위험 등록부 | `attemptNumber`, max-attempts `FAILED`, predecessor guard, delayed publisher race, retry backoff CAS를 구현 계약으로 승격했다. |
| readiness | Task 7 | `ApplicationContextRunner`로 invalid config fail-fast, broker outage 기동·writer 유지·liveness UP/readiness DEGRADED를 검증한다. |
| scope/allow-list/privacy/reschedule | Task 4/6/8 및 파일 지도 | multi-dialect scope negative matrix, persisted topic/key revalidation, log/event/history redaction, 실제 `RescheduleController`/`ClosureRescheduleService` 경로와 테스트를 추가했다. |
| 운영 artifact | Task 7/8 및 위험 등록부 | concrete alert trigger/clear/owner/escalation/rollback과 English runbook 파일을 추가하고 parity validator를 둔다. |

## 구현 후 reconciliation

- 구현은 fixed-seed mixed 20,000-row H2 backlog에서 실제 `JdbcAppointmentOutboxStore.claim` 경로를
  사용해 3회 warmup/15회 측정과 2-thread contention sample을 수행했다. V22 index metadata,
  `EXPLAIN`, due/lease/attempt-version CAS predicate, distinct claim ID 결과와 raw-payload-free
  report는 `appointment-messaging/build/reports/appointment-messaging/benchmark.json`에 남겼다.
- 이 결과는 bounded local query/claim contract 증거이며 production SLO 승인이 아니다. PostgreSQL/MySQL
  lock-wait, p95/p99, heap/thread, serializer, Kafka catch-up 및 배포별 threshold는 rollout 전에
  deployment evidence로 별도 수집해야 한다. report의 `deploymentSloEvidence=false`가 이 경계를 고정한다.
- latest focused API regression은 41/41, Kafka4 singleton integration은 1/1로 통과했다. 모듈 전체 실행은
  66개 테스트 본문이 통과한 뒤 Gradle 결과 수집 단계에서 EOF가 발생했으므로 전체 모듈 green으로 주장하지
  않고 infrastructure/test-process gap으로 기록한다.

## 남은 P2 후속 항목

- causation upstream provenance를 server-produced로 제한하는 세부 구현 테스트와 secret-manager provider별 binding은 구현/보안 review에서 fresh 검증한다.
- payload JSON과 V22 metadata의 cross-field consistency 및 mixed-version rollback fixture는 Task 2/6/8의 실제 테스트 결과로 확정한다.

## 최종 계획 게이트

- 계획 수정 후 문서 기준 P0=0, 미해결 계획 P1=0.
- 구현 전 검증 보류: 실제 Kotlin 컴파일, Flyway 3-dialect 실행, Kafka4 singleton 통합, benchmark threshold, CI path 결과는 구현 단계에서 fresh evidence가 필요하다.
- Step 2-R 명세 승인: 2026-08-05 사용자 `승인`.
- Step 3-R 계획 리뷰: required repair mapping 완료, 구현 단계로 진행.

**Gate verdict: PASS FOR IMPLEMENTATION — implementation fresh verification remains mandatory.**
