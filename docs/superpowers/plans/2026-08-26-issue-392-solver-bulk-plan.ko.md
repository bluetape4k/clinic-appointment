# Issue #392 solver planning fact bulk 조회 계획

## 목표

`SolverService` 기준 데이터 로딩에서 의사마다 스케줄과 부재를 따로 읽는 `2N` 조회를
clinic/date 범위 bulk query 두 번으로 수렴한다. 결과의 tenant/clinic 경계, 의사 순서,
planning fact version hash와 SERIALIZABLE apply 계약은 유지한다.

## 실행 범위

1. `DoctorRepository`에 의사 ID 묶음용 스케줄·부재 조회 API를 추가한다.
2. 두 API가 `TenantClinicScope` predicate와 날짜 범위를 유지하고, 결과를 doctor ID로
   그룹화해 기존 기준 데이터 변환 순서를 보존하게 한다.
3. H2/PostgreSQL에서 tenant 격리, 날짜 필터, 중복 입력, SQL statement budget을
   회귀 테스트로 고정한다.
4. solver 기존 결과·version fence 테스트를 재실행하고, 100명 의사 PostgreSQL
   benchmark 결과를 기록한다.
5. 7-Tier review, `bluetape-kotlin-patterns`, `bluetape4k-assertions`, writer audit와
   exact-head CI/read-back을 완료한 뒤 stacked train merge는 보류한다.

## 완료 조건

- [x] 의사 수와 무관하게 스케줄·부재 SELECT가 각각 1회다.
- [x] tenant/clinic 범위, 날짜 필터, 결과 순서와 중복 입력 계약이 유지된다.
- [x] `SolverService`의 planning fact version fence와 apply 의미가 유지된다.
- [x] H2/PostgreSQL query-count regression과 100명 의사 benchmark가 통과한다.
- [x] 변경된 Kotlin 코드가 기존 bluetape4k repository/assertion 패턴을 재사용한다.
- [ ] exact-head CI와 PR/Issue read-back을 완료한다.

## 현재 기준

- 구현 commit: `38e06d52`
- 선행 stacked branch: `fix/issue-396-notification-assertions`
- 후속 PR/CI 및 workflow receipt는 구현 commit push 후 기록한다.
