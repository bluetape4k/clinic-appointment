# Issue #33 환자 인증 설계

## 문서 상태

- Issue: `#33`
- 유형: Type-A Full Feature
- 대상: `appointment-api`, `appointment-core`, `frontend/appointment-frontend`
- 기준 브랜치: `develop`
- 설계 승인: 2026-08-12 사용자 승인

## 목적

환자가 하나의 tenant 범위 안에서 포털에 로그인하고 예약 관련 보호 화면을
사용할 수 있는 `PATIENT` 인증·세션 경계를 추가한다. 기존 staff/admin의
메모리 bearer 흐름과 Gateway JWT 검증 계약은 유지하면서, 환자 브라우저에는
bearer token을 저장하거나 응답하지 않는다.

이번 설계는 전화번호·이메일·loginId를 모두 로그인 식별자로 지원한다. 세
식별자는 하나의 환자 계정에 여러 개 연결할 수 있으며, API와 도메인에서는
문자열 `key=value`를 직접 파싱하지 않고 구조화된 key/value 값으로 전달한다.

## 현재 근거

- `appointment-api`에는 `JwtTokenParser`, `JwtAuthenticationFilter`,
  `SchedulingUserPrincipal`, tenant authorization manager가 이미 있다.
- JWT parser는 `PATIENT` actor에 `roles=[PATIENT]`, `actorType=PATIENT`,
  `patientSubject`, `allowedTenants`, `exp`, `nbf`에 준하는 시간 계약을 요구한다.
  기존 workforce Gateway token의 호환성을 깨지 않도록 parser의 `nbf` optional
  계약은 유지하되, 이번 PATIENT issuer는 `iat`와 같은 `nbf`를 항상 발급하고
  제공된 `nbf`의 미래 시각은 계속 fail-closed로 검증한다.
- 현재 JWT filter는 `Authorization: Bearer`만 읽고 cookie 세션·로그인·회원가입
  endpoint는 제공하지 않는다.
- `appointment-core`에는 예약의 `patientPhone`과 불투명 `MemberId`가 있지만
  환자 계정·credential 저장소는 없다.
- 포털 API client는 tenant path를 사용하지만 `withCredentials`와 환자 인증
  화면·guard·session bootstrap은 아직 없다.
- 기준선 검증은 `appointment-api` targeted Testcontainers 재실행 6건 통과,
  API 전체 XML 집계에서 환경 의존 Ryuk 오류를 제외한 코드 실패 없음, frontend
  214건 통과로 기록한다. Ryuk 오류는 문서화된 Colima socket 환경변수로
  재실행해 통과했다.

## 범위

### 포함

1. tenant-scoped PATIENT 계정과 로그인 식별자 저장 모델
2. 전화번호·이메일·loginId key/value 정규화 및 중복 검증
3. 회원가입, 로그인, session 조회, 로그아웃, CSRF token bootstrap API
4. 기존 strict JWT claim 계약을 만족하는 HttpOnly cookie 발급·검증
5. cookie 기반 PATIENT 요청의 tenant·role·patientSubject 경계
6. Angular cookie client, PATIENT guard/interceptor, 로그인·회원가입·만료·
   로그아웃 상태 화면
7. 백엔드·프론트엔드·migration·보안 negative test와 한국어 KDoc/API 문서

### 제외

- staff/admin role 개편
- password reset, 계정 복구, MFA, SMS OTP, 이메일 verification provider
- mobile native push 인증
- 외부 회원 디렉터리·결제·상품 시스템과의 계정 병합
- 기존 예약 데이터의 일괄 환자 계정 자동 연결

전화번호·이메일 소유권 검증은 이 예제의 credential 인증과 별도 경계다.
검증되지 않은 식별자의 login 허용 여부는 설정으로 분리하고, production
배포에서는 OTP 또는 외부 verified-member evidence 없이 활성화하지 않는다.

## 대안 검토

### A. 직접 HttpOnly JWT cookie 발급 (채택)

`appointment-api`가 credential을 확인하고 기존 `JwtTokenParser`와 동일한
issuer/audience/signing-key 계약으로 PATIENT JWT를 발급한다. 브라우저는
`appointment_patient_session` cookie만 전송하고 JWT 본문을 볼 수 없다.

- 장점: 현재 API·parser·tenant filter를 재사용하고 BFF라는 새 subsystem을
  추가하지 않는다.
- 단점: API가 credential verifier이자 token issuer가 되므로 signing secret과
  cookie 운영 정책을 명시해야 한다.

