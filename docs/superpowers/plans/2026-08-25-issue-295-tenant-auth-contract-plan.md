# Issue #295 tenant API·인증 계약 구현 계획

> **실행 메모:** 이 계획은 승인된 Type A 범위의 실행 기준이다. 각 작업은 이 문서의 순서대로 RED → GREEN → 검증을 수행한다.

**목표:** 환자·직원 API 호출을 tenant-scoped 공통 transport와 분리된 인증 scope로 정렬한다.

**아키텍처:** `TenantApiClient`가 tenant URL과 `HttpResponse` transport를 소유하고 `PortalApiClient`와 management service가 이를 재사용한다. `HttpContext` scope와 `SessionStateService`가 cookie/Bearer 및 인증 실패 상태를 분리한다.

**기술 스택:** Angular 22, TypeScript 6, RxJS, Vitest 4, Playwright 1.62.

---

## 실행 원칙

설계 문서 `docs/superpowers/specs/2026-08-25-issue-295-tenant-auth-contract-design.md`를 기준으로 RED → GREEN 순서로 진행한다. 각 단계는 격리 worktree `feat/issue-295-tenant-auth-contract`에서 실행하고, 실패하면 해당 단계로 되돌아가 원인을 고친다. 새 dependency는 추가하지 않는다.

## 단계별 작업

### 1. 공통 API transport와 인증 scope

- 파일: `src/app/core/api/tenant-api-client.ts`, `src/app/core/api/api-auth-context.ts`, `src/app/core/api/index.ts`
- 작업: tenant URL 인코딩·누락 검증, `HttpContext` scope, response transport, `withCredentials` 정책을 구현한다.
- RED: tenant 누락, path 계약, patient/workforce option과 response header를 검증하는 `tenant-api-client.spec.ts`를 먼저 작성한다.
- GREEN: 최소 구현 후 단위 테스트를 통과시킨다.
- 롤백: 새 파일만 제거하면 기존 PortalApiClient로 복귀할 수 있다.

### 2. 공통 session state와 workforce bootstrap

- 파일: `src/app/core/services/session-state.service.ts`, `src/app/core/services/auth.service.ts`, 관련 spec
- 작업: scope별 상태 signal, `AuthService.bootstrap(token, tenantCode?)`, `allowedTenants` 검증, 비영속 restore seam을 추가한다.
- RED: 허용 tenant 자동 선택·다중 tenant 명시 선택·불일치 폐기·storage 미사용을 테스트한다.
- GREEN: JWT parser와 tenant context를 기존 규칙에 맞게 확장한다.
- 롤백: 기존 `setToken`/`removeToken` public 동작은 유지한다.

### 3. interceptor와 route guard 경계

- 파일: `src/app/core/interceptors/auth.interceptor.ts`, `error.interceptor.ts`, `role.guard.ts`, 각 spec
- 작업: workforce scope에만 Bearer를 붙이고 patient scope는 cookie만 사용한다. 401/403/tenant 누락을 `SessionStateService`에 기록한다.
- RED: scope별 header/credentials, 401·403 상태, role guard 상태 전달을 추가한다.
- GREEN: 기존 snack-bar 동작과 redirect 호환성을 보존한다.

### 4. 기존 client와 patient auth transport 재사용

- 파일: `portal-api-client.ts`, `patient-auth.service.ts`, 관련 spec
- 작업: 직접 `HttpClient`/URL 생성을 제거하고 `TenantApiClient`를 사용한다. Portal API는 `patient-cookie`와 credentials를 명시한다.
- RED: 기존 ETag·`Retry-After`와 cookie credentials assertion을 유지·보강한다.
- GREEN: 기존 40개 test file regression을 통과시킨다.

### 5. management 서비스 tenant 전환

