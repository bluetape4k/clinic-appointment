# Issue #430 API·인증 전송 계약 실행 계획 3-R 검토

## 검토 범위

- 대상: `docs/superpowers/plans/2026-08-27-issue-430-api-auth-contract-plan.md`
- 기준 base: #23 PR #432 head `c2275ff9dc16c6e64829ffb4da9015331a84be0a`
- 검토일: 2026-08-27
- 적용 규칙: `bluetape-workflow`, `bluetape-full-feature`,
  `bluetape-kotlin-patterns`, `bluetape-writer`

## 여섯 관점 검토

| 관점 | P0 | P1 | P2 | P3 | 판정과 근거 |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 1 | 0 | origin `URL` 정규화와 XSRF token 추출은 요청당 유한 작업이며 별도 benchmark 대상이 아니다. |
| Stability | 0 | 0 | 1 | 0 | 외부 thread·retry·connection을 추가하지 않고 CORS source는 항상 제공하되 `/api/**` mapping만 opt-in으로 제한한다. native cookie 실동작은 #24/#27 경계다. |
| Security | 0 | 0 | 0 | 0 | HTTPS·origin-only·wildcard 거부, patient `withCredentials`, XSRF scope, patient JWT storage 금지와 Bearer 메모리 경계를 테스트로 고정한다. |
| Operator/Ops | 0 | 0 | 1 | 0 | same-origin 기본 동작을 보존하고 cross-origin 운영자는 `allowed-origins`와 credentials를 함께 설정해야 한다. startup validation과 `application.yml` 설명이 있다. |
| Developer/API | 0 | 0 | 0 | 0 | 기존 `TenantApiClient`, `HttpXsrfTokenExtractor`, Spring Security CORS 지점을 재사용하며 새 dependency나 raw client를 만들지 않는다. |
| User/Caller | 0 | 0 | 1 | 0 | frontend README와 요구사항 문서가 browser proxy, native runtime origin, cookie/XSRF, SameSite 한계를 명시한다. |

## 실행 가능성 확인

1. workflow receipt의 component check 수를 manifest 한도인 8개로 맞추고,
   `module-build`와 `module-unit`에 frontend·backend 명령을 함께 기록한다.
2. RED는 endpoint/XSRF/client와 backend CORS property/source에 한정하고, GREEN은
   focused test → build/typecheck/full unit → E2E → API module test 순서로 실행한다.
3. `TenantApiClient`가 tenant encoding과 auth scope를 계속 소유하므로 기존
   `PortalApiClient`, `PatientAuthService`, workforce 서비스의 호출자 변경이 없다.
4. frontend 문서 validator도 `environment.apiUrl` 문자열 복제가 아니라 공통
   `TenantApiClient`와 scope 재사용을 검사하도록 맞춰, ecosystem 재사용 회귀를
   실제 source 계약으로 감시한다.
5. Kotlin 테스트는 `io.bluetape4k.assertions`만 사용하며 `!!`, JUnit assertion,
   AssertJ, Kluent를 새로 도입하지 않는다.

## 위험과 후속 경계

- `SameSite=Strict` cookie가 native cross-site WebView에서 전송되는지는 browser
  E2E로 증명할 수 없으므로 #24 실기기 검증으로 남긴다.
- cookie bridge 또는 secure storage가 필요하면 #27에서 별도 신뢰 경계와 설계를
  다시 승인한다.
- Gradle frontend task의 Node archive verification metadata가 별도 환경 이슈로
  남아 있으면 npm 기반 build/unit/E2E와 분리해 기록한다.

## 결론

**PASS — 계획은 실행 가능하다.** P0=0, P1=0이며, 세 가지 P2는 범위와 후속 이슈가
명확하다. 구현 중 origin·auth·CORS 경계가 확장되면 이 계획과 review를 다시 갱신한다.
