# Issue #38 tenant authority 구현 리뷰

## 리뷰 범위

`/api/{tenantCode}/...`를 commitment HTTP의 유일한 외부 tenant selector로 적용한
구현을 현재 worktree, focused test 결과, 정적 계약 검사로 재검토했다. 이 문서는
public GitHub 문서가 아닌 한국어 내부 리뷰 기록이다.

## 판정 요약

| 관점 | 판정 | 근거 |
|---|---|---|
| API 계약 | PASS | commitment 10개 operation이 tenant path로 통일되고 `/api/v2` route literal이 production source에 없음 |
| 보안·권한 | PASS | path grammar pre-auth 검증, JWT membership, role matrix, selected tenant 재검증, fail-closed 오류 envelope |
| 안정성 | PASS | request 경계 `TenantContext` cleanup, filter chain 단일 등록, DB outage의 명시적 500, privacy-safe log |
| 성능 | PASS (범위 한정) | filter 1회, route별 service lookup 예산 테스트(1회/2회), cross-layer cache 미도입 |
| Kotlin/Spring | PASS | nullable actor compatibility, Spring Security chain 순서, controller rename과 OpenAPI 경로 일치 |
| 문서/운영 | PASS | English/Korean README pair, API/runbook, architecture ADR, Korean review/lesson 반영 |

P0=0, P1=0이다. 전체 모듈 회귀의 최종 단일 실행 증거와 외부 CI 결과는 PR 단계에서
재확인해야 하며, 이를 로컬 PASS로 과장하지 않는다.

## 주요 구현 확인

1. `TenantCodeRules`가 lower-case ASCII slug, single hyphen, 64자 상한과 `v1`/`v2`
   reserved root를 한 곳에서 정의한다. `TenantPathValidationFilter`는 raw URI와
   servlet path/path info의 불일치, percent escape, semicolon, traversal,
   duplicate separator를 JWT parser보다 먼저 404로 차단한다.
2. Security chain은 correlation → path validation → JWT → tenant context 순서이며,
   네 filter의 servlet 자동 등록을 끄고 chain에서만 한 번 실행한다. commitment
   matcher는 generic tenant write보다 앞에서 환자·관리자 role matrix를 적용한다.
3. `TenantContextFilter`는 인증된 요청의 active tenant를 정확히 한 번 조회하고,
   membership·active 상태를 구분한다. DB 예외는 일반 요청 `500 INTERNAL_ERROR`,
   policy 요청 `POLICY_INTERNAL_ERROR`로 매핑하며 log에는 correlation ID와
   canonical tenant code만 남긴다.
4. `ActorContext.selectedTenantCode`가 path에서 선택한 tenant를 보존한다.
   commitment access resolver는 이를 canonical rule, JWT allow-list, active
   tenant, clinic scope와 다시 대조하므로 multi-tenant claim에서
   `singleOrNull()`로 권위를 선택하지 않는다.
5. 공개 controller는 `CustomerAppointmentController`와
   `AdminAppointmentController`로 이름을 정리하고 `/api/{tenantCode}`를 공통
   base path로 사용한다. `/api/v2` 호환 alias는 추가하지 않았다.

## 조회 예산 증거

| 경계 | 기대 조회 | 검증 |
|---|---:|---|
| tenant filter | 1 | `TenantContextFilterTest.authenticated tenant request performs one active tenant lookup` |
| direct create | 2 | `DefaultAppointmentCommitmentApplicationServiceTest.direct create keeps the two lookup budget` |
| direct confirm | 2 | `...direct confirm keeps the two lookup budget` |
| query | 1 | `...query keeps the single appointment lookup budget` |
| role denied | filter 1 / service 0 | `AppointmentCommitmentSecurityIntegrationTest` denied-role matrix |

approve/decline/change/expire/cancel의 call graph는 access resolver 1회 경로이며,
이번 범위에서는 공통 cache나 무제한 prefetch를 추가하지 않았다.

## 최신 검증 증거

- tenant/filter/authorization focused suite — `23 passing`, `BUILD SUCCESSFUL`.
- commitment controller/OpenAPI/feature-off/exception suite — `24 passing`,
  `BUILD SUCCESSFUL`.
- service access/lookup-budget suite — `25 passing`, `BUILD SUCCESSFUL`.
- affected Notification OpenAPI, catalog security, commitment security suite —
  `15 passing`, `BUILD SUCCESSFUL`.
- `git diff --check` — 통과.
- `rg '/api/v2' appointment-api/src/main --glob '*.kt'` — 결과 없음.
- `AdminAppointmentV2Controller`/`CustomerAppointmentV2Controller` 잔여 production,
  test, README 심볼 — 결과 없음.
- active README/API/runbook의 route 문서 — tenant path로 정렬됨. `V21`은 database
  migration version이며 API version alias가 아니다.

## 전체 회귀와 남은 검증 한계

첫 전체 실행에서는 기존 `CatalogProductSyncSecurityIntegrationTest` fixture가
공유 `tenant-default` row를 지운 뒤 복구하지 않아 새 tenant lookup 경계가 7건의
commitment 보안 테스트를 404로 만들었고, `NotificationOpenApiTest`가 갱신 전
`/api/v2` operation을 기대해 1건이 실패했다. 두 fixture/계약은 수정했고 affected
통합군은 위와 같이 다시 통과했다.

수정 후 전체 `:appointment-api:test`는 이 환경에서 context-mode 300초 제한으로
최종 요약을 회수하지 못했다. 중간 Gradle worker는 기존 PostgreSQL 성능 통합
테스트와 Redis shutdown reconnect log를 거쳤으며 종료 정리했다. 따라서 전체
모듈 PASS는 PR CI의 fresh run에서 확인할 항목으로 남긴다.

## 최종 결론

Issue #38 구현은 계약·보안·안정성·성능 범위에서 P0/P1 blocker 없이 PASS다.
머지 전에는 exact PR head의 CI, review thread, DoD body를 다시 읽고, CI가 전체
`appointment-api` 회귀를 성공으로 증명한 뒤에만 merge한다.
