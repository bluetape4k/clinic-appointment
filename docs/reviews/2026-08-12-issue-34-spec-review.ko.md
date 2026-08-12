# Issue #34 설계 7-tier 독립 검토

검토 대상은 설계·구현 계획·위험 register와 현재 `appointment-api`,
`appointment-event`, `appointment-notification`, Angular 포털 계약이다.
코드 수정 전 독립 관점 검토를 수행했으며, 모든 P1은 계획과 검증 기준에 반영한
뒤 구현을 시작한다.

| 관점 | 결과 | 핵심 확인 및 반영 |
|---|---|---|
| 성능 | P0 0 / P1 2 | 취소 전용 timer와 proposal metric 분리, PostgreSQL 고정 dataset·동시성·p95/p99 회귀 게이트, mixed-schema backlog scale fixture를 추가했다. |
| 안정성 | P0 0 / P1 3 | consumer-first rollout, worker replica readiness와 backlog 0 조건, renderer/catalog v2 readiness, reminder lease/recovery 경합 및 rollback 보존을 추가했다. |
| 보안 | P0 0 / P1 4 | application/command 직접 호출의 역할 검증, 실제 cross-tenant/clinic/patient IDOR fixture, 폐쇄형 event/parameter 조합, detail 보존·redaction 경계를 추가했다. |
| 운영/운영자 | P0 0 / P1 1 | compile-time schema version 변경만으로 rollout하지 않고 default-off feature flag/property, readiness endpoint, replica별 codec matrix, vendor별 JSON/indexed backlog query, decode/template metric·alert와 timeout runbook을 요구한다. activation/rollback checklist도 분리했다. |
| 개발자/API | P0 0 / P1 6 | ADMIN/STAFF/PATIENT cancel 계약, reasonDetail 전달·canonical hash, v1/v2 codec, SchemaInit 등록, reason registry, Angular cancelled view/client 파일 경계를 계획에 반영했다. |
| 사용자/호출자 | P0 0 / P1 4 | `REQUESTED` local step과 terminal mapping, 412 명시 재확인·single-flight/focus 복귀, deterministic browser/backend harness, dead detail button 제거를 명시했다. |

## 통합 판정

- 초기 검토는 P1 공백으로 `REJECT`였으나 위 항목을 설계·계획·risk register에
  반영했다.
- 재검토 시 확인할 구현 stop condition은 모든 관점 P0/P1 0, PostgreSQL 성능
  evidence, template/worker readiness, reminder 경합, 실제 IDOR 및 browser 증거다.
- 계획 재검토에서 성능은 전용 PostgreSQL simulation/fixture와 실제 codec backlog
  benchmark, baseline/candidate comparator 및 CI `issue34-performance-gate`로
  실행 가능하게 고정한다. 아직 구현 전이므로 해당 evidence는 `PENDING`이다.
- `#305` 환자 취소 이력 API/UI와 raw audit log 노출은 이 작업에 포함하지 않는다.
- production canary/SLO와 live replica evidence는 구현 후 별도 운영 검증 전까지
  `PENDING`으로 유지한다.

## 재검토 명령

```bash
git diff --check
./gradlew :appointment-event:test :appointment-api:test :appointment-notification:test
cd frontend/appointment-frontend
npm test -- --watch=false
npm run build
npm run test:e2e -- --project=chromium
```
