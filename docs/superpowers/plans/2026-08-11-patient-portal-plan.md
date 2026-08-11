# 환자 포털 구현 계획

> **For agentic workers:** 이 계획은 현재 세션에서 단계별로 실행한다. 각 단계는 테스트를 먼저 추가하고 해당 모듈 검증을 통과한 뒤 다음 단계로 진행한다.

**Goal:** #295–#299를 통해 tenant-scoped API 계약, 환자 포털 shell, commitment/proposal UI, SSE/polling 알림, CI 계약을 1.4.0 milestone 기준으로 구현한다.

**Architecture:** 기존 `frontend/appointment-frontend` Angular 22 standalone application 안에 `/portal` lazy route를 추가한다. `core/api`가 tenant와 precondition header를 소유하고, `features/patient-portal`은 typed facade와 공용 예약 요약 formatter를 사용한다. backend endpoint·도메인 persistence는 변경하지 않는다.

**Tech Stack:** Angular 22, TypeScript 6, RxJS, Angular HttpClient, Vitest/jsdom, 기존 Angular Material toolchain, GitHub Actions.

---

### Task 1: 계약 문서와 workspace 경계 고정

**Files:**
- Create: `DESIGN.md`
- Create: `docs/superpowers/specs/2026-08-11-patient-portal-design.md`
- Create: `docs/superpowers/specs/2026-08-11-patient-portal-visual-reference.html`
- Create: `docs/superpowers/plans/2026-08-11-patient-portal-plan.md`

- [x] 승인된 C 방향, `/portal` route 경계, 상품·회차 표시 순서, 320px 접근성 계약을 위 파일에 고정한다.
- [x] 별도 Angular 앱/React 전환을 제외한 이유와 실제 backend DTO source path를 기록한다.
- [ ] `git diff --check`와 문서 링크 검사를 통과한 뒤 Lore commit으로 저장한다.

### Task 2: #295 tenant-scoped typed API client

**Files:**
- Create: `frontend/appointment-frontend/src/app/core/api/portal-api.models.ts`
- Create: `frontend/appointment-frontend/src/app/core/api/portal-api-error.ts`
- Create: `frontend/appointment-frontend/src/app/core/api/tenant-context.service.ts`
- Create: `frontend/appointment-frontend/src/app/core/api/portal-api-client.ts`
- Create: `frontend/appointment-frontend/src/app/core/api/portal-api-client.spec.ts`
- Modify: `frontend/appointment-frontend/src/app/core/api/index.ts`

- [ ] RED: `PortalApiClient`가 `/api/{tenantCode}`를 만들고 tenant 공백/누락을 거부하며, create/decision 요청에 정확한 header를 넣는 테스트를 작성한다.
- [ ] RED: 412, 410, 428, 503 및 `Retry-After`를 `PortalApiErrorState`로 매핑하는 테스트를 작성한다.
- [ ] GREEN: `TenantContextService`, `PortalApiClient`, DTO union과 오류 mapper를 구현한다.
- [ ] GREEN: raw URL을 component에서 조립하지 않도록 public API를 `requestAppointment`, `getCommitment`, `acceptProposal`, `declineProposal`, `getSlots`로 제한한다.
- [ ] `npm test -- --watch=false --include='src/app/core/api/portal-api-client.spec.ts'`와 `npm run build`를 실행한다.

### Task 3: #296 Codex visualize patient shell

