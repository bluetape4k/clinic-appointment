# Issue #27 네이티브 WebView bridge 교훈

## 재사용한 경계

native가 Angular route 문자열을 직접 조립하지 않고, WebView의 순수
`parseNativeDeepLink()`가 scheme·host·tenant·route·query를 한 번 검증한다. 그 결과를
기존 `Router`, `AuthService.allowedTenants`, `TenantContextService`가 소비하므로
browser와 native-like adapter가 같은 authorization·navigation 경계를 공유한다. 이
예제에서 새 native auth/cookie/storage layer를 만들지 않은 것이 핵심이다.

## 순서와 실패 처리

앱 셸 순서는 `WorkforceAuthBootstrapService.restore()` →
`NativeWebViewBridgeService.start()`로 고정한다. native bridge는
`App.addListener('appUrlOpen')`를 등록한 뒤 `getLaunchUrl()`을 한 번 확인하고, 둘 다
`handleUrl()`을 통과시킨다. parser 실패, unknown tenant, unauthenticated session은
router와 tenant mutation 전에 종료하며, router가 `false`/reject하면 event를 발행하지
않는다. listener handle은 `stop()`/destroy에서 한 번만 제거한다.

## 보안·운영 교훈

- URL은 `io.bluetape4k.clinic.appointment://open/{tenantCode}/{route}[?query]`로
  제한하고 portal·unknown query·duplicate/empty value·credentials·port·fragment를
  fail-closed한다.
- 성공 event `clinic.native.navigation.v1`는 versioned typed payload만 전달한다.
  workforce JWT는 기존 메모리 상태를 재사용하고 raw URL이나 token을 event/log/storage에
  복사하지 않는다. tenant scope가 sessionStorage를 쓰는 기존 계약과 token 비영속은
  별개의 경계다.
- Android/iOS metadata는 parser 상수와 같은 scheme/host를 사용하고
  `bridge:verify`로 drift를 차단한다. `cap:sync`가 plugin project를 갱신하지만 실제
  OS URL dispatch는 정적 검사만으로 승격하지 않는다.

## 다음 작업에 남긴 guard

실제 Xcode·Android SDK가 없는 호스트에서 browser E2E를 native 성공으로 보고하지 않는다.
Issue #24에서 signed build, cold-start/background URL, app resume, orientation/IME,
cookie/CORS와 native cache를 실기기·에뮬레이터로 확인한다. dependency version을
업데이트할 때는 `npm ls`, `cap sync`, `cap doctor`, static metadata validator를 함께
실행하고, route/query allowlist를 늘릴 때는 parser rejection matrix와 event version을
동시에 갱신한다.
