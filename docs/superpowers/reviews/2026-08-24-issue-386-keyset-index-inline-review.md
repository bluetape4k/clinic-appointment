# Issue #386 inline review

## 검토 범위와 방식

- 대상: `ClinicKeysetIndexAssessmentTest.kt`, Issue #386 측정 요약, `chart.data.json`,
  `chart.svg`, `chart.semantic.json`
- 방식: 독립 reviewer 대신 사용자가 지정한 inline review로 7개 관점(범위,
  정확성, 보안, 성능, 유지보수, 문서·운영, 회귀)을 순서대로 검토했다.
- 독립 reviewer lane: N/A. 사용자 지시로 대체했다.
- 판정: `APPROVE` (Issue #386 범위 안에서), 아키텍처 상태 `CLEAR`

## 관점별 결과

| 관점 | 근거 | 판정 |
|---|---|---|
| 범위·계약 | 테스트는 PostgreSQL singleton과 Flyway 실제 schema를 사용하고, 세 테이블을 동일한 keyset SQL로 비교한다 (`ClinicKeysetIndexAssessmentTest.kt:29-64`, `172-189`). production API SQL과 Flyway 파일은 변경하지 않았다. | PASS |
| 정확성·데이터 | 16개 clinic에 테이블마다 32,000행을 분산하고 warm-up 2회·읽기 7회·쓰기 5회를 기록한다 (`:81-134`, `:136-165`, `:223-255`). 각 계획의 50행, `OFFSET` 부재, 실행·계획 시간, top-level shared buffer, 필터 제거 행을 파싱한다 (`:167-221`, `:318-325`). | PASS |
| 보안 | SQL 값은 `PreparedStatement` 바인딩을 사용한다 (`:187-189`, `:240-249`). 테이블명·인덱스명은 테스트 내부 상수 `TABLES`에서만 공급되며 사용자 입력·비밀값·외부 payload를 받지 않는다. | PASS |
| 성능·운영 | 후보 인덱스를 실제로 생성하고 `EXPLAIN (ANALYZE, BUFFERS)`와 rollback 쓰기 샘플을 전후 비교한 뒤 제거한다 (`:43-55`, `:257-289`). 측정 결과 21개 계획 모두 primary-key index를 선택했으며 후보 인덱스의 읽기 개선은 재현되지 않았다. 로컬 표본을 운영 p95로 과장하지 않도록 요약과 차트에 한계를 명시했다. | PASS |
| 유지보수·재사용 | 기존 `Containers.Postgres`, Flyway migration, JDBC EXPLAIN 패턴을 재사용하고 production abstraction이나 dependency를 추가하지 않았다. `TableSpec`은 세 테이블의 고정 DDL 차이만 표현한다 (`:429-434`, `:488-507`). | PASS |
| 문서·운영 | 원문 계획은 build report로 저장하고, 한국어 요약·기계 판독 chart data·semantic ledger·SVG/PNG를 함께 고정했다 (`:327-383`, `docs/benchmarks/issue-386-keyset-index-assessment/`). 배포·롤백 결정을 migration 보류로 명시했다. | PASS |
| 회귀·검증 | Issue #312 기준선 테스트와 Issue #386 targeted test가 PostgreSQL singleton에서 각각 GREEN이다. 차트 semantic audit, SVG XML/text audit, PNG visual audit, Korean terminology audit도 통과했다. | PASS |

## 결함과 위험

- CRITICAL: 0
- HIGH: 0
- MEDIUM: 0
- LOW: 0

### 비차단 관찰

`EXPLAIN` 후보 상태에서도 `*_pkey`가 선택된 사실은 이 fixture와 PostgreSQL
18.6 설정에서의 결과다. 운영 cardinality·동시 쓰기·캐시 상태가 다르면 결론이
달라질 수 있으므로 migration을 추가하지 않고, 운영 근거가 생길 때 별도 이슈로
재측정하도록 문서에 남겼다.

## 최종 판정

현재 변경은 Issue #386의 검증 산출물로 승인할 수 있다. 읽기 개선을 재현하지
못한 후보 인덱스를 저장소 migration으로 승격하지 않은 결정이 범위와 증거에
맞는다. PR 병합 전에는 exact head, CI, 리뷰·thread를 다시 읽고 fresh approval을
받아야 한다.