- 파일: `appointment.service.ts`, `clinic.service.ts`, `doctor.service.ts`, `equipment.service.ts`, `slot.service.ts`, `reschedule.service.ts`, `equipment-unavailability.service.ts`, `treatment-type.service.ts`, `dashboard-stats.service.ts` 및 specs
- 작업: raw `HttpClient`와 `environment.apiUrl` 조립을 제거하고 `TenantApiClient` workforce scope를 사용한다. public method와 DTO는 유지한다. SSE fetch도 tenant path와 workforce token을 같은 tenant context에서 만든다.
- RED: 각 spec의 expected URL을 `/api/tenant-a/...`로 바꾸고 tenant 누락·Bearer 경계를 추가한다.
- GREEN: 각 service spec을 모듈별로 통과시키고 static scan으로 raw 호출 0건을 증명한다.
- 롤백: 서비스별 commit 단위로 되돌릴 수 있게 공통 client 도입과 service migration을 분리한다.

### 6. 정적 계약·대표 화면 검증

- 파일: `src/app/core/api/tenant-api-contract.spec.ts`, 필요한 management/route specs
- 작업: management/core service에서 raw `HttpClient`, `environment.apiUrl`, unscoped `/api/` literal을 검사한다. role guard와 tenant state propagation을 대표 화면 테스트로 확인한다.
- 검증: `npm test -- --watch=false`, `npm run build`, 가능하면 `npm run test:e2e`.

### 7. Type A review·문서·delivery evidence

- 파일: `docs/reviews/2026-08-25-issue-295-tenant-auth-contract-inline-review.md`, `docs/lessons/2026-08-25-issue-295-tenant-auth-contract.md`
- 작업: 성능·안정성·보안·운영·개발/API·사용자 관점과 통합 검토를 main session에서 수행하고 P0/P1=0을 확인한다. Kotlin diff 0개를 기록한다.
- 문서 게이트: 각 한국어 artifact에 SPW-01~05와 KO-01~07 결과를 기록한다.
- PR: 사용자 요청 범위에는 merge 권한이 없으므로 merge-ready 보고까지만 진행한다. PR/merge는 최신 head에 대한 별도 승인이 필요하다.

## 수용 기준 추적

| 설계 기준 | 구현 단계 | 증거 |
|---|---|---|
| tenant path 단일화 | 1, 4, 5, 6 | transport/service tests, static scan |
| cookie/Bearer 분리 | 1, 3, 4, 5 | interceptor/client tests |
| workforce bootstrap | 2 | AuthService tests |
| 공통 상태 전파 | 2, 3, 6 | SessionState/guard/error tests |
| DTO/error drift 방지 | 4, 기존 portal contract | existing contract tests + build |
| backend/mobile 비변경 | 전 단계 | `git diff --name-only`, Kotlin/backend/mobile scan |

## 위험과 재실행

- Angular `HttpContext` API 타입 오류가 나면 `HttpRequest` 생성 옵션의 실제 Angular 22 타입을 확인하고 transport 단위부터 재실행한다.
- SSE는 `HttpClient`가 아니므로 tenant URL과 Bearer를 별도 helper로 만들되, URL 원본은 `TenantApiClient.url()`만 사용한다.
- Gradle frontend build는 baseline에서 dependency verification metadata가 없어 실패했으므로 npm build를 주 검증으로 사용하고 Gradle 실패를 재현 가능한 환경 gap으로 보고한다.
- 기존 portal session epoch/cache 테스트가 깨지면 client 추출이 behavior를 바꾼 것이므로 `PortalApiClient`의 specialized error/cache 로직을 복원하고 transport option만 조정한다.

## 계획 문서 게이트

- SPW-01: PASS — 설계 문서와 현재 파일·명령·승인된 범위를 근거로 독자와 실행 조건을 고정했다.
- SPW-02: PASS — 의존 순서, 파일, RED/GREEN, 검증, rollback, hazard, approval gate를 포함했다.
- SPW-03: PASS — 한국어 기술 용어와 실행 동사를 일관되게 사용했다.
- SPW-04: PASS — 모든 설계 수용 기준을 단계와 증거에 매핑했다.
- SPW-05: PASS — 단계·표·코드 경로를 read-back했고 누락된 backend/mobile 변경을 금지했다.
