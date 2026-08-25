# Issue #392 solver planning fact bulk 조회 7-Tier review

## 판정

`SolverService`가 의사별 스케줄·부재 조회를 repository bulk API 두 번으로 읽도록
변경했다. `TenantClinicScope`, date range, doctor 순서와 planning fact hash 입력은
그대로 유지한다. 구현 tip 로컬 판정은 **PASS**이며, stacked PR train 전체 완료 전
merge는 **HOLD**다.

## 7-Tier 결과

| Tier | 검토 내용 | 결과 |
|---|---|---|
| 1. 계약·범위 | #392의 solver N+1 범위만 수정하고 선행 #396 tip 위에 적층 | PASS |
| 2. API·상태 | `Map<doctorId, records>`와 기존 `flatMap` 순서를 유지; 빈 입력은 empty map | PASS |
| 3. 안전성 | bulk predicate가 tenant clinic doctor subquery를 유지하고 foreign tenant row를 제외 | PASS |
| 4. Kotlin 패턴 | immutable `val`, `distinct`, early return, `orEmpty`, `groupBy`, 명시적 KDoc | PASS |
| 5. 테스트 | bluetape4k assertions와 Exposed `StatementInterceptor`로 범위·날짜·query budget 검증 | PASS |
| 6. 운영성 | 의사 100명 PostgreSQL smoke benchmark에서 schedule/absence query 각 1회 | PASS |
| 7. 통합·회귀 | solver 22개와 core bulk 4개 통과; exact-head CI는 후속 gate | PASS 조건 충족 |

Finding count: `P0=0 / P1=0 / P2=0 / P3=0`.

## 검증 근거

- `DoctorRepository.findAllSchedulesByDoctorIds`: doctor ID와 row ID 순서로 bulk 조회 후
  doctor ID별 그룹화
- `DoctorRepository.findAbsencesByDoctorIdsAndDateRange`: tenant predicate와 날짜 범위
  조건을 함께 적용
- `SolverService.loadSnapshotInCurrentTransaction`: 의사 ID 목록으로 두 bulk API를
  호출하고 기존 의사 순서대로 flatten
- `DoctorRepositoryBulkTest`: H2/PostgreSQL에서 foreign tenant 제외, 날짜 필터, 중복
  doctor ID, SQL budget 검증
- `SolverServiceTest`: 기존 feasible 결과와 planning fact/version fence 회귀

## 남은 gate와 위험

- exact-head GitHub CI와 PR/Issue metadata/read-back을 완료해야 최종 DoD가 된다.
- benchmark는 100명 의사 단일 fixture의 query shape와 smoke elapsed만 증명하며,
  운영 데이터 분포·connection pool·동시 solver throughput을 대신하지 않는다.
- 전체 stacked train의 후속 Issue가 남아 있으므로 자동 merge하지 않는다.
