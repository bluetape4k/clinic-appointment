# Issue #33 환자 인증 구현 계획

## 목표와 완료 조건

Issue #33의 환자 포탈 인증을 `PHONE`, `EMAIL`, `LOGIN_ID` 세 가지 식별자에 대해
tenant 범위로 구현한다. 등록·로그인·세션 조회·로그아웃과 Angular guard/interceptor를
연결하고, 환자 JWT는 HttpOnly 세션 쿠키로만 브라우저에 전달한다. 기존 staff/admin
Bearer JWT의 호환성은 유지한다.

완료는 다음 증거를 모두 남긴 뒤에만 선언한다.

- V26 additive migration이 H2/PostgreSQL/MySQL 스크립트와 migration contract test로 고정된다.
- 한 환자가 세 식별자 중 하나 이상을 등록할 수 있고, 각 key의 정규화·tenant uniqueness·계정당 key uniqueness가 검증된다.
- 올바른 tenant와 비밀번호로 로그인하면 PATIENT principal과 `nbf=iat`를 포함한 JWT가 발급되고, token/password/PII가 응답·로그에 노출되지 않는다.
- malformed/expired/future-`nbf`/다른 tenant 쿠키는 401 또는 403으로 거절되고 stale cookie가 삭제된다. 기존 `nbf` 없는 workforce JWT는 계속 파싱된다.
- cookie 기반 unsafe request에는 Spring Security SPA CSRF와 Angular XSRF header가 적용되고, login/logout 뒤 CSRF token을 재발급한다.
- production-style profile에서는 실제 edge/adapter rate limiter가 없으면 fail-closed이며, dev/test만 bounded no-op adapter를 사용한다.
- Angular는 bearer token을 local/session storage에 저장하지 않고, patient login/register/session/logout UI와 portal guard를 제공한다.
- 모듈별 테스트·빌드, frontend build, 7-tier review, issue-linked PR CI가 통과한다.

## 범위와 고정된 설계 결정

- HTTP request body의 identifier는 `{key, value}` 구조로 표현한다. 문자열 `PHONE=...`을
  다시 파싱하는 계약은 만들지 않는다.
- 하나의 `patient_account`가 tenant 안에서 `PHONE`, `EMAIL`, `LOGIN_ID`를 각각 최대
  하나씩 가질 수 있다. 등록 시 최소 하나, 최대 세 개이며 중복 key는 400이다.
- `PHONE`/`EMAIL` 소유권 검증과 OTP, 비밀번호 recovery는 이번 이슈에서 제외하고
  production prerequisite로 문서화한다.
- 인증 cookie는 기본 `appointment_patient_session`, `HttpOnly`, protected profile의
  `Secure`, `SameSite=Strict`, `Path=/`, bounded `Max-Age`로 만든다. Authorization
  header가 있으면 기존 Bearer 경로를 우선한다.
- Spring `CookieCsrfTokenRepository` 기본 `XSRF-TOKEN` cookie와 `X-XSRF-TOKEN` header를
  사용하고 `csrf.spa()`로 authentication/logout 뒤 token을 새로 생성한다. Angular의
  built-in XSRF 설정은 이 이름과 맞춘다. 인증 cookie와 CSRF cookie는 별개다.
- JWT parser 전체에 `nbf` 필수 조건을 추가하지 않는다. 기존 workforce token의 optional
  `nbf` 호환성을 보존하고, patient issuer는 항상 `nbf=iat`를 기록한다. JJWT의 future
  `nbf`/expired 검증과 경계 테스트를 유지한다.
- login identifier가 존재하지 않을 때는 precomputed dummy password hash를 같은
  `PasswordEncoder` 경로로 검증해 enumeration timing 차이를 줄인다.
- local unbounded attempt map은 만들지 않는다. `PatientLoginAttemptLimiter` port를 API에
  두고 protected profile은 실제 adapter가 없으면 bean 생성 실패, dev/test는 bounded no-op으로
  구성한다.
- Exposed 조회·변경은 모두 호출자 `transaction {}` 경계 안에서 실행한다. 기존
  `scheduling_*` table 이름과 `TenantGroupRepository` contract를 변경하지 않는다.

## 구현 순서

