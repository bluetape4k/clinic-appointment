# Issue #430 API·인증 전송 계약 구현 7-Tier 검토

## 검토 범위와 기준

- 대상: `feat/issue-430-api-auth-contract`의 #430 slice
- stacked base: #23 PR #432 head `c2275ff9dc16c6e64829ffb4da9015331a84be0a`
- 검토 기준: `bluetape-workflow`, `bluetape-kotlin-patterns`,
  `bluetape-full-feature`, `bluetape-writer`
- 검토일: 2026-08-27
- 사용자 경계: #23 PR은 병합하지 않으며, Epic #13의 모든 slice가 끝난 뒤 한 번만
  최종 병합 승인을 받는다.

## 7-Tier 결과

| Tier | P0 | P1 | P2 | P3 | 근거와 판정 |
|---|---:|---:|---:|---:|---|
| 1. Performance | 0 | 0 | 0 | 0 | `api-endpoint.ts:20-80`의 origin `URL` 정규화와 `xsrf.interceptor.ts:10-31`의 token 조회는 요청 경계의 유한 작업이다. 새 polling·retry·connection·무제한 buffer는 없다. 수치 benchmark와 native 측정은 이 slice의 수용 기준이 아니므로 N/A로 기록한다. |
| 2. Stability | 0 | 0 | 1 | 0 | `ApiCorsConfiguration.kt:17-29`가 source bean을 항상 제공하고 enabled일 때만 `/api/**` mapping을 등록해 Spring Security context 실패를 막는다. 같은 설정을 protected/no-op chain에 import한다(`SecurityConfig.kt:62-65,702-705`). native `SameSite=Strict` cookie 실동작은 브라우저 테스트로 대체하지 않고 #24/#27로 분리한다. |
| 3. Security | 0 | 0 | 1 | 0 | `ApiCorsProperties.kt:24-70`이 wildcard·credentials·origin 구성요소·운영 HTTP를 거부하고, `api-endpoint.ts:52-80`이 native/production HTTPS와 origin-only를 강제한다. patient JWT storage 우회가 없고(`tenant-api-contract.spec.ts:38-48`), patient XSRF와 workforce Bearer scope를 분리한다. 앱 origin에서 읽을 수 있는 XSRF cookie와 실제 cookie 정책은 배포자가 고정해야 한다. |
| 4. Operator/Ops | 0 | 0 | 1 | 0 | `application.yml:50-60`은 CORS를 disabled-by-default로 두고 유한한 origin·credentials·preflight 정책을 문서화한다. 운영 cross-origin 배포는 `allowed-origins`와 HTTPS를 함께 설정해야 하며 API gateway/DNS/certificate는 이 slice에 포함하지 않는다. |
| 5. Developer/API | 0 | 0 | 0 | 0 | `TenantApiClient`가 tenant encoding과 `API_AUTH_SCOPE`를 계속 소유하고(`tenant-api-client.ts:31-60`), Angular `HttpXsrfTokenExtractor`와 Spring CORS 통합 지점을 재사용한다. 새 third-party dependency, raw `HttpClient`, patient token storage를 추가하지 않았다. backend 새 테스트는 모두 `io.bluetape4k.assertions`를 사용한다. |
| 6. User/Caller | 0 | 0 | 1 | 0 | Playwright가 runtime origin에서 login·appointment mutation·logout 및 CSRF 실패 상태를 검증한다(`e2e/api-origin-contract.spec.ts:43-237`). README와 요구사항 문서는 browser proxy, native HTTPS, host-only cookie 한계와 #24/#27 경계를 설명한다. native 실기기 성공으로 해석하지 않는다. |
| 7. Main-session integration | 0 | 0 | 0 | 0 | workflow receipt `20260827T060254Z-9b1e2453`의 topology에 `module-build/module-unit`을 포함해 frontend와 backend 증거를 함께 요구한다. root dirty 파일은 건드리지 않았고, #23 PR #432는 열린 상태로 이 branch의 base에만 사용한다. |

### 종합 판정