### B. 서버 측 BFF session

BFF가 token을 서버에 보관하고 브라우저에는 opaque session ID만 제공한다.
토큰 노출 면적은 더 작지만 BFF 저장소, session revocation, 별도 routing과
운영 컴포넌트를 추가한다. Issue #33의 API·포털 범위를 초과하므로 보류한다.

### C. memory-only bearer + refresh cookie

access token을 메모리에만 두고 refresh token을 cookie로 보관한다. 기존 bearer
filter와의 호환성은 좋지만 Angular 요청에 bearer가 계속 노출되고, 이번 이슈의
명확한 cookie 세션 계약보다 수명·rotation·replay 관리가 커진다. 채택하지 않는다.

## 계정·식별자 모델

### 환자 계정

`appointment-core`에 `PatientAccounts`와 `PatientAccountRepository`를 추가한다.

| 필드 | 규칙 |
| --- | --- |
| `id` | Exposed `LongIdTable` 기본 식별자 |
| `tenantGroupId` | `scheduling_tenant_groups.id` 외래 키. 계정은 정확히 한 tenant에 속한다. |
| `patientSubject` | JWT의 opaque subject. 안전한 ASCII, 계정별 immutable, raw PII 금지 |
| `displayName` | 1~100자의 화면 표시용 이름. credential/로그에는 사용하지 않는다. |
| `passwordHash` | Spring Security `PasswordEncoder` 결과만 저장한다. 원문 비밀번호는 저장·로그·응답하지 않는다. |
| `active` | 비활성 계정은 login/session bootstrap에서 거부한다. |
| `createdAt`, `updatedAt` | UTC timestamp |

`(tenant_group_id, patient_subject)`는 unique로 고정한다. `patientSubject`는
UUID 기반 opaque 값으로 생성하고 전화번호·이메일·loginId를 포함하지 않는다.

### 로그인 식별자 key/value

`PatientLoginIdentities`는 하나의 계정에 여러 로그인 방법을 연결한다.

```kotlin
enum class PatientLoginIdentifierKey {
    PHONE,
    EMAIL,
    LOGIN_ID,
}

data class PatientLoginIdentifier(
    val key: PatientLoginIdentifierKey,
    val value: String,
)
```

테이블은 다음 제약을 가진다.

- `patientAccountId` 외래 키
- `tenantGroupId`를 중복 보관해 tenant-scoped unique index를 단일 테이블에서
  보장한다. repository는 account와 tenant가 일치하는지 함께 검증한다.
- `identifierKey`와 `normalizedValue`
- `(tenant_group_id, identifier_key, normalized_value)` unique
- `(patient_account_id, identifier_key)` unique: 한 계정에 key별 최대 하나
- 한 계정의 식별자 수는 최대 세 개이며 회원가입 요청은 하나 이상이어야 한다.

`key=value`라는 개념은 저장·도메인 모델에서 사용하지만 HTTP에는 다음처럼
구조화된 객체로 보낸다.

```json
{
  "identifier": {
    "key": "PHONE",
    "value": "010-1234-5678"
  },
  "password": "사용자 입력 비밀번호"
}
```

`"PHONE=010-1234-5678"`처럼 포장한 단일 문자열은 escaping, validation,
OpenAPI schema, secret redaction이 취약하므로 허용하지 않는다.

### 정규화

- `PHONE`: 앞뒤 공백을 제거하고 허용된 구분자를 제거한 뒤 한국 전화번호와
  `+82` 입력을 하나의 canonical representation으로 변환한다. 길이·국가
  규칙을 만족하지 않으면 400을 반환한다.
- `EMAIL`: Unicode trim/NFC 후 lowercase하고 RFC 길이 상한을 적용한다.
- `LOGIN_ID`: trim/lowercase하고 안전한 ASCII 식별자와 길이 상한을 적용한다.
- 원문 identifier는 log, metric label, JWT, error response에 넣지 않는다.

정규화 함수는 순수 Kotlin 함수로 두고 key별 경계·중복·동일 계정 연결을
직접 테스트한다.

비밀번호는 UTF-8 기준 12~128자 범위를 요구하고, 공백만 있는 값·control
문자·identifier와 동일한 값은 거부한다. 이 정책은 register와 login의
오류 메시지에 원문을 되돌려 쓰지 않는다.

## HTTP API 계약

모든 endpoint는 `/api/{tenantCode}/auth` 아래에 둔다. public endpoint도
`tenantCode`가 canonical이고 활성 tenant인지 확인하며, tenant path를
요청 body의 값으로 덮어쓰지 않는다.

