# Issue #430 Capacitor WebView API·인증 전송 계약 설계

## 결정 요약

Capacitor WebView와 일반 브라우저가 같은 `TenantApiClient`와 인증 상태 모델을
계속 사용하도록 API origin만 명시적으로 주입한다. 브라우저 개발 환경은 기존
`/api` proxy를 유지하고, production/native 환경은 정규화된 HTTPS origin을
`environment` 또는 `globalThis.__CLINIC_API_CONFIG__`에서 선택한다. tenant path는
기존 client가 만들고, patient cookie와 workforce Bearer는 `API_AUTH_SCOPE`로
분리한다.

Angular 기본 XSRF interceptor는 같은 origin 요청만 처리하므로, cross-origin
patient mutation에는 기존 `HttpXsrfTokenExtractor`와 `X-XSRF-TOKEN` 이름을
재사용하는 얇은 interceptor를 추가한다. 이 interceptor는 `patient-cookie`
scope와 unsafe method에서만 동작하고 이미 있는 header를 덮어쓰지 않는다.

API는 Spring Security의 CORS 통합 지점을 사용한다. CORS는 기본적으로 꺼 두고,
운영 또는 native 배포가 `scheduling.security.cors.enabled=true`와 유한한 HTTPS
origin 목록을 함께 설정할 때만 `/api/**`에 적용한다. wildcard origin과
credentials 조합은 설정 단계에서 거부한다. patient cookie의 `HttpOnly`,
`Secure`, `SameSite=Strict` 정책과 workforce token의 메모리 전용 경계는 이
변경에서 바꾸지 않는다.

이번 설계는 사용자가 승인한 #430 stacked slice의 범위로 고정한다. #23 PR
#432는 병합하지 않고 이 브랜치의 base로만 사용한다.

## SPW-01 — 독자·목적·근거