**Files:**
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal.routes.ts`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal-shell.component.ts`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal-shell.component.html`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal-shell.component.scss`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-appointments-page.component.*`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-notifications-page.component.*`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-profile-page.component.*`
- Modify: `frontend/appointment-frontend/src/app/app.routes.ts`
- Modify: `frontend/appointment-frontend/src/styles.scss`
- Test: `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal-shell.component.spec.ts`

- [ ] RED: `/portal` route와 세 nav label, `aria-current`, keyboard focus, 320px overflow 없는 DOM 계약을 테스트한다.
- [ ] GREEN: standalone shell과 route pages를 구현하고 기존 staff route와 분리한다.
- [ ] GREEN: `portal-` CSS token, light/dark surface, direct labels, reduced-motion, 상태 텍스트를 적용한다.
- [ ] 상품명/회차 formatter를 예약·알림에서 공유할 수 있도록 `AppointmentSummary` 타입으로 노출한다.
- [ ] 320px/736px/1024px에서 `scrollWidth <= clientWidth`를 확인하는 jsdom/component 테스트를 추가한다.

### Task 4: #297 commitment/proposal 흐름

**Files:**
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/appointment-summary.ts`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/appointment-commitment.facade.ts`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/components/appointment-card.component.*`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-appointments-page.component.spec.ts`

- [ ] RED: request→proposal, accept/decline, 빠른 연속 클릭, 412 재조회, 410 만료, 503 retry 상태를 facade 테스트로 잠근다.
- [ ] GREEN: idempotency key를 intent별로 생성·재사용하고 마지막 ETag를 decision에 전달한다.
- [ ] GREEN: `PROPOSED/HELD/CONFIRMED/EXPIRED`를 텍스트와 다음 행동으로 표시한다.
- [ ] GREEN: 상품이 있으면 주 제목, 회차가 있으면 `N회차 / M회` 또는 `N회차`만 렌더링하고 빈 placeholder를 만들지 않는다.
- [ ] live region에 성공·충돌·만료 안내를 내보낸다.

### Task 5: #298 SSE/polling notification adapter

**Files:**
- Create: `frontend/appointment-frontend/src/app/core/api/portal-event-stream.adapter.ts`
- Create: `frontend/appointment-frontend/src/app/core/api/portal-event-stream.adapter.spec.ts`
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-notifications-page.component.*`

- [ ] RED: 정상 SSE, 연결 끊김 후 polling fallback, 중복 event, 순서 역전, 오래된 event, tab 재진입 resync를 테스트한다.
- [ ] GREEN: SSE와 polling이 같은 reducer로 `NotificationState`를 갱신하고 backoff/Retry-After를 적용한다.
- [ ] GREEN: 재배정 proposal, 기존 확정 보존, 동의 필요, 만료를 알림에 같은 상품·회차 formatter로 표시한다.
- [ ] 키보드/스크린 리더에서 읽음 토글과 상세 이동을 수행한다.

### Task 6: #299 contract/browser/CI gate

**Files:**
- Create: `frontend/appointment-frontend/src/app/core/api/portal-api-contract.spec.ts`
- Create: `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal.browser.spec.ts`
- Create: `frontend/appointment-frontend/e2e/patient-portal.spec.ts` (Playwright toolchain이 현재 CI에서 실행 가능할 때)
- Modify: `frontend/appointment-frontend/package.json`
- Modify: `frontend/appointment-frontend/package-lock.json`
- Modify: `.github/workflows/frontend-ci.yml`
- Create: `docs/ci/patient-portal-testing.md`

- [ ] RED: contract 테스트가 tenant path, DTO 필수 field, `Idempotency-Key`, `If-None-Match`, `If-Match`, `ETag`, 오류 code drift를 실패시키는 예를 만든다.
- [ ] GREEN: Angular unit/component와 browser scenario를 재현 가능한 npm script로 등록한다.
- [ ] GREEN: CI를 install→contract/unit→browser smoke→production build 순서로 구성하고 각 테스트 job이 실패를 숨기지 않게 한다.
- [ ] Playwright가 추가되면 Node 22.22.3에서 headless Chromium 설치·실행을 고정하고, 미지원 runner에서는 jsdom browser scenario를 required proof로 유지한다.
- [ ] 로컬 명령과 flaky 격리 기준을 한국어 문서로 기록한다.

### Task 7: 최종 검증과 handoff

**Files:**
- Modify: `docs/lessons/2026-08-11-patient-portal.md` when a reusable lesson or novel recovery is found

- [ ] `git diff --check`, targeted Vitest, `npm run build`, accessibility DOM assertions, actionlint를 순서대로 실행한다.
- [ ] 각 이슈 acceptance와 실제 변경 파일을 대조하고 P0/P1을 0으로 수렴한다.
- [ ] 변경 branch/head SHA를 기록하고 PR/merge는 별도 승인 경계로 남긴다.
- [ ] 재사용 가능한 실패·복구·운영 규칙이 없으면 lesson N/A 근거를 final report에 적는다.