### CSRF token bootstrap

`GET /api/{tenantCode}/auth/csrf`

- 공개 endpoint
- Spring Security `csrf.spa()` 구성으로 `CookieCsrfTokenRepository`와 SPA용
  request handler를 사용해 `XSRF-TOKEN` cookie를 발급하거나 갱신한다.
- 응답 body에는 secret, JWT, credential을 넣지 않는다.
- Angular는 같은 origin/cors 허용 범위에서 이 endpoint를 먼저 호출하고
  unsafe request에 `X-XSRF-TOKEN` header를 자동 전송한다.

### 회원가입

`POST /api/{tenantCode}/auth/register`

```json
{
  "displayName": "홍길동",
  "password": "길이 정책을 만족하는 비밀번호",
  "identifiers": [
    { "key": "PHONE", "value": "010-1234-5678" },
    { "key": "EMAIL", "value": "patient@example.com" },
    { "key": "LOGIN_ID", "value": "hong.patient" }
  ]
}
```

- `identifiers`는 1~3개, key 중복 금지
- 활성 tenant 확인 후 계정·식별자를 하나의 transaction으로 생성한다.
- identifier가 존재하지 않는 login도 precomputed dummy password hash를 같은
  `PasswordEncoder` 경로로 검증해 존재 여부에 따른 timing 차이를 줄인다.
- 비밀번호는 `PasswordEncoder`로 해시하고 BCrypt/Delegating prefix를
  사용한다. 새 password hashing dependency는 추가하지 않는다.
- 성공은 `201 Created`이며 cookie나 JWT를 응답하지 않는다. 화면은 login으로
  이동한다.
- 이미 사용 중인 key/value는 원문 값이나 다른 계정 존재 여부를 노출하지 않는
  오류 계약으로 반환한다.

### 로그인

`POST /api/{tenantCode}/auth/login`

- identifier key/value를 정규화하고 동일 tenant의 identity를 조회한다.
- 계정 active와 password hash를 검증한다.
- 실패는 존재 여부·실패 원인을 구분하지 않는 `401`이다.
- 성공 시 기존 JWT claim 계약을 만족하는 PATIENT token을
  `appointment_patient_session` cookie로 발급한다.
- 응답 body에는 token, password, 전화번호, 이메일, loginId를 넣지 않고
  `tenantCode`, role, `displayName`, `expiresAt`만 반환한다.

JWT claim은 다음과 같다.

| Claim | 값 |
| --- | --- |
| `sub` | opaque `patientSubject` |
| `roles` | `['PATIENT']` |
| `actorType` | `PATIENT` |
| `allowedTenants` | 로그인한 단일 `tenantCode` |
| `allowedClinicIds` | 빈 집합. clinic scope는 별도 policy가 부여한다. |
| `patientSubject` | 계정의 동일 opaque subject |
| `assurance` | `PASSWORD` |
| `iss`, `aud`, `jti`, `iat`, `nbf`, `auth_time`, `exp` | PATIENT issuer는 `nbf`를 `iat`와 함께 발급한다. 기존 workforce token의 optional `nbf` 호환성은 유지한다. |

### 세션 조회

`GET /api/{tenantCode}/auth/session`

- cookie를 검증해 현재 session summary를 반환한다.
- cookie가 없거나 서명·issuer·audience·`exp`·`nbf`·actor invariant가
  실패하면 `401`을 반환하고 stale cookie 삭제 header를 추가한다.
- principal의 `allowedTenants`에 path tenant가 없으면 `403`이다.

### 로그아웃

`POST /api/{tenantCode}/auth/logout`

- cookie session과 무관하게 cookie deletion header를 발급하는 idempotent
  endpoint로 둔다.
- unsafe cookie request이므로 유효 CSRF token을 요구한다.
- token 본문을 응답하지 않고 `204 No Content`를 반환한다.

### 공통 오류

인증 실패는 `401`, 인증된 principal의 tenant/role mismatch는 `403`, 입력
정규화·검증 실패는 `400`으로 구분한다. 응답과 log에는 raw token, password,
전화번호, 이메일, loginId, patientSubject를 넣지 않는다. correlation ID만
진단용으로 남긴다.

## Cookie·filter·tenant 보안

### Cookie 속성

`appointment_patient_session`은 설정 가능한 이름을 가지되 기본 정책은 다음과
같다.

