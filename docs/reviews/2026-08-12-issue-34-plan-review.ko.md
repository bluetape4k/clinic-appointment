# Issue #34 구현 계획 7-tier 재검토

설계 보강 후 구현 계획을 독립 관점으로 재검토했다. 계획은 실제 구현 전
승인 가능한 실행 단위와 실패 시 중단 조건을 포함하며, benchmark·운영·브라우저
evidence 자체는 구현 후 `PENDING`이다.

| 관점 | 판정 | 재검토 결론 |
|---|---|---|
| 성능 | ACCEPT 조건부 | PostgreSQL cancel simulation/fixture, 3회 median/분산, absolute/relative threshold, 실제 codec backlog benchmark, comparator/CI artifact 명령을 Task 7에 고정했다. 구현 후 evidence 없이는 PR 준비를 하지 않는다. |
| 안정성 | ACCEPT 조건부 | consumer-first와 default-off flag, activation/rollback 분리, template readiness, reminder lease/recovery 경합, v2 `EXHAUSTED` reconciliation을 명시했다. |
| 보안 | ACCEPT 조건부 | ADMIN/STAFF/PATIENT matrix, application/command 직접 호출 검증, 실제 IDOR fixture, 폐쇄형 code/event 조합, detail sink redaction을 명시했다. |
| 운영/운영자 | ACCEPT 조건부 | readiness endpoint, replica codec matrix, vendor별 JSON/indexed backlog query, schema/decode/template metrics와 alert/runbook 파일을 계획에 추가했다. |
| 개발자/API | ACCEPT 조건부 | DTO→command→transaction→notification→renderer 전체 파일 지도와 SchemaInit, metrics, feature policy 경계를 확장했다. |
| 사용자/호출자 | ACCEPT 조건부 | 모든 mutation 공통 412 정책, protected backend harness, terminal step, dead button, a11y/focus, #305 negative contract를 명시했다. |

## 구현 진입 판정

- P0: 0
- 설계/계획 문서상 남은 P1: 0
- 구현 후 필수 evidence: backend module tests, renderer/worker readiness,
  PostgreSQL benchmark reports, codec benchmark comparator, protected browser
  traces/backend state/outbox receipt, 7-tier implementation review.
- production canary/SLO와 실제 worker replica evidence는 운영 승인 전까지
  `PENDING`으로 유지한다.
- 성능 load mix의 expected conflict/exhaustion과 unexpected error 분모를 분리하고
  configuration prefix를 `clinic.notification.v2-producer`로 통일했다.