각 단계는 먼저 RED 테스트를 작성하고, 해당 테스트를 통과시키는 최소 구현을 넣은 뒤,
중복 제거와 KDoc 정리를 수행한다. 아래 명령은 repository root가 아닌 승인된
worktree `/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/issue-33-patient-auth`에서 실행한다.

### 1. Core identity 모델과 repository — RED

추가할 테스트:

- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/identity/PatientLoginIdentifierTest.kt`
  - key별 trim/NFC/lowercase 규칙, 한국 전화번호 및 `+82` canonical form,
    email 길이/형식, loginId safe ASCII/길이, control character/blank 거절을 고정한다.
  - 같은 key 중복과 세 key 조합의 최대치/최소치를 고정한다.
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/PatientAuthenticationRepositoryTest.kt`
  - `AbstractExposedTest`와 `withTables(testDB, TenantGroups, PatientAccounts,
    PatientLoginIdentities)`를 사용한다.
  - tenant별 identifier 조회, 다른 tenant 동일 값 격리, `(tenant,key,value)` 중복,
    `(account,key)` 중복, inactive account 제외, account와 identity FK contract를 검증한다.

RED 실행:

```bash
./gradlew :appointment-core:test --tests '*PatientLoginIdentifierTest' --tests '*PatientAuthenticationRepositoryTest'
```

이 시점에는 새 production type이 없어 컴파일/테스트가 실패해야 하며, 실패 원인을
계획 기록과 commit에 남긴다. 기존 core 테스트는 실행하지 않고 RED 범위만 확인한다.

### 2. Core identity 모델과 repository — GREEN/REFACTOR

다음 파일을 추가/수정한다.

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/identity/PatientLoginIdentifier.kt`
  - `PatientLoginIdentifierKey { PHONE, EMAIL, LOGIN_ID }`, immutable identifier value,
    key별 `normalize`/`validate`, register payload 조합 validator를 둔다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/PatientAccountRecords.kt`
  - `PatientAccountRecord`와 `PatientLoginIdentityRecord`를 nullable DB id 규칙에 맞게 정의한다.
  - `patientSubject`는 opaque safe ASCII subject로만 취급하고 display name/PII를 subject로
    재사용하지 않는다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/PatientAccounts.kt`
  - `scheduling_patient_accounts`: tenant FK, opaque subject unique, bounded display name,
    password hash, active, created/updated timestamp.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/PatientLoginIdentities.kt`
  - `scheduling_patient_login_identities`: account FK, tenant FK, key enum string,
    normalized value, `(tenant,key,value)` unique, `(account,key)` unique.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/PatientAccountRepository.kt`
  - `insert`, `findActiveById`, `findActiveBySubject`, `findActiveByIdentifier`를 제공한다.
    각 method는 transaction을 열지 않고 caller transaction을 요구한다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/PatientLoginIdentityRepository.kt`
  - `insert`, `insertAll`, `findActiveByIdentifier`, `findByAccountId`를 제공하고 tenant/key/value
    조건을 항상 함께 사용한다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RecordMappers.kt`
  - 두 table record mapper를 기존 mapper 규칙으로 추가한다.

GREEN/REFACTOR 실행:

```bash
./gradlew :appointment-core:test --tests '*PatientLoginIdentifierTest' --tests '*PatientAuthenticationRepositoryTest'
./gradlew :appointment-core:test
```

### 3. API migration과 wiring contract — RED

추가할 테스트/지원 파일:

- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/PatientAuthenticationMigrationTestSupport.kt`
  - H2 Flyway V26을 실행하고 두 table, FK, unique index, check/boundary column을 검사한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
  - `V26 patient authentication schema is additive on H2` 테스트와 support 호출을 추가한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/PatientAuthenticationWiringTest.kt`
  - protected profile에 real `PatientLoginAttemptLimiter` adapter가 없을 때 fail-closed
    조건을 검증하고, test profile bounded no-op 구성을 검증한다.

RED 실행:

```bash
./gradlew :appointment-api:test --tests '*PatientAuthenticationMigrationTest*' --tests '*PatientAuthenticationWiringTest'
```

### 4. API migration과 wiring contract — GREEN/REFACTOR

다음 migration을 추가하고 wiring을 확장한다.

- `appointment-api/src/main/resources/db/migration/h2/V26__add_patient_authentication.sql`
- `appointment-api/src/main/resources/db/migration/postgresql/V26__add_patient_authentication.sql`
- `appointment-api/src/main/resources/db/migration/mysql/V26__add_patient_authentication.sql`
  - 모두 additive로 작성하고 기존 `scheduling_*` 이름을 유지한다. down migration이나
    기존 patient/member row 자동 변환은 넣지 않는다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
  - patient account/identity repository와 authentication service의 명시적 bean assembly를
    추가하되 transaction 경계를 service 내부의 명시적 `transaction {}`로 보인다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/PatientLoginAttemptLimiter.kt`
  - port, bounded test/no-op adapter, protected profile guard를 정의한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
  - V26 contract가 기존 V25 및 전체 migration sequence와 함께 통과하도록 갱신한다.