- **독자:** `clinic-appointment` 유지보수자, 모바일 WebView 통합 담당자, Issue #430 검토자
- **언어:** 저장소 로컬 규칙에 따라 한국어. 코드 토큰·명령·URL·설정 키는 원문 유지
- **Issue:** [#430](https://github.com/bluetape4k/clinic-appointment/issues/430)
- **선행 slice:** [#23 PR #432](https://github.com/bluetape4k/clinic-appointment/pull/432)
- **현재 공통 경계:**
  - `frontend/appointment-frontend/src/app/core/api/tenant-api-client.ts`
  - `frontend/appointment-frontend/src/app/core/api/api-auth-context.ts`
  - `frontend/appointment-frontend/src/app/core/services/patient-auth.service.ts`
  - `frontend/appointment-frontend/src/app/core/services/auth.service.ts`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- **기존 회귀 근거:** frontend baseline `npm test -- --watch=false` — 45 files, 327 tests 통과
- **공식 근거:** Angular `withXsrfConfiguration`, Spring Security CORS, Spring MVC CORS 문서와
  Angular 22 local source를 확인했다. Angular 기본 interceptor는 request origin과
  page origin이 다르면 XSRF header를 추가하지 않는다.
- **승인 상태:** 2026-08-27 사용자 `승인`으로 이 설계의 bounded implementation을 시작한다.

## 범위와 제외 범위

### 포함

1. `apiOrigin`과 `apiBasePath`를 분리하고, empty origin은 browser same-origin/proxy로
   해석한다.
2. runtime override는 `globalThis.__CLINIC_API_CONFIG__`의 `apiOrigin`만 읽으며
   storage나 query string을 사용하지 않는다.
3. native에서 origin이 없으면 즉시 실패하고, production의 비어 있지 않은 origin이
   HTTP이면 거부한다. 모든 origin에 credentials·path·query·fragment가 들어오면
   거부한다.
4. 기존 tenant encoding과 `/api/{tenantCode}/...` path를 유지한다.
5. `TenantApiClient`가 patient cookie scope의 `withCredentials` 기본값을 `true`로
   정하고 workforce Bearer scope에는 cookie를 보내지 않도록 한다.
6. `HttpXsrfTokenExtractor`를 이용한 cross-origin patient mutation interceptor와
   unit/contract 테스트를 추가한다.
7. Spring Security CORS source를 opt-in configuration으로 제공하고, origin·method·header·
   credentials·exposed header·maxAge 계약을 단위 테스트와 설정 문서로 고정한다.
8. production/native 설정, cookie/XSRF 한계, SameSite 경계를 frontend README와
   API `application.yml`에 기록한다.
9. API origin, patient login/session/logout/mutation, workforce Bearer, 인증 실패와
   tenant 누락을 기존 상태 모델로 검증하는 frontend test/E2E 증거를 추가한다.

### 제외

- backend 인증 방식, JWT 발급/검증, patient cookie 속성 자체의 전면 재설계
- native cookie bridge, secure storage, push notification, offline queue
- `TenantApiClient`를 우회하는 새 HTTP client 또는 새 third-party dependency
- API gateway의 실제 DNS/certificate 발급과 실기기/에뮬레이터 검증(#24)
- `SameSite=Strict`를 `SameSite=None`으로 바꾸는 정책 변경. native에서 cross-site
  cookie가 필요하면 #24/#27에서 명시적인 bridge 또는 same-site 배포 조건을 결정한다.

## 대안 비교와 선택

| 대안 | 장점 | 비용·위험 | 결정 |
|---|---|---|---|
| 기존 `/api` proxy만 유지 | 변경량이 가장 작음 | native WebView에서 운영 API origin과 CORS를 선택할 수 없고 cross-origin XSRF가 누락됨 | 제외 |
| build-time `environment.prod.ts` origin만 사용 | Angular 표준 file replacement로 단순함 | 같은 bundle을 여러 native 환경에서 재사용할 수 없고 runtime contract가 없음 | 보조 fallback |
| typed environment + 제한된 runtime override + custom XSRF + opt-in Spring CORS | 기존 transport·auth·token extractor를 재사용하면서 native 선택·검증 가능 | 배포자가 명시 origin/CORS를 함께 설정해야 하고 실제 cookie 정책은 #24에서 검증해야 함 | **선택** |
| native secure storage/cookie bridge를 이번 slice에 포함 | cross-site cookie 대안을 즉시 제공 | 새로운 신뢰 경계·plugin·수명주기가 생겨 #27 범위를 침범함 | 제외 |

선택안은 API origin을 인증 정보로 취급하지 않는다. origin은 build/runtime 설정에만
두고 patient JWT를 localStorage/sessionStorage에 저장하지 않는다. Bearer token은
기존 `AuthService`의 메모리 상태만 사용한다.

## 구성 요소와 데이터 흐름

```text
environment.apiOrigin 또는 __CLINIC_API_CONFIG__.apiOrigin
        │ validate/normalize (HTTPS, origin-only, no credentials)
        ▼
TenantApiClient.url(path)
        │ /api/{tenantCode}/... 유지
        ├─ patient-cookie + withCredentials=true
        │       └─ built-in/custom XSRF → X-XSRF-TOKEN
        └─ workforce-bearer + withCredentials=false
                └─ AuthService 메모리 token → Authorization: Bearer ...

Spring Security
        └─ empty source 또는 enabled `/api/**` mapping → finite origins + credentials
```

### Frontend origin 계약

- `apiOrigin=''`: browser same-origin 또는 development proxy. native에서 사용하면
  명확한 configuration error를 반환한다. production browser의 same-origin 배포는
  이 값을 허용한다.
- `apiOrigin='https://api.example.test'`: trailing slash를 제거한 origin으로
  정규화한다.
- `http://...`: development browser의 localhost 진단만 허용하고 production의
  cross-origin/native 요청은 거부한다.
- `https://api.example.test/api`: path가 포함되어 거부한다. base path는 항상
  `apiBasePath='/api'`로 관리한다.
- runtime override가 있으면 environment 값보다 우선하지만, 같은 validation을
  다시 거친다. override는 global object를 직접 읽고 storage·URL fragment를 읽지 않는다.

### XSRF 계약

1. GET/HEAD/OPTIONS 또는 `none`/`workforce-bearer` scope는 header를 추가하지 않는다.
2. `patient-cookie` unsafe request만 `HttpXsrfTokenExtractor.getToken()`을 호출한다.
3. token이 없거나 caller가 이미 `X-XSRF-TOKEN`을 지정했으면 request를 그대로 전달한다.
4. `TenantApiClient`가 patient scope의 credentials 기본값을 true로 보장한다.
5. same-origin request는 Angular built-in interceptor에 위임하고, cross-origin request만
   이 interceptor가 token을 보강한다.
6. cross-origin bootstrap은 앱 origin에서 읽을 수 있는 `XSRF-TOKEN` cookie를 제공해야
   하며 API host-only cookie를 storage로 복사하지 않는다.

### Backend CORS 계약

- property prefix: `scheduling.security.cors`
- 기본값: `enabled=false`, `allowed-origins=[]`, `allow-credentials=true`
- enabled일 때 origin은 하나 이상이어야 하고 `*`를 사용할 수 없다.
- enabled일 때 `allow-credentials=false`는 patient cookie 계약을 깨므로 거부한다.
- origin은 `https://`를 기본으로 하며 local 진단용 `http://localhost`와
  `http://127.0.0.1`만 허용한다.
- `/api/**`에 `GET, POST, PUT, PATCH, DELETE, OPTIONS`와
  `Content-Type, Accept, Authorization, X-XSRF-TOKEN, Idempotency-Key, If-None-Match,
  If-Match`를 허용한다.
- `ETag`, `Retry-After`, `X-Correlation-Id`, `X-Tenant-Identity-Generation`을 노출하고
  preflight `maxAge`를 설정한다.
- CORS filter를 Spring Security authentication보다 먼저 실행하고, 기본 security
  chain과 dev/test no-op chain 모두 같은 source를 사용한다. `enabled=false`에서는
  source가 비어 있어 기존 same-origin 요청에 CORS headers를 추가하지 않는다.

## 실패·호환성 계약

| 상황 | 기대 동작 |
|---|---|
| tenant 없음 | 기존 `TenantContextService.requireTenant()` 오류를 network 호출 전에 반환 |
| malformed/external API path | 기존 내부 path validation을 유지 |
| native API origin 없음 | origin configuration error, request 미전송 |
| production의 non-empty/native HTTP origin | HTTPS policy error, request 미전송 |
| CORS enabled + empty/wildcard origin | Spring configuration startup failure |
| patient 401/403 | 기존 `errorInterceptor`와 `SessionStateService`의 `unauthorized/forbidden` 유지 |
| CSRF 실패 | 기존 HTTP error가 patient 상태 모델로 전파되고 Bearer header는 추가하지 않음 |
| patient JWT 저장 검색 | `localStorage`/`sessionStorage`에 patient token key가 없어야 함 |
| SameSite=Strict + cross-site native cookie | 이번 slice가 성공으로 포장하지 않고 #24/#27 검증 경계로 기록 |

기존 browser `/api` proxy와 same-origin XSRF 동작은 regression test로 유지한다.

## 테스트와 수용 기준

### Frontend

- API origin normalization: empty/same-origin, trailing slash, HTTPS, invalid origin,
  production/native HTTP rejection, runtime override precedence
- `TenantApiClient`: encoded tenant path, patient credentials default/override rejection,
  workforce Bearer and no patient storage
- XSRF interceptor: unsafe patient cross-origin header, safe method/scope skip, missing
  token, caller header preservation
- source contract: management services keep `TenantApiClient`; no raw `HttpClient`,
  `environment.apiUrl`, or patient JWT storage
- browser E2E: runtime origin, CSRF/login/session/logout/mutation request header and
  failure state mapping; existing route scenarios remain green
- `npm run build`, `npm test -- --watch=false`, `npx tsc --noEmit -p tsconfig.app.json`,
  `npm run test:e2e`

### Backend

- `ApiCorsProperties` defaults and validation (empty/wildcard/HTTP policy)
- `UrlBasedCorsConfigurationSource` exact `/api/**` mapping, allowed origins/methods/
  headers, exposed headers, credentials and maxAge
- protected and no-op security chains call `.cors {}` without changing CSRF/auth rules
- `./gradlew :appointment-api:compileKotlin`, focused CORS tests, and
  `./gradlew :appointment-api:test`

### Acceptance checklist

- [ ] production/native API origin을 compile-time 또는 제한된 runtime override로 선택 가능
- [ ] patient cookie/XSRF와 workforce Bearer scope가 서로 섞이지 않음
- [ ] CORS/credentials/HTTPS/tenant path가 코드와 테스트로 고정됨
- [ ] 기존 인증 실패·tenant 누락·CSRF 실패 상태 모델 유지
- [ ] patient JWT storage 없음
- [ ] frontend build/unit/contract/TypeScript/browser E2E 통과
- [ ] backend CORS configuration/test 통과
- [ ] 실제 native cookie SameSite 동작은 #24/#27에 남아 있음

## SPW-02~05 문서 게이트

- **SPW-02 구조·탐색성:** 결정 요약 → 범위 → 대안 → 흐름 → 실패 계약 → 검증 순서로
  구성했다.
- **SPW-03 증거·링크:** Issue/PR과 현재 source/test 경로를 직접 연결하고, Angular·Spring
  공식 문서 링크를 구현 review에 남긴다.
- **SPW-04 독자 가치:** native 배포자가 origin/CORS/cookie 한계를 설정 전에 확인할 수
  있으며, caller는 기존 `TenantApiClient`만 사용한다.
- **SPW-05 자연스러운 한국어:** 식별자·설정 키·명령은 보존하고, 설명은 짧은 능동형 문장으로
  작성했다. 구현 후 `audit-korean-terms.mjs`를 실행한다.

## 참고 링크

- [Angular `withXsrfConfiguration`](https://angular.dev/api/common/http/withXsrfConfiguration)
- [Spring Security CORS 통합](https://docs.spring.io/spring-security/reference/7.0/servlet/integrations/cors.html)
- [Spring MVC CORS](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)
