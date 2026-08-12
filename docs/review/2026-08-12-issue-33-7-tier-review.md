# Issue #33 환자 인증 7-tier code review

검토일: 2026-08-12
검토 브랜치: `feat/issue-33-patient-auth`
검토 범위: `appointment-core`, `appointment-api`, `frontend/appointment-frontend`, V26 migration, 운영 문서

## 결론

**코드 결함 기준 PASS — P0 0건, P1 0건, P2 0건, P3 0건.**

초기 PR 검토에서 관찰한 Redis Testcontainer 종료 뒤 Lettuce reconnect loop는
standalone `@SpringBootTest`의 context cache와 bounded Redis command timeout 부재가
겹친 lifecycle gap이었다. 모든 Redis 기반 standalone test가 class 뒤 context를 닫고,
`CacheConfig`가 `RedisURI.timeout`을 명시하며, Toxiproxy downstream latency 회귀가
이를 고정하도록 보정했다. production cookie/DB/limiter canary는 별도 배포 전제라
여전히 `PENDING`이다.

## Seven-tier 결과

| tier | 검토 관점 | 결과 | P0/P1/P2/P3 | 근거 |
|---|---|---|---:|---|
| 1 | 요구사항·계약 | `PHONE`, `EMAIL`, `LOGIN_ID`의 `{key,value}` 계약, tenant session, HttpOnly cookie, logout, 401/403과 기존 workforce bearer 호환을 구현했다. | 0/0/0/0 | `PatientAuthenticationDtos.kt`, `PatientAuthenticationController.kt`, Issue #33 acceptance 조건 |
| 2 | 구조·데이터 | account와 login identity를 분리하고 tenant/account FK와 두 unique 경계를 V26 additive migration에 고정했다. Exposed 저장소 호출은 transaction 경계 안에 있다. | 0/0/0/0 | `V26__add_patient_authentication.sql`, `PatientAccountRepository.kt`, `PatientLoginIdentityRepository.kt` |
| 3 | 보안·프라이버시 | cookie가 bearer보다 낮은 우선순위이고, raw JWT를 response/storage/log에 노출하지 않는다. CSRF SPA bootstrap, tenant grant, opaque subject, dummy hash, malformed cookie 삭제, protected profile limiter fail-closed를 확인했다. | 0/0/0/0 | `JwtAuthenticationFilter.kt`, `PatientCsrfRequestMatcher.kt`, `SecurityConfig.kt`, `PatientAuthenticationService.kt` |
| 4 | 정확성·동시성 | identifier 정규화와 중복 검증, inactive account, 만료/미래 token, subject/role mismatch, tenant mismatch를 음성 경로로 고정했다. 기존 token에 `nbf`가 없다는 이유로 전역 계약을 깨지 않는다. | 0/0/0/0 | core/API auth tests, `PatientJwtIssuerTest`, `JwtTokenParserTest`, `TenantContextFilterTest` |
| 5 | API·frontend UX | Angular는 실제 login 화면을 통해 structured identifier를 전송하고, tenant code만 검증해 sessionStorage에 복원한다. token/patient data 영속 저장은 없다. route guard와 XSRF interceptor가 cookie 세션과 맞물린다. | 0/0/0/0 | `PatientAuthService`, `TenantContextService`, login/register page specs, Playwright portal flow |
| 6 | 운영·관측성 | V26 rollout/rollback, cookie 폐기, limiter adapter, raw secret redaction, canary 항목을 runbook에 남겼다. 외부 limiter와 운영 canary는 배포 prerequisite로 분리했다. | 0/0/0/0 | `docs/runbooks/patient-authentication.md`, `PatientLoginAttemptLimiter.kt` |
| 7 | 테스트·빌드·전달 | core 696개, auth/security/context targeted 53개, wiring 14개, frontend 37 files/225 tests, Angular build, E2E 3개와 full API 762개가 통과하고 JVM이 정상 종료했다. Toxiproxy latency 회귀가 Redis cache close의 bounded wait를 고정한다. PR/CI/merge는 이 검토의 후속 delivery gate다. | 0/0/0/0 | 아래 검증 기록 |

## `bluetape-kotlin-patterns` 대조

- Exposed 접근은 `transaction {}` 내부에 있고, repository는 immutable record와 명시적
  결과 타입을 반환한다.
- raw `!!` 대신 `checkNotNull`/명시적 예외 경계를 사용하고, nullable principal과
  deferred CSRF token을 안전하게 처리한다.
- token/password/PII를 storage, response, log에 기록하지 않으며, 누락 identity도
  같은 `PasswordEncoder` 경로를 탄다.
- bearer와 cookie의 transport 우선순위, tenant context cleanup, request 경계의
  `SecurityContext` cleanup을 코드와 테스트에 함께 고정했다.
- 프로세스 내부 unbounded rate-limit map이나 새 dependency를 추가하지 않았다.

## 검증 기록

```text
./gradlew --no-daemon :appointment-core:test --tests '*PatientLoginIdentifierTest' \
  :appointment-api:test --tests '*PatientAuthenticationServiceTest' \
  --tests '*PatientLoginIdentifierNormalizerTest' \
  --tests '*PatientAuthenticationControllerTest' \
  --tests '*GlobalExceptionHandlerPatientAuthTest' \
  --tests '*PatientJwtIssuerTest' --tests '*JwtAuthenticationFilterTest' \
  --tests '*PatientCsrfRequestMatcherTest' --tests '*SecurityConfigFilterOrderTest' \
  --tests '*TenantContextFilterTest' --tests '*JwtTokenParserTest'
SUCCESS: Executed 53 tests
```

```text
ApplicationContextRunner wiring/auth contexts: SUCCESS: Executed 14 tests
Core module with Docker socket override: SUCCESS: Executed 696 tests
Angular unit tests: 37 files, 225 tests passed
npm run build: Angular build complete
npm run test:e2e -- --reporter=line: 3 passed
```

```text
DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew --no-daemon :appointment-api:test --console=plain
SUCCESS: Executed 762 tests in 4m 46s (3 skipped)
BUILD SUCCESSFUL in 4m 50s
```

별도 Toxiproxy 회귀도 `SUCCESS: Executed 1 tests`로 통과했고, Redis command timeout
양수 검증을 포함한 targeted suite는 `SUCCESS: Executed 6 tests in 7.9s`였다. 이
실행들의 exit code는 모두 0이며, 초기 reconnect loop는 더 이상 재현되지 않았다.

## 후속 delivery gate

- [ ] production profile에 실제 `PatientLoginAttemptLimiter` adapter를 주입한다.
- [ ] tenant 1곳에서 CSRF/register/login/session/logout canary와 cookie 속성 evidence를 수집한다.
- [ ] PR body의 issue/milestone/assignee/labels와 마지막 `## DoD Status`를 live GitHub에서 확인한다.
- [ ] CI가 exact PR head에서 통과한 뒤에만 fresh merge approval을 요청한다.
