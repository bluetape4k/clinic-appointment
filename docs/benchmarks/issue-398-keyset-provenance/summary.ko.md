# Issue #398 keyset provenance 검증 요약

## 실행 결과

| 항목 | 결과 |
|---|---|
| provenance validator | `revision=HEAD` 해석 및 source path 3개 존재 확인 |
| benchmark test | 2 통과 |
| measured plans | baseline/candidate 각 21개, 허용되지 않은 index 0건 |
| candidate planner 선택 | 0건; 세 table 모두 기존 `*_pkey` 선택 |
| cleanup | 세 table 모두 candidate index 부재 확인 |
| chart/data/summary | 동일 report의 PostgreSQL 18.6 수치로 갱신 |

## 측정 수치

| 테이블 | baseline read p95 | candidate read p95 | baseline write p95 | candidate write p95 |
|---|---:|---:|---:|---:|
| `scheduling_doctors` | 0.786 ms | 0.787 ms | 4.342 ms | 4.094 ms |
| `scheduling_equipments` | 0.719 ms | 0.737 ms | 3.589 ms | 4.027 ms |
| `scheduling_treatment_types` | 0.672 ms | 0.867 ms | 4.039 ms | 4.324 ms |

이 수치는 단일 Docker PostgreSQL 실행의 benchmark evidence이며 운영 SLO나
Flyway migration 승인 근거가 아니다.
