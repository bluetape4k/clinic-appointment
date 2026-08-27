# Issue #430 API·인증 전송 계약 lesson

## 재사용 원칙

이번 slice의 핵심은 Capacitor용 별도 API 계층을 만드는 것이 아니라, 기존
`TenantApiClient`를 browser와 WebView의 공통 transport로 고정한 것이다.

- tenant encoding과 `/api/{tenantCode}/...` 조합은 `TenantApiClient`에 남긴다.
- `API_AUTH_SCOPE`로 patient cookie와 workforce Bearer를 명시적으로 나눈다.
- patient 요청은 `withCredentials=true`, workforce 요청은 cookie 없이 메모리 Bearer만
  사용한다.
- Angular가 제공하는 `HttpXsrfTokenExtractor`를 재사용하고, cross-origin unsafe
  patient 요청에만 얇은 interceptor를 추가한다. same-origin 요청은 Angular built-in
  interceptor에 위임한다.
- Spring Security가 찾는 CORS source는 항상 제공하되, `enabled=true`일 때만
  `/api/**` mapping을 등록한다. 비활성 source를 없애면 isolated Security context가
  실패할 수 있으므로 이 경계를 테스트로 고정했다.
- backend 테스트 assertion은 `io.bluetape4k.assertions`를 사용한다. 새 helper나
  third-party assertion library를 만들지 않는다.

이 재사용 규칙을 `scripts/validate-frontend-contract.mjs`에도 반영해 raw URL과
`HttpClient` 우회가 다시 들어오면 문서/계약 검증에서 즉시 드러나게 했다.

## API origin과 보안 경계

browser same-origin/proxy는 빈 `apiOrigin`을 사용하고, production의 non-empty origin과
native WebView는 HTTPS origin만 허용한다. runtime override는
`globalThis.__CLINIC_API_CONFIG__`의 typed `apiOrigin`만 읽으며 credentials, path,
query, fragment, wildcard를 거부한다. API base path는 origin과 섞지 않고 기존
`apiBasePath='/api'`로 관리한다.

patient JWT는 `localStorage`나 `sessionStorage`로 복사하지 않는다. workforce token은
기존 `AuthService` 메모리 상태를 유지한다. CORS는 유한한 origin과
`allow-credentials=true`를 함께 요구하며 wildcard는 거부한다.

## XSRF와 SameSite의 실제 운영 조건

cross-origin 요청에서 `HttpXsrfTokenExtractor`가 읽으려면 `XSRF-TOKEN` cookie가 앱
origin에서 읽을 수 있어야 한다. API host에만 있는 host-only cookie를 WebView가
읽는다고 가정하거나 token을 storage로 복사하면 안 된다. 따라서 reverse proxy 또는
same-site 배포 조건을 먼저 정해야 한다.

patient session cookie가 `SameSite=Strict`인 상태에서 native 앱과 API가 cross-site이면
브라우저 E2E의 성공을 native cookie 전송의 증거로 사용할 수 없다. 실제 WebView,
emulator/device, cookie bridge 여부는 #24/#27에서 별도 신뢰 경계로 검증한다.

## 검증 결과

- Frontend: unit 47 files/340 tests, production build, TypeScript, docs validator 모두
  통과했다.
- Browser: Playwright Chromium 12 시나리오가 runtime HTTPS origin에서 login,
  appointment mutation, logout, XSRF header/cookie, CSRF 실패 화면을 확인했다.
- Backend: CORS properties/source 및 Security filter context 9개 focused test와
  `appointment-api` 전체 906개 test(3 skipped)가 통과했다.
- 문서: `audit-korean-terms.mjs` 7개 파일 findings=0, `git diff --check` 통과.

## 후속 작업 경계

- #24: 실제 iOS/Android build, emulator/device cookie와 HTTPS 검증
- #27: native cookie bridge 또는 secure storage가 필요한 경우 새 신뢰 경계 설계
- 운영 배포: API gateway의 DNS/certificate와 허용 origin 목록을 환경별로 관리

이번 lesson의 결론은 “native 전용 복제 구현”이 아니라 “기존 bluetape4k식 공통
transport·assertion·configuration 경계를 재사용하고, 실제 플랫폼 검증만 후속 slice로
분리한다”이다.
