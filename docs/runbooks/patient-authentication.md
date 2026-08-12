# 환자 인증 운영 런북

## 목적과 소유권

Issue #33은 tenant별 환자 계정에 `PHONE`, `EMAIL`, `LOGIN_ID` 중 하나 이상을
`{key,value}` 구조로 연결하고, 로그인 성공 시 JWT를 `HttpOnly` cookie로 전달한다.
환자 인증 API와 계정 저장소의 소유자는 `appointment-api`/`appointment-core`이며,
Angular 포털은 cookie 세션과 XSRF header만 소비한다. 기존 직원·관리자 Bearer JWT
계약은 이 기능의 소유 범위가 아니다.

범위에 포함하지 않는 항목은 OTP/SMS·email 소유권 확인, 비밀번호 복구, 기존 member
자동 연결, native push 인증이다. 이 항목들은 별도 설계와 migration 없이는 이번 세션에
추가하지 않는다.

## 배포 전 설정

### 데이터베이스

- PostgreSQL Flyway `V26__add_patient_authentication.sql`을 적용한다.
- migration은 `scheduling_patient_accounts`와
  `scheduling_patient_login_identities`를 추가하는 additive 변경이다.
- tenant·account foreign key와 `(tenant_group_id, identifier_key, normalized_value)`,
  `(patient_account_id, identifier_key)` unique index가 존재하는지 readiness에서 확인한다.
- 기존 migration을 되돌리는 down migration이나 기존 member의 자동 변환을 운영 절차로
  사용하지 않는다.

### Spring 설정

`scheduling.security.patient` 아래 값은 환경별 secret/config 관리 시스템에서 주입한다.

| 키 | 기본값 | 운영 규칙 |
|---|---|---|
| `enabled` | `true` | 기능을 끄더라도 protected endpoint를 공개하는 우회로로 사용하지 않는다. |
| `cookieName` | `appointment_patient_session` | 식별 가능한 고정 이름을 사용하고 변경 시 구 cookie를 별도로 폐기한다. |
| `sessionTtl` | `1h` | 1초 초과 24시간 이하의 bounded 값만 허용한다. |
| `cookieSecure` | `true` | HTTPS 운영에서는 반드시 `true`로 둔다. |
| `cookieSameSite` | `Strict` | cross-site 이동이 필요한 명시적 사유가 없으면 `Strict`를 유지한다. |
| `cookiePath` | `/` | portal API와 동일한 host-only path 계약을 유지한다. |
| `dummyPasswordHash` | BCrypt 고정 hash | 누락 identity도 같은 `PasswordEncoder` 경로로 검증한다. raw password/hash를 로그에 남기지 않는다. |
| `minPasswordLength` / `maxPasswordLength` | `12` / `128` | 정책 완화는 보안 검토와 별도 migration 없이 하지 않는다. |

`scheduling.security.jwt`의 `issuer`, `audience`, `secret`, `allowedClockSkew`도 기존
직원 JWT와 동일한 trust boundary를 유지해야 한다. 환자 issuer는 `iat`와 같은 `nbf`를
발급하지만 기존 직원 token에 `nbf`가 없다는 이유로 전역 parser 정책을 강화하지 않는다.

### Rate limit adapter

명시적 `prod`, `staging` 등 protected profile에서는 `PatientLoginAttemptLimiter`의
실제 adapter를 반드시 주입한다. gateway/분산 rate limiter가 tenant, normalized
identifier key, client fingerprint를 bounded 방식으로 제한해야 하며, 프로세스 내부의
unbounded map을 대체 구현으로 배포하지 않는다. `dev`, `test` 또는 profile이 없는
로컬 context에서만 상태 없는 bounded no-op이 허용된다. adapter가 없으면 protected
profile의 애플리케이션 기동이 실패해야 한다.

## 요청 흐름

모든 경로는 `/api/{tenantCode}/auth` 아래에 있고, `TenantContextFilter`가 public
register/login/CSRF 요청에서도 먼저 active tenant를 확인한다.

1. Angular가 `GET /api/{tenantCode}/auth/csrf`를 `withCredentials`로 호출한다. Spring
   SPA CSRF handler가 deferred token을 materialize하고 `XSRF-TOKEN` cookie를 발급한다.
2. 회원가입은 `POST /register`에 `displayName`, `password`, `identifiers`를 보낸다.
   identifier는 1~3개이며 key는 중복될 수 없다. 성공 응답에는 password, token,
   patient subject가 없다.
