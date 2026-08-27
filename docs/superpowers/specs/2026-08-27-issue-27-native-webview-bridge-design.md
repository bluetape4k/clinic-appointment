# Issue #27 네이티브 ↔ WebView typed bridge 설계

## 문제와 목표

Epic #13의 Capacitor WebView foundation(#23), API 인증 전송 계약(#430), lazy
bundle(#431), viewport·입력 계약(#26), PWA·오프라인 경계(#25) 위에 네이티브 URL을
기존 Angular router·session 상태로 연결한다. 브라우저에서는 Capacitor plugin이
없는 경우에도 앱이 부팅되고 listener가 남지 않는 no-op 경계를 제공한다.

이번 slice의 목표는 다음 네 가지를 하나의 재사용 가능한 계약으로 고정하는 것이다.

1. `@capacitor/app`의 `appUrlOpen`과 cold-start `getLaunchUrl()`을 Angular 서비스로
   연결한다.
2. tenant·route·query를 명시적인 parser에서 검증하고, malformed·unknown tenant·
   unauthorized 입력을 navigation 전에 거부한다.
3. 기존 `globalThis.__CLINIC_WORKFORCE_AUTH__` handoff와 `AuthService`의 메모리
   JWT·`allowedTenants` 계약을 재사용한다. workforce token은 새 storage 경로를
   만들지 않는다.
4. native → WebView 전달 결과를 versioned event name과 불변 typed payload로
   노출하고, Angular service lifecycle에서 plugin listener를 정리한다.

## 현재 근거

| 근거 | 확인 결과 |
| --- | --- |
| `frontend/appointment-frontend/src/app/app.ts` | 앱 셸이 `WorkforceAuthBootstrapService.restore()`를 먼저 실행한 뒤 router navigation을 관찰한다. bridge 초기화는 이 순서 뒤에 둔다. |
| `src/app/core/services/workforce-auth-bootstrap.service.ts` | `__CLINIC_WORKFORCE_AUTH__`를 소비 즉시 삭제하고 JWT를 `AuthService` 메모리에만 전달한다. |
| `src/app/core/services/auth.service.ts` | `bootstrap(token, tenantCode?)`가 JWT `allowedTenants` membership과 tenant scope를 원자적으로 검증한다. |
| `src/app/core/api/tenant-context.service.ts` | tenant scope는 기존 `sessionStorage` 계약을 사용하며 code 형식을 검증한다. workforce token 저장소는 아니다. |
| `src/app/app.routes.ts` | 지원 가능한 workforce top-level route는 `calendar`, `appointments`, `management`이다. `management`는 기존 `roleGuard`를 그대로 통과시킨다. |
| `capacitor.config.ts`, Android/iOS foundation | app identity는 `io.bluetape4k.clinic.appointment`로 고정되어 있고, native URL scheme 등록은 아직 없다. |
| Capacitor 공식 [App API](https://capacitorjs.com/docs/apis/app) | `App.addListener('appUrlOpen', ...)`와 `getLaunchUrl()`을 제공하고 listener handle의 `remove()`를 지원한다. |

## 선택한 접근

### A안 — parser + 주입 가능한 Capacitor adapter + Angular service (채택)

`native-deep-link.ts`는 부작용 없는 parser와 route command 변환을 담당한다.
`NativeWebViewBridgeService`는 `Capacitor.isNativePlatform()`과 `@capacitor/app`
plugin을 injection token으로 감싸므로 unit test가 native plugin 없이도 실제 lifecycle을
재현할 수 있다. 앱 셸은 인증 handoff 복원 뒤 `start()`를 한 번 호출한다.

URI 계약은 기존 Capacitor app id를 scheme으로 재사용한다.

```text
io.bluetape4k.clinic.appointment://open/{tenantCode}/{route}[?query]
```

- `open` host, credentials, port, fragment는 금지한다.
- `tenantCode`는 기존 tenant 형식과 제품 문서의 canonical lower-case slug에 맞춰
  `[a-z0-9][a-z0-9._-]{0,63}`만 허용한다.
- route는 `calendar`, `appointments`, `management`만 지원한다. patient portal은
  workforce JWT·patient cookie 경계가 다르므로 이번 bridge에서 직접 열지 않고
  fail-closed한다.
- query allowlist는 route별로 고정한다. `calendar`는 `view=day|week|month`와
  실제 달력 날짜 `date=YYYY-MM-DD`, `appointments`는 양의 9자리 이하 `id`,
  `management`는 `section=clinics|doctors|treatments|reschedule|equipment-unavailability|admin-dashboard`를 허용한다. 중복·빈 값·미등록 key는 거부한다.
- 허용 query는 기존 Angular route command로 변환한다. 예를 들어
  `calendar?view=week&date=2026-08-27`은 `/calendar/week/2026-08-27`,
  `appointments?id=42`는 `/appointments/42`로 이동한다.

성공한 navigation만 `clinic.native.navigation.v1` custom event와 readonly
`Observable<NativeNavigationEvent>`로 발행한다. payload는 `version: 1`, tenant,
route, 정규화된 query만 포함하며 token·raw URL·credential은 포함하지 않는다.

### B안 — native에서 route 문자열을 만들어 Angular에 전달

native Swift/Java가 Angular path를 직접 조립하면 플랫폼마다 URL decoding과
allowlist가 달라진다. route guard와 tenant authorization이 WebView와 native에
중복되어 drift 가능성이 커지므로 채택하지 않는다.

### C안 — `window.location` 또는 `postMessage`만 사용

브라우저와 native가 같은 URL을 사용할 수 있지만 plugin lifecycle·cold-start URL·
listener handle을 표현할 수 없고, raw query가 router에 바로 도달한다. `@capacitor/app`
공식 lifecycle과 기존 Angular Router를 재사용하는 A안보다 검증 경계가 약하므로
채택하지 않는다.

## 구성 요소와 데이터 흐름

1. `App` constructor가 기존 `WorkforceAuthBootstrapService.restore()`를 실행한다.
2. 이어서 `NativeWebViewBridgeService.start()`가 browser면 상태만 `browser-noop`으로
   두고 반환한다. native면 `appUrlOpen` listener를 등록한 뒤 `getLaunchUrl()`을 한 번
   확인한다.
3. plugin callback은 raw URL을 parser에 전달한다. parser가 실패하면 navigation과
   event 발행을 모두 생략하고 rejection reason을 내부 상태에 기록한다.
4. parser가 성공해도 `AuthService.isAuthenticated()`와
   `AuthService.allowedTenants()`에 tenant membership이 없으면 `unauthorized`로
   거부한다. tenant는 authorization 이후에만 `TenantContextService.setTenant()`으로
   설정한다.
5. Angular `Router.navigate()`가 `true`를 반환한 경우에만 typed event를 stream과
   `CustomEvent`로 발행한다. role guard가 거부한 navigation은 event를 발행하지
   않는다.
6. `stop()`/`ngOnDestroy()`는 저장한 plugin handle의 `remove()`를 한 번 호출한다.
   중복 `start()`는 같은 promise를 재사용하며 listener를 추가하지 않는다.

## 실패 모드와 안전 경계

| 실패 모드 | 방지/관측 동작 |
| --- | --- |
| scheme·host·path·query가 malformed | parser가 구조화된 `malformed` reason을 반환하고 router를 호출하지 않는다. raw URL이나 query를 event/log에 남기지 않는다. |
| tenant가 JWT `allowedTenants`에 없음 | tenant를 변경하지 않고 `unauthorized`를 반환한다. 기존 token은 storage로 복사하지 않는다. |
| workforce 인증이 없음 | route guard를 우회하지 않고 `unauthorized`로 거부한다. patient portal deep link를 자동 로그인으로 해석하지 않는다. |
| plugin 설치/등록 또는 `getLaunchUrl()` 실패 | 앱 부팅을 실패시키지 않고 `native-unavailable` 상태로 남긴다. browser no-op과 같은 router 기본 동작을 보존한다. |
| listener가 여러 번 시작되거나 앱이 destroy됨 | idempotent start와 handle `remove()`로 callback 중복·leak를 방지한다. |
| router navigation이 false/reject | event를 발행하지 않고 tenant scope는 이미 검증된 값만 유지한다. |

## 호환성과 native 등록

- 새 runtime dependency는 Capacitor 8 peer 범위와 맞는 `@capacitor/app@8.1.1`만
  추가한다. 기존 core/CLI/iOS/Android `8.5.0`을 교체하지 않는다.
- Android activity에는 `custom_url_scheme`와 `open` host를 위한 `VIEW`·`DEFAULT`·
  `BROWSABLE` intent filter를 추가하고, iOS `Info.plist`에는 동일 scheme의
  `CFBundleURLTypes`를 등록한다. 실제 SDK build와 device deep-link smoke는 후속
  #24가 담당한다.
- 기존 AppDelegate/SceneDelegate proxy와 Capacitor `webDir`를 재사용하며 별도
  native 인증·cookie·push notification plugin은 추가하지 않는다.
- frontend에는 Kotlin source가 없으므로 `$bluetape-kotlin-patterns`의 Kotlin
  production/test 변경은 N/A이다. 기존 backend 모듈의 `bluetape4k-assertions`를
  TypeScript에 복제하지 않고, 인증·tenant·router 계약을 현재 ecosystem API로
  재사용한다. Kotlin 회귀는 기존 `appointment-api` build로 확인한다.

## 수용 기준

1. 지원 URL이 allowlisted tenant와 route command로 이동하고 성공 event payload가
   `clinic.native.navigation.v1`/`version: 1`로 관측된다.
2. scheme·host·path·query 오류, unknown tenant, unauthenticated token은 router와
   tenant mutation 전에 거부된다.
3. workforce token은 `localStorage`·`sessionStorage`·custom native storage에
   기록되지 않고 기존 메모리 `AuthService`만 사용한다.
4. native listener와 cold-start launch URL을 한 번 처리하고 browser에서 plugin
   callback을 등록하지 않으며 destroy 후 handle을 제거한다.
5. browser fake adapter와 native-like adapter가 같은 parser/router/session 결과를
   검증한다. native SDK/device 실행 결과는 #24에 남긴다.
6. frontend unit/contract, TypeScript, production build, browser E2E와 native
   registration 정적 검사가 통과하고 `git diff --check`가 깨끗하다.

## DoD와 제외 범위

- DoD: typed parser/service/adapter, Angular bootstrap wiring, native scheme metadata,
  Korean README와 KDoc, RED/GREEN 테스트, 7-Tier review, exact-head CI와 Issue/PR
  read-back이 모두 완료된다.
- 제외: push notification, native token persistence, patient cookie/CORS 정책,
  actual Xcode/Android SDK build, emulator/device smoke. 후자는 #24의 단일 heavy
  validation lane에서 수행한다.
- 이 PR은 Epic #13의 stacked train 중간 결과이므로 merge하지 않는다. 모든 child
  issue 완료 후 한 번만 최종 merge approval을 받는다.
