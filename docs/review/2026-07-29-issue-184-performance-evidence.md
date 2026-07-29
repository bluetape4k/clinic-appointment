# Issue #184 방문 예약 확정 성능·다이얼렉트 증거

## 판정

Task 10의 PostgreSQL 동시 확정, 일반·최대 Plan 계산, 핵심 조회 실행 계획,
H2·PostgreSQL·MySQL 호환성 기준을 모두 충족했다.

| 검증 항목 | 기준 | 결과 | 판정 |
|---|---:|---:|---|
| 인기 전담 자원 동시 확정 | 100 caller, 중복 점유 0 | 성공 1, 안정 충돌 99, active allocation 1 | 통과 |
| 미복구 deadlock | 0 | 0 | 통과 |
| 동시 확정 command p95 | 2,000 ms 이하 | 698 ms | 통과 |
| 일반 Plan proposal p95 / p99 | 1,000 / 3,000 ms 이하 | 0.826 / 1.013 ms | 통과 |
| 최대 Plan proposal p95 | 5,000 ms 이하 | 6.074 ms | 통과 |
| 최대 Plan dirty-set p95 / p99 | dataset 예산 이하 | 0.234 / 0.245 ms | 통과 |
| Gatling 실패 요청 | 0 | 0 / 264 | 통과 |
| PostgreSQL 10만 allocation full scan | 없음 | 없음 | 통과 |
| retention 보존 조회 full scan | 없음 | 3개 조회 모두 index-only scan | 통과 |
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
- 종료 후 active allocation: 1
- 중복 점유: 0
- 미복구 deadlock: 0
- p95: 698 ms
- budget: 2,000 ms

별도의 `VisitCommitmentConcurrencyTest`는 전담 resource overlap, capacity bucket
상한, 서로 다른 proposal의 수락 경합을 실제 PostgreSQL transaction으로 검증한다.
`AppointmentCommitmentCommandServiceTest`는 의료진·장비를 함께 잠그는 다중 항목과
동일 idempotency key replay를 검증한다.

## proposal과 dirty-set

| Dataset | 표본 | Proposal p95 | Proposal p99 | Dirty-set p95 | Dirty-set p99 |
|---|---:|---:|---:|---:|---:|
| normal | 100 | 0.826 ms | 1.013 ms | 0.050 ms | 0.059 ms |
| maximum | 100 | 6.074 ms | 6.532 ms | 0.234 ms | 0.245 ms |

이 proposal 수치는 dependency/window 탐색과 정렬 경로를 측정한다. fixture의
practitioner/equipment/space 요구와 `availableResources`는 비어 있으므로
inventory cardinality에 따른 proposal-side 자원 매칭 비용까지 대표하지 않는다.
실제 확정 command의 전담 자원 overlap·capacity·다중 잠금은 아래 Gatling과
PostgreSQL 동시성 검증이 담당한다. proposal-side 자원 매칭은 별도 예산을 정한 뒤
후속 benchmark로 보강한다.

Gatling canonical 진입점
`io.bluetape4k.clinic.appointment.api.VisitCommitmentSimulation`은 같은 고정
dataset과 실제 Exposed command fixture를 loopback HTTP transport까지 포함해
264회 실행했다.

- 실패 요청: 0
- 전체 p95 / p99 / max: 52 / 257 / 323 ms
- normal proposal p95 / p99: 2 / 3 ms
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
| allocation overlap | `idx_resource_allocation_overlap` | 1.148 ms | 없음 |
| current proposal | `uq_proposal_commitment_revision` 역방향 scan | 0.285 ms | 없음 |
| Plan dirty-set | `uq_plan_revision_dependency` index-only scan | 0.322 ms | 없음 |
| idempotency retention | `idx_appointment_idempotency_retention` index-only scan | 0.478 ms | 없음 |
| inbox retention | `idx_inbox_retention` index-only scan | 0.467 ms | 없음 |
| outbox retention | `idx_outbox_retention` index-only scan | 0.473 ms | 없음 |

여섯 실행 계획 모두 `Seq Scan on`을 포함하지 않는다. V12 보존 index 세 건은
각 20,000 row·100 clinic fixture에서 검증했다. 원문은
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
