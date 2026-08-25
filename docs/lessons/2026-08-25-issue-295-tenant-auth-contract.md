# Issue #295 tenant API·인증 계약 lesson

## 문제

환자 포털은 이미 tenant path를 사용했지만 직원·관리자 서비스가 `environment.apiUrl`과 raw `HttpClient`로 `/api/...`를 직접 조립했다. 동시에 patient cookie와 workforce Bearer가 하나의 interceptor 규칙에 섞여 있어, tenant 누락·인증 실패를 화면이 일관되게 해석하기 어려웠다.

## 이번에 고정한 해결책

- URL 생성과 `HttpResponse` transport는 `TenantApiClient` 한 곳에 둔다.
- 호출자는 `patient-cookie` 또는 `workforce-bearer` `HttpContext` scope를 명시한다.
- `AuthService`는 token을 저장하지 않고, Gateway/host가 호출할 수 있는 `bootstrap(token, tenantCode?)` seam만 제공한다.
- 환자와 workforce의 `SessionStateService`를 분리해 `authenticated`, `unauthorized`, `forbidden`, `tenant-missing`을 같은 모델로 전달한다.
- SSE처럼 `HttpClient` 밖의 호출도 URL 원본은 `TenantApiClient.url()`에서 얻고, workforce 401/403을 같은 session state에 기록한다.

## 재사용 규칙

1. tenant path를 만드는 서비스별 helper를 추가하지 말고 공통 transport를 재사용한다.
2. cookie와 Bearer를 token 존재 여부로 추론하지 말고 요청 scope로 선언한다.
3. multi-tenant JWT는 현재 context를 조용히 추측하지 않는다. 허용 tenant가 하나일 때만 자동 선택하고, 그 외에는 명시 선택을 요구한다.
4. component fixture가 tenant를 설정하지 않으면 새 계약의 실패를 숨기거나 unhandled rejection으로 남긴다. 관리 화면 spec은 생성 전에 `TenantContextService.setTenant()`를 호출한다.
5. raw URL 회귀는 개별 test만으로 놓칠 수 있으므로 서비스 source 계약 테스트를 두고 `HttpClient`, `environment.apiUrl`, unscoped `/api/`를 함께 검사한다.
6. TypeScript 변경에서도 Kotlin 패턴 지침의 적용 여부를 diff로 증명한다. 적용 대상이 아니면 Kotlin diff 0개를 DoD에 기록한다.

## 검증과 남은 선택

`44개 파일 / 322개 unit test`, production build, `5개 Playwright E2E`가 통과했다. Gradle frontend task의 Node archive verification metadata 누락은 저장소/환경 후속 조치이며, 이 이슈 구현에서 dependency verification을 우회하거나 새 dependency를 추가하지 않았다. 실제 workforce host는 page load 시 비영속 `AuthService.bootstrap()` 호출자를 연결해야 한다.