- `HttpOnly=true`
- protected profile에서 `Secure=true`
- `SameSite=Strict` (배포 topology가 cross-site를 요구할 때만 명시적 완화)
- `Path=/`
- JWT `exp`와 동일한 bounded `Max-Age`
- domain attribute는 설정하지 않아 host-only cookie로 유지

dev/test에서 Secure를 끌 수 있는 설정은 테스트 profile에만 허용하고,
production-style profile에서 insecure cookie를 fail-closed로 거부한다.

### JWT filter

`JwtAuthenticationFilter`는 `Authorization: Bearer`를 우선 사용하고, bearer가
없을 때만 설정된 patient session cookie를 읽는다. 두 token을 합치거나 cookie
값을 로그에 남기지 않는다. parser가 실패하면 anonymous 상태를 유지하고
protected matcher가 401을 결정한다.

`TenantContextFilter`는 csrf bootstrap·회원가입·로그인처럼 principal이 아직
없는 public auth endpoint를 먼저 식별해 기존 cookie principal의 tenant 비교를
건너뛰고, `TenantGroupRepository`로 활성 tenant path만 확인한다. session,
logout 및 모든 portal data endpoint는 parser가 수립한 principal과 path tenant를
정확히 비교한다.

### Authorization과 CSRF

`SecurityConfig`에서 auth matcher를 generic `/api/{tenantCode}/**` rule보다
먼저 배치한다.

- `GET csrf`, `POST register`, `POST login`: public + 활성 tenant
- `GET session`, `POST logout`: PATIENT + tenant authorization
- patient portal data read/write: PATIENT + tenant authorization
- 기존 admin/staff/doctor matcher와 bearer authorization: 변경하지 않는다.

Cookie 기반 unsafe request는 `csrf.spa()`가 구성한 double-submit token을
요구한다. `Authorization` bearer만 사용하는 legacy request는 CSRF matcher에서
제외할 수 있지만 cookie가 존재하는 PATIENT request를 우회시키지 않는다.
Spring Security가 authentication success/logout success에서 CSRF cookie를
비울 수 있으므로 frontend는 초기화·login success·logout success 뒤에
`GET /auth/csrf`를 다시 호출한다. CSRF 누락·불일치, cookie 없는 session,
다른 tenant cookie를 각각 negative test로 고정한다.

## Angular 설계

### AuthService

현재 in-memory bearer token 상태와 별도로 `PATIENT_COOKIE` session mode를 둔다.
환자 token은 읽거나 저장하지 않고 session summary만 signal에 보관한다.

- app bootstrap 또는 portal 진입 시 현재 tenant의 `/auth/session`을 한 번 호출한다.
- 200이면 PATIENT session을 복원한다.
- 401이면 memory state를 비우고 login route로 이동한다.
- 403이면 tenant mismatch 상태를 보여주고 tenant 선택을 재설정한다.
- logout 또는 session expiry 시 patient state를 즉시 clear한다.
- `localStorage`, `sessionStorage`, IndexedDB, URL fragment에 bearer token을
  쓰지 않는 회귀 테스트를 유지한다.

### Interceptor·guard

- `PortalApiClient`의 요청은 `withCredentials: true`로 보낸다.
- auth interceptor는 patient cookie mode에서 `Authorization` header를 추가하지
  않는다. 기존 staff/admin memory bearer mode는 유지한다.
- patient route에는 `patientAuthGuard`를 추가하고 session bootstrap 결과가
  없으면 `/portal/login`으로 `returnUrl`을 보존해 이동한다.
- 401 response는 session expiry 화면 상태와 재로그인 안내로 변환한다.
- 403 response는 접근 거부 상태로 표시하고 다른 tenant data를 렌더링하지 않는다.

### 화면

기존 Codex visualize shell의 카드·nav·focus·contrast 규칙을 재사용한다.

- `/portal/login`: key 선택, identifier, password, 오류·재로그인 안내
- `/portal/register`: displayName, 하나 이상 식별자 key/value, password,
  중복·정규화 오류 표시
- `/portal/*`: 인증된 상태의 appointments/notifications/profile 화면
- 만료·로그아웃·tenant mismatch: credential 또는 token을 노출하지 않는 안내

모든 form control은 label, keyboard focus, aria-live error region을 제공하고,
비밀번호·식별자 원문을 화면 전환 시 query string에 넣지 않는다.

## Migration·호환성·운영

- `appointment-api` migration에 `V26__add_patient_authentication.sql`을
  H2/PostgreSQL/MySQL별로 추가한다.
