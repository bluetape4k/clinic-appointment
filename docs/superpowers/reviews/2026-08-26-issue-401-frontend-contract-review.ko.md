# Issue #401 7-Tier 검토

## 검토 대상

- 현재 tip: `docs/issue-401-frontend-contract`
- 기준 tip: `25525d733e1394664778ee9fb16371f30d048ff7`
- 변경: frontend README·요구사항 문서, 문서 계약 validator, README diagram
  Angular 버전 표기

## 7-Tier 결과

| Tier | 판정 | 근거 |
|---|---|---|
| 성능 | PASS | runtime code와 bundle 경로를 변경하지 않고 문서·정적 검증만 추가했다. |
| 안정성 | PASS | validator가 package, route, tenant URL, legacy residual을 매 실행 확인한다. |
| 보안/데이터 경계 | PASS | tenant code·HttpOnly session·staff JWT의 서로 다른 경계를 문서에서 분리하고 source behavior는 보존했다. |
| 운영 | PASS | `/api/{tenantCode}/...`, local seed, #295 follow-up과 실행 명령을 reader 문서에 고정했다. |
| 개발자/API | PASS | 기존 `TenantContextService`, `PatientAuthService`, `PortalApiClient`, route를 재사용하고 새 runtime abstraction을 만들지 않았다. |
| 사용자/호출자 | PASS | 환자 포털 완료 범위와 직원·관리자 미완료 범위를 구분해 잘못된 tenant-ready 기대를 막는다. |
| 통합/테스트 | PASS | `npm run docs:verify`, Angular build, frontend unit/contract test와 문서 audit을 검증한다. |

## 증거

- package: Angular core/router/CLI/compiler-cli major `22` 일치
- route/source: `portal`, login/register, `patientAuthGuard`, sessionStorage,
  tenant-scoped client URL, legacy staff URL 각 1건 확인
- 문서: root 2개, module 2개, requirements 1개 총 5개 검사; stale Angular·Karma·전체
  endpoint 완료 문구 0건
- diagram: architecture/module overview 4개 SVG XML/text audit, PNG visual audit
  통과; Angular 18 잔여 표기 0건
- blocker: P0=0, P1=0, P2=0, P3=0

## 판단

이번 변경은 tenant-aware patient portal의 실제 완료 범위를 독자가 재현할 수
있도록 문서와 정적 guard를 정렬한다. 직원·관리자 tenant/auth 전환은 별도
source behavior 이슈인 #295의 범위로 남긴다.
