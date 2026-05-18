# Angular 라우트 roleGuard 적용 — Management 전체 보호

## 작업 개요

Issue #47: `management` 라우트에 `roleGuard`를 추가해 `ROLE_ADMIN`, `ROLE_STAFF`, `ROLE_DOCTOR` 역할만 접근 허용.

## 결정 사항

**방법**: Parent lazy-load route에 `canActivate: [roleGuard]` + `data: { requiredRoles: [...] }` 추가.

**Why:** Angular의 `canActivate`는 부모 route에 설정하면 모든 child route에 자동 적용된다. `management.routes.ts`의 각 child에 개별 guard를 추가할 필요 없이 `app.routes.ts`의 parent entry 한 곳만 수정하면 된다.

## 역할 범위

`ROLE_ADMIN`, `ROLE_STAFF`, `ROLE_DOCTOR` — ROLE_DOCTOR를 포함한 이유: 의사도 자신의 일정 관리, 진료 유형 조회 등 management 기능이 필요하다는 요구사항 변경 (이슈 #47 논의).

## 검증

- `ng build --configuration production` 성공 (2.865s)
- 빌드 산출물에 `management-routes` lazy chunk 정상 생성 확인

## 교훈

- **Parent route guard = 모든 child 보호**: Angular route guard는 lazy-loaded parent에 걸면 그 하위 모든 경로를 커버한다.
- **`data.requiredRoles`**: `roleGuard`는 `route.data['requiredRoles']`를 읽으므로 정확한 key명 일치 필수.
- **역할 포함 범위는 사전에 확정**: 이슈 논의에서 "Y (ADMIN+STAFF)"와 "Doctor 포함" 사이에 변경이 발생했다. 역할 목록은 구현 전에 최종 확정할 것.
