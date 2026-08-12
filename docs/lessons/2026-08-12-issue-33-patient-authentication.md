# Issue #33 환자 인증 구현 lesson

## 배운 점

### 1. 식별자는 `key=value` 계약으로 고정해야 한다

전화번호, email, loginId를 별도 필드로 늘리면 화면·API·DB의 조합이 빠르게
어긋난다. `PHONE`, `EMAIL`, `LOGIN_ID` enum과 `{key,value}` 요청을 하나의 계약으로
두고, tenant와 account에 unique 경계를 각각 두면 한 환자가 최대 세 가지 방식으로
로그인하면서도 서로 다른 환자의 identity가 연결되지 않는다. 정규화는 입력을 단순히
trim하는 것이 아니라 전화번호 canonical form, email NFC/lowercase, loginId safe
ASCII lower form으로 책임을 나눠야 한다.

### 2. HttpOnly cookie는 CSRF bootstrap과 한 세트다

Spring Security의 SPA CSRF handler는 authentication/logout 뒤 token을 지울 수 있고,
deferred token은 실제로 읽어야 `XSRF-TOKEN` cookie가 materialize된다. 그래서 공개
`GET /auth/csrf`를 두고 Angular `withCredentials`와 `withXsrfConfiguration`을 함께
검증했다. cookie를 도입하고 CSRF 흐름을 나중에 추가하면 login/register/logout이
환경별로만 실패하는 회귀가 생긴다.

### 3. tenant 복원에는 sessionStorage만 사용한다

새로고침 뒤 cookie session을 찾으려면 tenant path가 필요하지만, browser storage에
JWT나 환자 정보를 저장해서는 안 된다. 검증된 tenant code만 `sessionStorage`에
보관하고 `/session`으로 실제 권한을 재확인한다. storage 접근 자체가 실패해도
인증을 차단하지 않고, 잘못된 값은 초기화한다.

### 4. profile 기본값은 전체 suite에서 드러난다

처음에는 protected profile만 limiter를 fail-closed하면 된다고 생각했지만,
`ApplicationContextRunner`가 빈 profile과 `test + integration-test` 조합으로 실행되며
기동 경계를 드러냈다. 실제 운영 profile만 외부 limiter를 요구하고, `dev`/`test`와
빈 로컬 profile에는 bounded no-op을 명시적으로 제한하는 방식으로 수정했다. 새로운
bean guard는 단일 wiring test가 아니라 profile 조합을 포함한 전체 context suite에서
검증해야 한다.

### 5. E2E는 service mock보다 실제 화면 흐름을 우선한다

초기 frontend 검증은 service를 직접 호출해도 통과했지만, 실제 login page에서
structured key를 선택하고 CSRF/login/session/notification/SSE 경계를 통과하는지를
보장하지 못했다. Playwright helper가 실제 login route를 거치도록 바꾸자 tenant
session 복원과 시각 fixture 전 인증 상태가 같은 계약을 사용하게 됐다.

### 6. Redis near-cache는 fixture 종료 순서와 command timeout을 함께 고정한다

Spring `@SpringBootTest`가 Redis singleton보다 오래 살아 있으면 Testcontainers가
먼저 내려간 뒤 Lettuce `CLIENT TRACKING OFF` 정리가 reconnect loop를 만들 수 있다.
공통 기반 클래스를 쓰지 않는 통합 테스트도 `@DirtiesContext(AFTER_CLASS)`와
`SAME_THREAD`를 적용해 context를 먼저 닫고, custom `RedisClient`는
`RedisURI.timeout`을 명시해야 한다. `bluetape4k-testcontainers`의
`ToxiproxyServer`와 `RedisServer`를 같은 network에 붙여 downstream latency를 주입하면
정상 응답 경로만 확인하는 cache test가 아닌 종료 경로를 재현할 수 있다. 이 회귀는
전체 API suite가 실제로 JVM을 종료하는지까지 확인해야 완료로 판정한다.

## 재발 방지 규칙

- 신규 인증 방식은 `{key,value}` 또는 명시적 sealed 계약을 먼저 정하고, DB unique
  index·service·controller·Angular form을 같은 테스트 데이터로 연결한다.
- cookie 인증을 추가할 때는 `HttpOnly`, `Secure`, `SameSite`, `Path`, bounded TTL,
  CSRF bootstrap, bearer precedence, stale-cookie deletion을 하나의 checklist로
  검토한다.
- 브라우저 storage에는 tenant context처럼 비밀이 아닌 최소 selector만 둔다. token,
  password, patient subject, normalized identifier는 저장·로그·metric label에 두지 않는다.
- protected profile의 외부 adapter는 fail-closed로 만들고, local/test fallback은
  상태가 없고 bounded인지 코드와 profile matrix test로 확인한다.
- 모듈 전체 테스트가 fixture 종료에서 멈추면 테스트 본문이 통과했다는 이유로 green으로
  보고하지 않는다. Redis 기반 Spring context는 class 뒤에 닫고, command timeout과
  Toxiproxy 장애 회귀를 함께 검증한 뒤 전체 JVM exit code를 확인한다.

## 검증 요약

- core: Docker socket override 환경에서 696 tests 통과
- API auth/security/context targeted: 53 tests 통과
- API wiring/ApplicationContextRunner: 14 tests 통과
- frontend: 37 files, 225 tests 통과; Angular build 통과; Playwright E2E 3개 통과
- full API: 762 tests 통과, 4분 50초에 `BUILD SUCCESSFUL` 및 process exit 0
- Redis lifecycle: Toxiproxy latency 회귀 1개 및 timeout/security targeted 6개 통과
- production limiter/cookie/DB canary: 환경 부재로 `PENDING`

## 공식 참고

- Spring Security CSRF SPA 처리: <https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html>
- Angular XSRF 설정: <https://angular.dev/api/common/http/withXsrfConfiguration>
- Angular HTTP interceptor: <https://angular.dev/guide/http/interceptors>