3. 로그인은 `POST /login`에 `{ "identifier": { "key": "PHONE|EMAIL|LOGIN_ID", "value": "..." }, "password": "..." }`를 보낸다.
   성공 JWT는 응답 body가 아니라 `HttpOnly` session cookie로만 전달된다.
4. 포털 새로고침은 `GET /session`을 cookie와 함께 호출해 public session summary를
   복원한다. tenant code만 검증 후 `sessionStorage`에 보관하며 token/patient data는
   브라우저 storage에 저장하지 않는다.
5. 로그아웃은 CSRF header와 함께 `POST /logout`을 호출하고, 서버는 credential 유무와
   무관하게 동일 속성의 deletion cookie를 반환한다.

Bearer `Authorization` header가 있으면 patient cookie보다 우선한다. cookie가 malformed,
중복 또는 만료되어 parser가 거절하면 raw token을 응답·로그에 넣지 않고 deletion cookie만
반환한다. 인증된 cookie의 tenant와 URL tenant가 다르면 `403`으로 종료한다.

## Rollout

1. 먼저 V26 schema와 index를 적용하고 migration readiness를 확인한다.
2. protected profile에 실제 limiter adapter, JWT secret/issuer/audience, HTTPS cookie
   설정을 함께 주입한다. readiness에서 limiter bean과 active tenant 조회를 확인한다.
3. 낮은 비율의 한 tenant canary에서 CSRF bootstrap → register → login → session →
   logout을 실행한다. 성공률, 401/403, CSRF 실패, limiter 거절, cookie 속성을 기록한다.
4. canary에서 raw JWT·password·identifier가 애플리케이션 로그, access log, tracing
   baggage, browser storage에 나타나지 않는지 확인한 뒤 tenant를 단계적으로 확대한다.
5. 운영 canary/SLO 증거는 현재 로컬 검증의 범위가 아니므로 배포 승인 시 `PENDING`으로
   표시하고 실제 환경 결과로 갱신한다.

## Rollback

- V26은 additive이므로 애플리케이션을 이전 버전으로 되돌릴 때도 schema를 먼저 삭제하지
  않는다. 구 버전이 새 테이블을 사용하지 않는지 확인한 뒤 별도 migration 계획으로만
  정리한다.
- cookie 설정 또는 서명 key를 되돌릴 때는 이전 cookie 이름을 잠시 읽어들이는 우회로를
  만들지 않는다. 영향을 받은 브라우저에 동일 속성의 deletion cookie를 내려 stale
  session을 폐기하고 다시 로그인하게 한다.
- rollback 중에는 patient auth endpoint를 protected profile에서 비활성화할 수 있지만,
  인증이 필요한 기존 workforce endpoint를 공개 상태로 바꾸지 않는다.
- limiter adapter나 JWT trust 설정이 불완전하면 fail-open하지 말고 애플리케이션을
  readiness 실패 상태로 유지한다.

## 장애 진단

| 증상 | 확인 순서 | 조치 |
|---|---|---|
| register/login이 404/`RESOURCE_NOT_FOUND` | active tenant, V26 migration, tenant path | tenant 활성화와 migration readiness를 먼저 복구한다. |
| login이 401 | identifier 정규화, account active, password encoder, limiter | raw credential을 로그로 수집하지 말고 동일 입력으로 재현한다. |
| login/logout이 403 | XSRF cookie/header와 CSRF bootstrap 호출 | `GET /csrf`를 먼저 호출하고 Angular XSRF interceptor 계약을 확인한다. |
| session이 다른 tenant에서 403 | URL tenant와 JWT `allowedTenants`/subject | cross-tenant 우회로를 만들지 말고 cookie를 삭제한 후 올바른 tenant에서 재로그인한다. |
| protected profile 기동 실패 | `PatientLoginAttemptLimiter` bean, JWT 설정 | 실제 limiter와 trust 설정을 주입한 뒤 재기동한다. no-op을 운영 profile에 복사하지 않는다. |
| 반복된 stale cookie | cookie name/path/samesite 변경 여부 | 기존 cookie를 동일 path로 삭제하고 새 session을 발급한다. token 값은 출력하지 않는다. |

운영 조사에서는 correlation id, tenant code, outcome, status, limiter decision처럼
비민감 metadata만 기록한다. JWT 원문, password, normalized phone/email/loginId,
patient subject는 로그·metric label·exception message에 넣지 않는다.