- table 이름은 기존 규칙에 맞춰 `scheduling_patient_accounts`와
  `scheduling_patient_login_identities`를 사용한다. 기존 `scheduling_*`
  이름은 변경하지 않는다.
- migration은 additive이며 기존 appointment row나 staff bearer token을
  수정하지 않는다.
- 이전 버전은 새 table을 사용하지 않고 새 cookie를 무시하므로 rollback 시
  데이터 파괴가 없다. rollback runbook은 traffic drain, cookie clear, 이전
  application 재기동 순서를 따른다.
- feature property는 protected profile에서 기본 활성화하고, secret·cookie
  설정이 불완전하면 startup을 fail-closed한다.
- login 실패율, 401/403, CSRF failure, duplicate identifier, migration
  readiness를 metric/correlation ID로 관찰하되 PII와 token을 label로 쓰지 않는다.
- login brute-force 방어는 gateway/edge rate limit을 production prerequisite로
  기록하고, API에는 `PatientLoginAttemptLimiter` port를 둬 외부 limiter의
  `429`·retry-after 계약을 연결한다. dev/test는 bounded no-op adapter를 쓰되
  protected production profile에서 limiter bean이 없으면 startup을 fail-closed한다.
  이 예제는 무제한 in-memory attempt map을 만들지 않는다.

## 검증 계획

### 백엔드

1. key별 phone/email/loginId 정규화와 invalid input unit test
2. Exposed repository의 tenant isolation, duplicate key/value, one-account-
   per-key, transaction rollback test
3. password hash가 원문을 보존하지 않는지와 PATIENT token의 `nbf` 포함 issuer/parser
   round-trip, 미래 `nbf` rejection test
4. MockMvc/security integration test: register/login/session/logout, cookie
   flags, no token body, 401/403, invalid `exp`/`nbf`, cross-tenant denial,
   CSRF missing/valid, bearer precedence
5. Flyway H2 migration test와 module compile/test

### 프론트엔드

1. AuthService cookie mode/session bootstrap/expiry/legacy storage cleanup
2. interceptor의 `withCredentials`와 bearer 비혼입
3. PATIENT guard의 login redirect, returnUrl, 401/403 state
4. login/register/logout/expiry component test와 접근성 상태
5. frontend 전체 Vitest와 production build

### 7-tier review gate

설계·계획·diff에 대해 performance, stability, security, operator/ops,
developer/API, user/caller 관점을 각각 검토한다. P0/P1은 구현·PR 진행을
막고, P2/P3은 수정하거나 후속 이슈와 근거를 남긴다. Kotlin pattern gate는
unsafe `!!`, raw credential logging, transaction boundary 누락, public KDoc/API
문서 누락을 별도로 확인한다.

## 수용 기준과 DoD

- [ ] 세 tenant에서 동일 phone/email/loginId가 독립적으로 등록되고 다른 tenant
      account로 해석되지 않는다.
- [ ] 하나의 patient account에 세 key를 모두 연결하고 각 key로 login할 수 있다.
- [ ] browser storage와 response body에 persistent bearer token이 없다.
- [ ] session cookie가 HttpOnly/Secure/SameSite/Path/Max-Age 계약을 만족한다.
- [ ] `exp`/`nbf` 무효 session은 401과 일관된 client state clear를 만든다.
- [ ] path tenant와 principal tenant가 다르면 403이며 다른 tenant 예약·상품·
      회차가 노출되지 않는다.
- [ ] role guard, interceptor, login/register/logout/expiry 화면과 tests가
      #299 CI에서 통과한다.
- [ ] API/core/frontend module-scoped validation, migration test, 7-tier review,
      Korean KDoc/API docs와 lesson을 보존한다.
- [ ] issue #33에 연결된 Korean PR이 `develop`을 base로 생성되고 exact head의
      required CI·review thread·metadata가 merge 전 검증된다.

## 위험과 후속

- OTP/email verification과 password recovery가 없으면 실제 production account
  assurance가 부족하다. 이 기능을 production으로 승격하기 전 별도 issue와
  external authority evidence가 필요하다.
- tenant별 동일 식별자를 허용하므로 client가 tenant context를 임의 변경할 수
  없도록 server path authorization과 session bootstrap을 함께 검증한다.
- API가 직접 JWT를 발급하는 선택은 auth service와 signing secret 운영 책임을
  명확히 한다. 외부 Gateway를 도입할 때는 issuer를 분리하고 cookie/BFF 계약을
  재검토한다.
