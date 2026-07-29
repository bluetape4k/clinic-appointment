# Issue #184 방문 예약 확정 성능·다이얼렉트 증거

## 판정

Task 10의 PostgreSQL 동시 확정, 일반·최대 Plan 계산, 핵심 조회 실행 계획,
H2·PostgreSQL·MySQL 호환성 기준을 모두 충족했다.

| 검증 항목 | 기준 | 결과 | 판정 |
|---|---:|---:|---|
| 인기 전담 자원 동시 확정 | 100 caller, 중복 점유 0 | 성공 1, 안정 충돌 99, active allocation 1 | 통과 |
| 미복구 deadlock | 0 | 0 | 통과 |
| 동시 확정 command p95 | 2,000 ms 이하 | 709 ms | 통과 |
| 일반 Plan proposal p95 / p99 | 1,000 / 3,000 ms 이하 | 0.974 / 1.294 ms | 통과 |
| 최대 Plan proposal p95 | 5,000 ms 이하 | 5.611 ms | 통과 |
| 최대 Plan dirty-set p95 / p99 | dataset 예산 이하 | 0.239 / 0.252 ms | 통과 |
| 자원 상한 fixture proposal p95 / p99 | 50 / 100 ms 이하 | 0.532 / 0.545 ms | 통과 |
| Gatling 실패 요청 | 0 | 0 / 264 | 통과 |
| PostgreSQL 10만 allocation full scan | 없음 | 없음 | 통과 |
| retention 보존 조회 full scan | 없음 | 4개 조회 모두 bounded index scan | 통과 |
| 지원 DB | H2, PostgreSQL, MySQL | 세 dialect 개별 통과 | 통과 |

## 측정 조건

- 실행일: 2026-07-30
- JVM 및 의존성: 저장소가 고정한 Kotlin 2.3, Java 25, Spring Boot 4
- container: 저장소 공용 singleton PostgreSQL·MySQL launcher
- 동시 확정: HikariCP 최대 20개 connection, 100 caller, 1회 command warm-up
- proposal/dirty-set: 고정 dataset별 20회 warm-up 후 100회 측정
- PostgreSQL 실행 계획: proposal 10,000건, Plan dependency 10,000건,
  allocation 100,000건 적재 후 `ANALYZE`

동시 확정 측정은 container connection 생성과 JVM cold start를 제외한다. 다만
application의 transaction 시작, bounded pool 대기, Exposed query, rollback,
안정 오류 변환은 caller latency에 포함한다. 인기 자원 mutex row가 없는 최초
경합도 command가 dialect별 충돌 없는 insert로 생성하도록 검증하며, 측정은 이
초기화 비용까지 포함한다.

## PostgreSQL 동시성

`VisitCommitmentLoadIntegrationTest`는 같은 전담 자원과 시간대에 100개 direct
confirmation을 동시에 보냈다. resource mutex는 `NOWAIT`로 잠그고 loser를
`RESOURCE_CONFLICT`로 변환한다.

- 성공: 1
- `RESOURCE_CONFLICT`: 99
- 기타 예외 또는 비정상 결과: 0
- 종료 후 active allocation: 1
- 중복 점유: 0
- 미복구 deadlock: 0
- p95: 709 ms
- budget: 2,000 ms

테스트는 단순 실패 개수만 세지 않고 99개 loser가 모두
`AppointmentCommitmentCommandException(RESOURCE_CONFLICT)`인지 단언한다. 예상하지
않은 예외, 다른 오류 코드, 누락된 결과는 별도 `unexpected`로 집계해 즉시 실패한다.

별도의 `VisitCommitmentConcurrencyTest`는 전담 resource overlap, capacity bucket
상한, 서로 다른 proposal의 수락 경합을 실제 PostgreSQL transaction으로 검증한다.
`AppointmentCommitmentCommandServiceTest`는 의료진·장비를 함께 잠그는 다중 항목과
동일 idempotency key replay를 검증한다.

## proposal과 dirty-set

| Dataset | 표본 | Proposal p95 | Proposal p99 | Dirty-set p95 | Dirty-set p99 |
|---|---:|---:|---:|---:|---:|
| normal | 100 | 0.974 ms | 1.294 ms | 0.048 ms | 0.057 ms |
| maximum | 100 | 5.611 ms | 6.250 ms | 0.239 ms | 0.252 ms |

기본 dataset 수치는 dependency/window 탐색과 정렬 경로를 측정한다. 별도의
`resource-rich` fixture는 한 방문의 40개 항목이 의료진·장비·공간을 모두 요구하고,
한 candidate slot에 허용 상한인 200개 자원을 두며 일치 자원을 목록 끝에 배치한다.
100회 측정 결과 proposal p95 0.532 ms, p99 0.545 ms로 각각 50 ms, 100 ms 예산을
충족했다. 요청은 slot당 200개와 전체 10,000개 자원 entry를 넘으면 계산 전에
`PLAN_LIMIT_EXCEEDED`로 거부한다.