- **P0 = 0, P1 = 0**
- **P2 = 4**: native cookie 측정 경계(#24/#27), 배포 CORS 책임, 앱 origin에서
  읽을 수 있는 XSRF cookie가 필요한 운영 계약, native caller 검증 경계
- **P3 = 0**
- 차단 결함은 없으며, 위 P2는 문서·Issue 경계가 명확한 후속 검증이다.

## Kotlin 및 bluetape4k 적용 점검

### `bluetape-kotlin-patterns` 최종 체크

| 체크 | 결과 | 증거 |
|---|---|---|
| KT-FIN-01..03 null 안전성·불변성·표현식 | PASS | `ApiCorsProperties`의 `val`과 기본 불변 목록, `require` 검증, nullable 결과의 명시적 처리 |
| KT-FIN-04..06 API 경계·예외·이름 | PASS | `ApiCorsConfiguration`의 단일 bean 책임, 설정 오류의 `IllegalArgumentException`, 설명적인 property/테스트 이름 |
| KT-FIN-07..09 재사용·동시성·리소스 | PASS | Spring 제공 CORS source와 기존 Security chain 재사용; 새 `GlobalScope`, `runBlocking`, `Thread.sleep`, `await`, `delay` 없음 |
| KT-FIN-10..11 문서·정적 품질 | PASS | 한국어 KDoc, `git diff --check`, 문서 validator 및 용어 audit 통과 |
| KT-SPR-01..05 | PASS | `@Configuration(proxyBeanMethods = false)`, `@EnableConfigurationProperties`, 두 Security profile import, `.cors {}` 순서, disabled empty source 검증 |
| KT-TEST-01..05 | PASS | RED→GREEN, bluetape assertions, property/source/context 테스트, 모듈 전체 회귀 실행 |

새 Kotlin 테스트에는 JUnit assertion/AssertJ/Kluent를 쓰지 않았으며, 다음 bluetape
assertion만 사용했다.

- `io.bluetape4k.assertions.assertFailsWith`
- `io.bluetape4k.assertions.shouldBeEqualTo`
- `io.bluetape4k.assertions.shouldBeTrue`
- `io.bluetape4k.assertions.shouldNotBeNull`

### 동시성·위험 패턴 빠른 검사

변경 대상 Kotlin 파일에서 새 `GlobalScope`, `runBlocking`, `Thread.sleep`, `await`,
`delay`, 광범위 catch를 발견하지 못했다. `appointment-api`의 기존
`NearCacheAdapter`/다른 설정 파일에 있는 기존 `await`와 catch는 이번 diff의 추가가
아니므로 N/A로 기록한다.

## 검증 증거

| 영역 | 명령/증거 | 결과 |
|---|---|---|
| Workflow | `bluetape-flow.py verify --run-id 20260827T060254Z-9b1e2453` | `ok=true`, topology checksum `133f474f...` |
| Frontend unit | `npm test -- --watch=false` | 47 files, 340 tests passed |
| Frontend build | `npm run build` | Angular 22 production build passed |
| TypeScript | `npx tsc --noEmit -p tsconfig.app.json` | passed |
| Contract/docs | `npm run docs:verify` | `ok=true`, documentsChecked=10, sourceChecks=8, failures=[] |
| Browser E2E | `npm run test:e2e` | Chromium 12 scenarios passed |
| Backend focused | `:appointment-api:test` CORS properties/source + `SecurityConfigFilterOrderTest` | 9 tests passed |
| Backend module | `./gradlew :appointment-api:test --no-daemon --max-workers=1 --console=plain` | 906 tests passed, 3 skipped, build successful |
| Korean artifact | `audit-korean-terms.mjs` | 7 files, findings=0 |
| Diff hygiene | `git diff --check` | passed |

`npm audit`의 개발 도구 경고나 native SDK/device build는 이 slice의 성공으로 포장하지
않는다. native build·실기기 cookie 정책·bridge가 필요하면 각각 #24와 #27에서 다시
검증한다.

## 최종 결론

**PASS — PR 생성 및 exact-head CI 대기 단계로 진행 가능.** 구현 범위에서 P0/P1은
없고, 공통 `TenantApiClient`·Angular XSRF·Spring Security CORS·bluetape assertions를
재사용했다. 이 PR은 merge-ready로만 유지하며 Epic #13 전체 완료 전에는 병합하지 않는다.
