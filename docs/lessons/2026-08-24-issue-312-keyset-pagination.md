# Issue #312 keyset cursor PostgreSQL 검증 결과

## 검증 범위

PostgreSQL 실제 migration schema에 의사·장비·진료 유형을 각각 2,000건씩 넣고,
`clinic_id=3,120,010`, `tenant_group_id=3,120,001`, `limit=50`, `cursor id=3,121,600`,
offset `1,500` 조건으로 같은 목록 경계를 비교했다. 테스트가 생성한 원문 실행계획은
`appointment-api/build/reports/performance/issue-312-keyset-pagination-postgresql-explain.txt`
에 기록된다.

| 테이블 | keyset 실행 시간(ms) | offset 실행 시간(ms) | keyset 결과 행 | offset 결과 행 |
|---|---:|---:|---:|---:|
| `scheduling_doctors` | 0.099 | 0.288 | 50 | 50 |
| `scheduling_equipments` | 0.100 | 0.297 | 50 | 50 |
| `scheduling_treatment_types` | 0.099 | 0.269 | 50 | 50 |

위 시간은 `EXPLAIN (ANALYZE, BUFFERS)`를 한 번 실행한 대표 측정값이다. 반복 분포를
수집하는 안정화 benchmark가 아니므로 절대적인 p95·처리량으로 해석하지 않는다. 이번
증거의 주된 목적은 keyset SQL에 `OFFSET`이 없고 `limit + 1` 경계가 실제 PostgreSQL
계획으로 실행되는지, 같은 50행을 반환하는지 확인하는 것이다.

## 실행계획 판정

- 세 keyset SQL 모두 `OFFSET` 없이 `LIMIT`을 사용했고 50행을 반환했다.
- 세 offset SQL은 동일한 50행을 반환했지만 offset 이전 1,500행을 읽은 뒤 제한했다.
- 현재 스키마에서는 세 테이블 모두 primary key index를 사용했다. keyset 계획도
  `Rows Removed by Filter: 1500`을 보여 주므로 `(clinic_id, id)` 복합 인덱스가 있으면
  cursor 경계 이전 행을 더 줄일 여지가 있다.
- 이번 이슈에는 migration이나 인덱스 변경을 넣지 않았다. 단일 측정으로 운영 인덱스
  추가를 확정하지 않고, 실제 cardinality·쓰기 비용·배포 절차를 별도 이슈에서 검토한다.

## 다음 결정

1. API 호출자는 기존 `page`/`size` 경로를 계속 사용할 수 있고, 대용량 순차 탐색에는
   `limit`/`nextCursor` 경로를 선택한다.
2. 후속 [Issue #386](https://github.com/bluetape4k/clinic-appointment/issues/386)에서는 각 테이블의 `(clinic_id, id)` 인덱스 후보와 기존 단일
   `clinic_id` 인덱스의 중복·쓰기 비용을 `EXPLAIN (ANALYZE, BUFFERS)`와 함께 다시
   비교한다.
3. 이 문서의 단일 측정값을 성능 목표나 회귀 임계값으로 사용하지 않는다. 반복 측정
   benchmark와 chart가 필요한 경우 별도 성능 작업의 표본·환경·분산 계약을 먼저
   고정한다.
