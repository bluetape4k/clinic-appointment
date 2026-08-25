# Issue #398 7-Tier 검토

## 검토 대상

- 현재 tip: `test/issue-398-provenance`
- 기준 tip: `4ea46e4a92da0c5bf77e6f4550164eff0474cb85`
- 변경: keyset benchmark test, chart/data/summary provenance와 측정 결과

## 7-Tier 결과

| Tier | 판정 | 근거 |
|---|---|---|
| 성능 | PASS | 기존 32,000행 fixture와 7회 read/5회 write 반복 측정을 유지하고 planner·p95 증거를 기록했다. |
| 안정성 | PASS | table별 `try/finally`와 전체 fixture cleanup으로 예외 뒤 shared PostgreSQL 오염을 막는다. |
| 보안/데이터 경계 | PASS | tenant/clinic 범위와 candidate index 이름을 고정하고 fixture ID 범위만 삭제한다. |
| 운영 | PASS | report가 `sourceRevision`, source paths, planner index와 cleanup 결과를 남긴다. |
| 개발자/API | PASS | 새 abstraction 없이 `bluetape4k-assertions` helper와 기존 benchmark 구조를 재사용했다. |
| 사용자/호출자 | PASS | production schema·API SQL·Flyway migration은 변경하지 않았다. |
| 통합/테스트 | PASS | provenance test와 PostgreSQL benchmark test 2건이 통과하고 chart 수치를 report에 맞췄다. |

## 증거

- provenance validator: `revision=HEAD`가 `git rev-parse HEAD`와 일치하고 3개 source path가 존재
- planner: 21개 baseline/candidate measured plan 모두 허용된 primary-key index를 선택했고 candidate selected plan은 0건
- cleanup: 세 table 모두 `candidateIndexAbsentAfterCleanup=true`
- benchmark: PostgreSQL 18.6, 각 table 32,000행, read p95와 write p95 수치를 chart/data/summary에 반영
- blocker: P0=0, P1=0, P2=0, P3=0

## 판단

이번 변경은 후보 인덱스를 배포하지 않고 benchmark의 증거 품질과 격리만
강화한다. 실제 planner가 후보 인덱스를 선택한다는 운영 cardinality 증거가
생기기 전까지는 migration 승격을 보류한다.