실제 확정 command의 전담 자원 overlap·capacity·다중 잠금은 아래 Gatling과
PostgreSQL 동시성 검증이 담당한다.

Gatling canonical 진입점
`io.bluetape4k.clinic.appointment.api.VisitCommitmentSimulation`은 같은 고정
dataset과 실제 Exposed command fixture를 loopback HTTP transport까지 포함해
264회 실행했다.

- 실패 요청: 0
- 전체 p95 / p99 / max: 38 / 68 / 325 ms
- normal proposal p95 / p99: 2 / 2 ms
- maximum proposal p95: 6 ms
- 전담 자원 overlap, capacity 소진, 의료진·장비·공간 다중 잠금,
  멱등성 replay: 각 5회, 실패 0

Gatling fixture는 H2에서 production `AppointmentCommitmentCommandService`와 같은
Exposed table·repository·transaction 경계를 호출한다. HTTP 응답은 command 결과와
예상 충돌을 검증한 뒤에만 성공하므로 proposal 계산뿐 아니라 overlap·capacity·
다중 잠금·멱등성 replay도 canonical simulation에 포함된다. PostgreSQL의 실제
동시 경합과 dialect lock 의미는 별도 integration test가 보강한다.

## PostgreSQL 실행 계획

| 조회 | 사용 index | 실행 시간 | full scan |
|---|---|---:|---|
| allocation overlap | `idx_resource_allocation_overlap` | 1.182 ms | 없음 |
| current proposal | `uq_proposal_commitment_revision` 역방향 scan | 0.257 ms | 없음 |
| Plan dirty-set | `uq_plan_revision_dependency` index-only scan | 0.280 ms | 없음 |
| idempotency retention | `idx_appointment_idempotency_retention` index-only scan | 0.460 ms | 없음 |
| inbox retention | `idx_inbox_retention` index-only scan | 0.464 ms | 없음 |
| outbox retention | `idx_outbox_retention` index-only scan | 0.461 ms | 없음 |
| quarantine retention | `idx_quarantine_resolved_retention` index scan | 1.179 ms | 없음 |

일곱 실행 계획 모두 `Seq Scan on`을 포함하지 않는다. 보존 index 네 건은
각 20,000 row·100 clinic fixture에서 검증했다. quarantine index는 status보다
`resolved_at, id`를 앞에 두어 실제 보존 query의 정렬과 batch limit를 그대로
지원한다. 원문은
`appointment-api/build/reports/performance/visit-commitment-postgresql-explain.txt`
에 생성되며, 테스트가 index 이름과 full scan 부재를 직접 단언한다.

## 다이얼렉트 의미 동등성

`VisitCommitmentDialectIntegrationTest`는 H2→PostgreSQL→MySQL 순서로 V1~V9
legacy row를 만든 뒤 V10·V11·V12를 적용해 legacy 보존, 신규 FK·unique·retention index,
quarantine resolution 시각과 clean install을 검증했다.

| Backend | 검증 | 결과 |
|---|---|---|
| H2 | V10·V11·V12 migration + 기존 command 회귀 | 통과 |
| PostgreSQL | V10·V11·V12 migration + 동시성·성능·command 의미 | 통과 |
| MySQL | V10·V11·V12 migration + direct confirmation replay·overlap conflict | 통과 |

MySQL command 의미 검증은 동일 command replay가 같은 commitment/proposal을
반환하고, 겹치는 신규 확정이 `RESOURCE_CONFLICT`로 rollback되는지 별도로 단언한다.
실행 계획과 accidental full scan 부재는 PostgreSQL에서만 측정했으며, MySQL을
production 성능 대상으로 활성화하기 전에는 대표 분포의 MySQL `EXPLAIN` 증거를
별도 확보해야 한다.

## 재현 명령

```bash
./gradlew :appointment-api:test \
  --tests "*AppointmentProposalServicePerformanceTest" \
  --tests "*VisitCommitmentLoadIntegrationTest" \
  --tests "*VisitCommitmentPerformanceIntegrationTest" \
  --tests "*VisitCommitmentDialectIntegrationTest" \
  --tests "*VisitCommitmentMySqlIntegrationTest"

./gradlew :appointment-api:gatlingRun \
  --simulation io.bluetape4k.clinic.appointment.api.VisitCommitmentSimulation
```

생성 artifact는 build output이므로 커밋하지 않는다. 재현 가능한 dataset, assertion,
테스트 코드와 이 판정 기록만 버전 관리한다.