검증:

```bash
./gradlew :appointment-api:test --tests '*FlywayMigrationTest*' --tests '*PatientAuthenticationWiringTest'
```

### 5. API authentication service — RED

추가할 테스트:

- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationServiceTest.kt`
  - 세 key 각각의 정상 login, 동일 tenant 조회, 다른 tenant 조회 실패, inactive account,
    wrong password, missing identifier dummy hash path, duplicate registration, 1/3 key
    registration, 0/4/duplicate key validation을 검증한다.
  - 성공 JWT에 `PATIENT`, `actorType=PATIENT`, `patientSubject`, tenant, `iat`, `nbf=iat`,
    `exp`, `auth_time`, `jti`, `assurance=PASSWORD`가 있고 token/password/PII가 반환되지
    않는지 검증한다.
  - limiter 거절과 예외 mapping, audit log redaction을 검증한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientLoginIdentifierNormalizerTest.kt`
  - HTTP 입력이 core normalizer로 동일 canonical 값이 되는지 고정한다.

RED 실행:

```bash
./gradlew :appointment-api:test --tests '*PatientAuthenticationServiceTest' --tests '*PatientLoginIdentifierNormalizerTest'
```

### 6. API authentication service — GREEN/REFACTOR

다음 파일을 추가한다.

- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationDtos.kt`
  - register/login request, identifier `{key,value}`, session summary, csrf response를 정의한다.
  - token, password, raw normalized PII를 response DTO에 포함하지 않는다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationException.kt`
  - validation/duplicate/invalid-credentials/limiter error와 generic public error code를 정의한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationProperties.kt`
  - cookie name, TTL, secure/same-site/path, dummy hash 설정을 bounded validation과 함께 둔다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationService.kt`
  - explicit transaction orchestration, normalization, `PasswordEncoder`, constant-time
    dummy verification, account/identity persistence, session principal creation을 담당한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/PatientJwtIssuer.kt`
  - 기존 JWT secret/issuer/audience 설정을 재사용하고 patient claim을 발급한다. `nbf=iat`를
    항상 기록하며 secret/raw token을 log하지 않는다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/PatientSessionCookie.kt`
  - `Set-Cookie` 생성/삭제와 bounded Max-Age를 한곳에 둔다.

검증:

```bash
./gradlew :appointment-api:test --tests '*PatientAuthenticationServiceTest' --tests '*PatientLoginIdentifierNormalizerTest'
```

### 7. API HTTP controller와 exception contract — RED

추가할 테스트:

- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationControllerTest.kt`
  - `GET /api/{tenantCode}/auth/csrf`, `POST /register`, `POST /login`, `GET /session`,
    `POST /logout` status/body/header contract를 고정한다.
  - success login은 HttpOnly/Secure profile/SameSite/Path/Max-Age cookie와 summary만 반환하고,
    register는 cookie/token 없이 201, logout은 204와 deletion cookie인지 검증한다.
  - generic 401, validation 400, duplicate 409, tenant mismatch 403, no PII/token/password
    in body/log contract를 검증한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandlerPatientAuthTest.kt`
  - patient error를 기존 `ApiResponse`/security error envelope과 충돌 없이 mapping한다.

RED 실행:

```bash
./gradlew :appointment-api:test --tests '*PatientAuthenticationControllerTest' --tests '*GlobalExceptionHandlerPatientAuthTest'
```

### 8. API HTTP controller와 exception contract — GREEN/REFACTOR

다음 파일을 추가/수정한다.

- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationController.kt`
  - tenant path를 받는 다섯 endpoint를 구현한다. public auth route도 active tenant를
    확인하고, session/logout은 authenticated patient contract를 적용한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
  - generic credential/tenant/validation mapping과 correlation id만 노출하도록 확장한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
  - controller가 사용할 service, cookie, JWT issuer, `PasswordEncoder`, `Clock` bean을 조립한다.

검증:

```bash
./gradlew :appointment-api:test --tests '*PatientAuthenticationControllerTest' --tests '*GlobalExceptionHandlerPatientAuthTest'
```

### 9. Security filter/tenant/CSRF — RED

추가할 테스트:

- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/PatientJwtIssuerTest.kt`
  - issuer output을 기존 `JwtTokenParser`로 parse하고 roles/actor/tenant/subject/time claims를
    검증한다. future nbf/expired token은 null이며 기존 no-nbf workforce fixture는 성공한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtAuthenticationFilterTest.kt`
  - bearer 우선, patient cookie fallback, malformed/expired cookie clear, bearer token raw
    logging 금지를 검증한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/PatientAuthenticationSecurityIntegrationTest.kt`
  - auth public route, patient role route, staff/admin route compatibility, missing auth 401,
    PATIENT wrong role 403, tenant mismatch 403, expired/future nbf 401+cookie clear,
    session/logout CSRF, unsafe no-CSRF 403을 MockMvc/TestSecurityContext로 검증한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfigFilterOrderTest.kt`
  - JWT → tenant context → authorization 순서, `/auth/**` matcher 선행, generic tenant matcher
    shadowing 방지를 고정한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilterPatientAuthTest.kt`
  - public auth/CSRF route는 active tenant만 요구하고 stale/mismatched patient cookie는
    auth layer에서 401/403으로 처리되며 다른 tenant context가 열리지 않는지 검증한다.

RED 실행:

```bash
./gradlew :appointment-api:test --tests '*PatientJwtIssuerTest' --tests '*JwtAuthenticationFilterTest' --tests '*PatientAuthenticationSecurityIntegrationTest' --tests '*SecurityConfigFilterOrderTest' --tests '*TenantContextFilterPatientAuthTest'
```

### 10. Security filter/tenant/CSRF — GREEN/REFACTOR

다음 파일을 추가/수정한다.

- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtAuthenticationFilter.kt`
  - Authorization Bearer를 우선하고 configured patient cookie를 fallback으로 읽는다. parser
    실패 cookie만 safe deletion header로 지우며 bearer failure는 기존 anonymous semantics를 유지한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
  - `csrf.spa()`/`CookieCsrfTokenRepository`, `/auth/csrf` public matcher, register/login public
    matcher, session/logout patient matcher를 generic tenant matcher보다 먼저 배치한다.
  - 기존 staff/admin/commitment authorization manager와 stateless policy를 보존한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilter.kt`
  - public auth route의 active tenant lookup과 authenticated route의 principal tenant membership
    check를 분리하되, 기존 tenant isolation/transaction contract를 유지한다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParser.kt`
  - patient issuer claim 해석에 필요한 보수적 검증만 추가하고, global `nbf` mandatory 변경은 하지 않는다.
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtSecurityProperties.kt`
  - patient cookie/issuer 설정이 기존 workforce secret contract와 충돌하지 않도록 필요한
    bounded property만 추가한다.

검증:

```bash
./gradlew :appointment-api:test --tests '*PatientJwtIssuerTest' --tests '*JwtAuthenticationFilterTest' --tests '*PatientAuthenticationSecurityIntegrationTest' --tests '*SecurityConfigFilterOrderTest' --tests '*TenantContextFilterPatientAuthTest'
```

### 11. Angular patient auth client와 state — RED

추가/수정할 테스트:

- `frontend/appointment-frontend/src/app/core/api/patient-auth-api-client.spec.ts`
  - structured identifier payload, tenant URL encoding, `withCredentials`, csrf/session/register/
    login/logout mapping, no token persistence를 검증한다.
- `frontend/appointment-frontend/src/app/core/services/auth.service.spec.ts`
  - 기존 staff/admin in-memory bearer behavior를 보존하면서 patient session summary,
    patient expiry/logout signal, local/session storage cleanup을 검증한다.
- `frontend/appointment-frontend/src/app/core/interceptors/auth.interceptor.spec.ts`
  - workforce token만 Authorization을 붙이고 patient cookie mode에는 bearer를 붙이지 않는지,
    API request가 credentials를 유지하는지 검증한다.
- `frontend/appointment-frontend/src/app/core/interceptors/error.interceptor.spec.ts`
  - patient 401에서 session state를 clear하고 login route로 유도할 수 있는 error contract,
    staff 401의 기존 behavior를 검증한다.
- `frontend/appointment-frontend/src/app/core/guards/patient-auth.guard.spec.ts`
  - missing/non-patient/expired patient session redirect와 valid patient allow를 검증한다.
- `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-login-page.component.spec.ts`
- `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-register-page.component.spec.ts`
- `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal-shell.component.spec.ts`
  - phone/email/loginId 선택, key=value form state, validation/error, login/register navigation,
    session summary와 logout UI를 검증한다.

RED 실행:

```bash
./gradlew :frontend:appointment-frontend:test
```

### 12. Angular patient auth client와 state — GREEN/REFACTOR

다음 파일을 추가/수정한다.

- `frontend/appointment-frontend/src/app/core/api/patient-auth.models.ts`
  - `PatientLoginIdentifierKey`, structured identifier, register/login/session/csrf DTO를 정의한다.
- `frontend/appointment-frontend/src/app/core/api/patient-auth-api-client.ts`
  - tenant-scoped `/auth/*` 호출을 `withCredentials: true`로 수행하고 token/password를
    browser state에 저장하지 않는다.
- `frontend/appointment-frontend/src/app/core/api/index.ts`
  - 새 client/model export를 추가한다.
- `frontend/appointment-frontend/src/app/core/services/auth.service.ts`
  - workforce bearer와 patient cookie session을 별도 상태로 관리하고, legacy storage를
    제거하며 expiry/session reset signal을 제공한다.
- `frontend/appointment-frontend/src/app/core/interceptors/auth.interceptor.ts`
  - patient cookie mode에서는 Authorization을 생성하지 않고, workforce token 요청만 기존
    header를 사용한다. 모든 auth/portal request에 credentials 정책을 적용한다.
- `frontend/appointment-frontend/src/app/core/interceptors/error.interceptor.ts`
  - 401 patient session을 지우고 route layer가 login으로 보낼 수 있도록 generic error를 유지한다.
- `frontend/appointment-frontend/src/app/core/guards/patient-auth.guard.ts`
  - `ROLE_PATIENT`와 active tenant session을 요구하고 `/portal/login`으로 redirect한다.
- `frontend/appointment-frontend/src/app/app.config.ts`
  - Angular built-in XSRF를 `XSRF-TOKEN`/`X-XSRF-TOKEN`으로 설정하고 interceptor 순서를 고정한다.
- `frontend/appointment-frontend/src/app/app.routes.ts`
  - `/portal/login`, `/portal/register` public route와 `/portal` guarded shell을 구성한다.
- `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal.routes.ts`
  - patient guard를 shell/child route에 적용하고 login/register는 guard 밖에 둔다.
- `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-login-page.component.{ts,html,scss}`
- `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-register-page.component.{ts,html,scss}`
  - Codex visualize style을 유지한 accessible form, key selector, validation/error states를 제공한다.
- `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal-shell.component.{ts,html,scss}`
  - session display, logout action, expired-session notice, nav active state를 제공한다.

GREEN/REFACTOR 실행:

```bash
./gradlew :frontend:appointment-frontend:test
./gradlew :frontend:appointment-frontend:build
```

### 13. End-to-end contract, redaction, compatibility — GREEN

다음 회귀 테스트를 추가하거나 기존 테스트를 확장한다.

- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParserTest.kt`
  - no-`nbf` workforce token compatibility와 patient future `nbf` rejection을 함께 고정한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/TestJwtProvider.kt`
  - patient claim helper와 explicit `nbf` option을 추가하되 기존 default no-`nbf` fixture는 유지한다.
- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/auth/PatientAuthenticationEndToEndTest.kt`
  - register → csrf → login → session → authenticated portal request → logout → 401 흐름과
    다른 tenant cookie denial을 한 테스트 시나리오로 검증한다.
- `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal.routes.spec.ts`
  - login/register/guarded shell route tree를 고정한다.
- `frontend/appointment-frontend/src/app/core/api/portal-api-client.spec.ts`
  - 기존 portal API contract가 patient cookie mode와 함께 깨지지 않는지 확인한다.

검증:

```bash
./gradlew :appointment-api:test --tests '*PatientAuthenticationEndToEndTest' --tests '*JwtTokenParserTest'
./gradlew :frontend:appointment-frontend:test
```

### 14. 운영/rollback 문서와 research 보존

추가/수정할 문서:

- `docs/runbooks/patient-authentication.md`
  - edge rate limit/OTP prerequisite, cookie secret/TTL/Secure 설정, CSRF bootstrap, 로그 redaction,
    deploy drain, stale cookie clear, old-version rollback 순서를 기록한다. V26 down migration은
    사용하지 않고 old app이 새 table을 무시하는 additive rollback만 허용한다.
- `docs/review/2026-08-12-issue-33-7-tier-review.md`
  - 기능, 보안, tenant isolation, API, Kotlin/Angular patterns, 테스트/운영의 7-tier 결과를
    P0/P1/P2와 evidence path로 기록한다.
- `docs/lessons/2026-08-12-issue-33-patient-authentication.md`
  - structured multi-identifier contract, optional workforce nbf compatibility, CSRF refresh,
    rate-limit boundary에서 얻은 재사용 가능한 교훈을 기록한다.
- `/Users/debop/work/bluetape4k/bluetape4k-wiki/research/2026-08-12-spring-angular-cookie-csrf.md`
  - 공식 Spring Security CSRF와 Angular XSRF 문서의 결정 관련 Korean 요약, 원문 URL, 조회일,
    이번 구현에 적용한 contract를 보존한다. wiki 변경은 `git diff --check`, `gno update`,
    `gno embed --collection bluetape4k-wiki`, 대표 `gno search`로 검증한다.

### 15. 최종 검증 및 issue-linked delivery

모듈별 최종 명령:

```bash
./gradlew :appointment-core:test
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :appointment-api:test
./gradlew :frontend:appointment-frontend:test
./gradlew :frontend:appointment-frontend:build
./gradlew :appointment-core:build :appointment-api:build
git diff --check
```

환경상 Ryuk/Colima가 없는 경우 API concurrency test를 위 환경 변수로 재실행하고,
실패가 그 테스트 harness 하나로 한정되는지 XML evidence를 남긴다. 기존 baseline은 API
155 XML/725 tests/환경 실패 1, frontend 33 files/214 tests 통과였으므로, 변경 후 증가한
수와 실패 목록을 비교한다.

최종 7-tier review에서 P0=0, P1=0이어야 한다. P2는 issue/lesson으로 남기고 구현을
완료로 표시할 수 있다. PR body는 Korean, `Closes #33`, milestone/assignee/labels를
issue와 맞추고 마지막 section은 `## DoD Status`로 둔다. CI와 PR head를 확인한 뒤
merge-ready 보고를 먼저 남기며, merge는 fresh explicit approval 이후에만 수행한다.

## 명시적 비범위와 후속 이슈

- OTP/전화번호·이메일 소유권 검증 및 계정 recovery: production identity provider/edge
  prerequisite를 확정하는 별도 issue.
- 환자 profile/appointment/member 자동 link: 기존 `MemberId` 의미를 변경하지 않는 별도
  domain 설계 issue.
- native push notification: Issue #33 비범위, 기존 #32/#34/#35와 독립 처리.
- production canary/SLO/실제 edge limiter 증거: 이 작업에서는 코드 fail-closed와 runbook만
  검증하고 운영 권한/환경이 제공될 때 별도 evidence를 수집한다.
