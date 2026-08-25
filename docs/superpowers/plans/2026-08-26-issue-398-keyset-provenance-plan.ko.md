# Issue #398 keyset index assessment provenance 계획

## 목표

`ClinicKeysetIndexAssessmentTest`의 planner 결정, fixture/index cleanup, chart
provenance를 실제 실행 결과와 연결한다. benchmark 산출물이 과거 commit이나
존재하지 않는 source path를 가리키지 않게 한다.

## 실행 순서

1. 기존 benchmark query, EXPLAIN parser, candidate index lifecycle과 chart/data/summary의 drift를 확인한다.
2. chart `revision=HEAD`를 실행 시 `git rev-parse HEAD`로 해석하는 provenance validator와 source path guard를 추가한다.
3. baseline/candidate의 실제 index 이름과 허용된 planner decision을 assertion하고 report에 기록한다.
4. 각 table의 candidate index와 전체 fixture를 `try/finally`에서 제거해 예외 경로 cleanup을 보장한다.
5. benchmark를 재실행하고 chart/data/summary 수치를 같은 report 결과로 갱신한다.
6. 7-Tier 검토, 한국어 문서 audit, 모듈 테스트·check·build를 검증한다.

## 보존할 계약

- `(clinic_id, id)` 후보 index를 production Flyway migration으로 승격하지 않는다.
- keyset query의 tenant predicate, cursor ordering, `LIMIT`과 기존 fixture cardinality를 바꾸지 않는다.
- planner는 primary key 또는 현재 candidate index와 clinic lookup index만 선택할 수 있고, 알 수 없는 index 선택은 실패로 처리한다.
- benchmark 산출물은 실행 checkout의 source path와 `HEAD`를 기준으로 검증한다.

## 완료 기준

- provenance validator가 revision과 source path를 현재 HEAD/파일에 연결한다.
- 21개 measured plan의 index decision과 baseline/candidate 비교가 assertion으로 고정된다.
- 정상·예외 경로 모두 candidate index와 fixture cleanup을 보장한다.
- benchmark 2건, chart/data/summary audit, 7-Tier blocker P0/P1/P2/P3가 0/0/0/0이다.
