# Issue #398 작업 교훈

## 재사용 우선 판단

기존 benchmark의 EXPLAIN parser, singleton PostgreSQL launcher,
`bluetape4k-assertions`를 유지하고 검증 경계만 보강했다. 새 planner abstraction이나
production index migration을 추가하지 않아 측정 목적과 실행 비용을 보존했다.

## 증거·cleanup 교훈

- commit SHA를 chart에 직접 하드코딩하면 다음 문서 commit에서 다시 stale해질 수
  있으므로 `revision=HEAD`와 실행 시 resolver를 함께 기록한다.
- planner를 단순히 row count와 elapsed time으로 설명하면 실제 index 선택을
  증명하지 못한다. `EXPLAIN`의 index name을 baseline/candidate별로 assertion하고
  report에 남겨야 한다.
- shared singleton DB benchmark에서는 성공 경로의 `DROP INDEX`만으로 부족하다.
  table 단위와 전체 fixture에 `try/finally` cleanup을 둬 예외 뒤 다음 실행을
  오염시키지 않아야 한다.

## 후속 경계

후보 `(clinic_id, id)` index를 Flyway로 승격하려면 실제 운영 cardinality,
동시 쓰기 비용, planner 선택 증거를 별도 이슈에서 다시 수집한다.
