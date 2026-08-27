# appointment-frontend

[한국어 본문](README.md) | [한국어 참고본](README.ko.md)

Angular 22 기반 병원 예약 관리 웹 UI입니다. 직원 화면과 환자 포털(`/portal`)을
같은 standalone workspace에서 route로 분리합니다.

환자 포털의 `/portal/login`·`/portal/register`는 tenant code를 입력받고,
`TenantContextService`가 같은 탭의 `sessionStorage`에 scope를 보관합니다.
`PatientAuthService`와 `PortalApiClient`는 이 scope를 `/api/{tenantCode}/...` 경로에
반영하며, 인증된 포털 내부 route는 `patientAuthGuard`로 보호합니다. 이 범위의
tenant routing은 구현되어 있습니다.

직원·관리자 화면의 legacy JWT `AuthService`와 일부 서비스는 아직 `/api/...` 경로를
직접 호출하므로 tenant-aware 직원 routing/auth는 미완료입니다. 이 잔여 범위는
[Issue #295](https://github.com/bluetape4k/clinic-appointment/issues/295)에서
추적하며, 이 예제는 두 사용자 영역의 완료 상태를 구분해 설명합니다.

## 개발 서버 실행

```bash
cd frontend/appointment-frontend
npm install
npm start   # http://localhost:4200
```

API 서버(`http://localhost:8080`)가 먼저 실행되어 있어야 합니다.

## API origin·인증 전송 계약

- 브라우저 개발 환경은 `environment.apiOrigin=''`과 `/api` proxy를 사용합니다.
- 같은 origin으로 배포하는 production browser도 `apiOrigin`을 비워 둡니다. API가
  다른 호스트라면 `environment.prod.ts`에 `https://` origin을 지정합니다.
- Capacitor WebView는 비어 있지 않은 HTTPS origin이 필요합니다. 여러 native 환경에서
  같은 bundle을 재사용해야 하면 앱이 시작되기 전에 제한된 runtime 설정을 주입합니다.

  ```ts
  globalThis.__CLINIC_API_CONFIG__ = { apiOrigin: 'https://api.example.test' };
  ```

  runtime 설정도 credentials, path, query, fragment, wildcard를 포함할 수 없으며,
  `TenantApiClient`가 `/api/{tenantCode}/...` 경로를 구성합니다.

- API를 cross-origin으로 열 때만 다음처럼 명시적인 origin과 credentials를 함께
  설정합니다. `*` origin은 patient cookie와 함께 사용할 수 없습니다.

  ```yaml
  scheduling:
    security:
      cors:
        enabled: true
        allowed-origins: [https://app.example.test]
        allow-credentials: true
  ```

  설정은 `/api/**`에만 적용되며, HTTPS가 기본입니다. `http://localhost`와
  `http://127.0.0.1`은 개발 진단에만 허용합니다.

- patient 인증은 HttpOnly cookie와 Angular `HttpXsrfTokenExtractor`를 재사용하고
  unsafe 요청에 `X-XSRF-TOKEN`을 보냅니다. patient JWT를 `localStorage`나
  `sessionStorage`에 저장하지 않으며, workforce Bearer token은 기존 메모리 상태만
  사용하고 cookie를 보내지 않습니다.
- cross-origin API를 사용할 때는 CSRF bootstrap이 앱 origin에서 읽을 수 있는
  `XSRF-TOKEN` cookie를 발급해야 합니다. API host에만 있는 host-only cookie는 앱
  WebView가 읽을 수 없으므로, same-site/reverse proxy 배포 조건을 먼저 고정하고
  token을 storage로 복사하지 않습니다.
- patient cookie가 `SameSite=Strict`인 상태에서 native 앱과 API가 cross-site이면
  cookie 동작을 이 문서나 browser E2E의 성공으로 간주하지 않습니다. 실제 기기 정책은
  [Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24), native
  bridge가 필요할 때의 경계는 [Issue #27](https://github.com/bluetape4k/clinic-appointment/issues/27)에서
  검증합니다.

## 사용자 흐름

![환자 예약 시나리오 시퀀스](../../docs/requirements/assets/user-scenarios-01-patient-booking-ko.png)

![장비 사용 불가 시나리오 시퀀스](../../docs/requirements/assets/user-scenarios-04-equipment-unavailability-ko.png)

## 빌드

```bash
# Angular CLI 직접
npm run build   # dist/ 생성
npm run bundle:verify   # Capacitor webDir의 index·lazy chunk·budget 계약 검증

# Gradle 통합 빌드
./gradlew :frontend:appointment-frontend:build
```

`npm run bundle:verify`는 Angular가 생성한
`dist/appointment-frontend/browser`를 Capacitor `webDir` 산출물로 간주하고,
`index.html`의 local script/modulepreload/stylesheet 참조, 초기 bundle byte와
production `initial`·`anyComponentStyle` budget, `calendar`·`appointments`·
`portal`·`management` lazy route marker를 확인합니다. 해시 파일명 자체를
고정하지 않으므로 Angular 재빌드 후에도 재사용할 수 있습니다.

## Capacitor WebView

Angular production bundle을 만든 뒤 Capacitor native project에 정적 자산을
동기화합니다.

Capacitor 8 CLI는 Node.js 22 이상이 필요합니다. 이 저장소의 frontend
toolchain은 Node.js `22.22.3`과 npm `11.12.0`을 기준으로 하며, `package.json`의
`engines.node`가 동일한 최소 버전을 선언합니다.

```bash
npm run cap:sync
```

생성된 project를 열려면 iOS에는 Xcode, Android에는 Android Studio와 Android SDK가
필요합니다.

```bash
npm run cap:open:ios
npm run cap:open:android
```

`cap:sync`는 `dist/appointment-frontend/browser`를 Capacitor `webDir`로 사용합니다.
API origin·cookie·CSRF 전송 계약은 [Issue #430](https://github.com/bluetape4k/clinic-appointment/issues/430),
실제 디바이스·에뮬레이터 검증은 [Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24)에서
다룹니다. 브라우저 E2E 통과만으로 native build나 실기기 동작을 보장하지 않습니다.

## 네이티브 deep link·WebView bridge

앱 셸은 기존 `WorkforceAuthBootstrapService`가 메모리 JWT handoff를 복원한 뒤
`NativeWebViewBridgeService`를 시작합니다. 브라우저에서는 `@capacitor/app` listener를
등록하지 않는 no-op이며, native에서는 cold start의 `getLaunchUrl()`과 실행 중
`appUrlOpen`을 같은 검증 경계로 처리합니다.

지원 URL 형식은 다음과 같습니다.

```text
io.bluetape4k.clinic.appointment://open/{tenantCode}/{route}[?query]
```

- `tenantCode`는 lower-case tenant slug이며 JWT의 `AuthService.allowedTenants`에
  포함된 경우에만 scope를 변경합니다.
- `calendar`는 `view=day|week|month`와 `date=YYYY-MM-DD`, `appointments`는
  양의 예약 `id`, `management`는 등록된 `section`만 허용합니다. portal, unknown
  query, 중복·빈 값, credentials·port·fragment는 fail-closed합니다.
- 성공한 navigation만 `clinic.native.navigation.v1`/`version: 1` typed event를
  발행하며 payload에 raw URL이나 token을 포함하지 않습니다. workforce token은
  기존 메모리 상태만 재사용하고 storage에 복사하지 않습니다.
- Android의 `VIEW`·`DEFAULT`·`BROWSABLE` intent filter와 iOS의
  `CFBundleURLTypes`가 동일한 scheme과 `open` host를 등록합니다. 검증 명령은 다음과
  같습니다.

  ```bash
  npm run test:bridge
  npm run bridge:verify
  ```

실제 Xcode/Android SDK 빌드, cold-start·background deep link, IME와 cookie 정책은
[Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24)에서 실제
디바이스·에뮬레이터로 검증합니다.

## 모바일 viewport·입력 계약

기존 Angular standalone shell, Angular Material 컴포넌트, Capacitor `webDir`를
그대로 재사용합니다. `appMobileViewport` directive가 `visualViewport` 높이와
키보드 inset을 `--mobile-viewport-height`·`--mobile-keyboard-inset` CSS 변수로
공유하고, Safe Area는 `env(safe-area-inset-*)`로 각 scroll 경계에 반영합니다.
따라서 페이지별 viewport 계산이나 새 keyboard plugin을 추가하지 않습니다.

```bash
npm test -- --watch=false
npm run build && npm run bundle:verify
npx tsc --noEmit -p tsconfig.app.json
npx playwright test e2e/patient-portal.spec.ts e2e/mobile-lazy-routes.spec.ts
```

브라우저 계약은 320·375·393·430px portrait와 짧은 landscape에서 lazy route,
가로 overflow, focus 입력, form action, 44px 이상 touch target을 확인합니다.
브라우저가 통과해도 iOS/Android IME·orientation·status bar와 native bridge는
검증된 것으로 보지 않으며, 실기기 계약은 [Issue #27](https://github.com/bluetape4k/clinic-appointment/issues/27),
배포·WebView 보안 경계는 [Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24)에서
별도로 다룹니다.

## iOS·Android WebView 통합 검증

실제 Capacitor WebView 검증은 브라우저 profile과 분리된
`.github/workflows/native-webview-ci.yml`에서 수행합니다. workflow는 입력한 `ref`를
checkout한 뒤 `expected_sha`와 실제 `git rev-parse HEAD`가 같은지 확인하고, Android
emulator와 iOS simulator에서 `cap:sync`·build·install·launch·custom URL deep link를
실행합니다. 어느 단계든 실패하면 성공으로 정규화하지 않고 platform report와 함께 job을
실패시킵니다.

로컬에서는 먼저 호스트 capability를 확인합니다.

```bash
npm run native:environment
npm run test:native:environment
```

브라우저 회귀는 WebKit iPhone과 Chromium Pixel profile에서 별도로 실행합니다.

```bash
npx playwright install chromium webkit
npx playwright test e2e/mobile-webview-contract.spec.ts \
  --project=mobile-ios --project=mobile-android --workers=1
npm run native:workflow
```

exact commit을 hosted runner에서 실행하려면 push된 SHA를 두 입력에 함께 전달합니다.

```bash
HEAD_SHA="$(git rev-parse HEAD)"
gh workflow run native-webview-ci.yml \
  --ref feat/issue-24-native-webview-validation \
  -f ref="$HEAD_SHA" -f expected_sha="$HEAD_SHA"
```

각 native job은 다음 필드만 포함하는 `native-webview-report.json`을 artifact로
업로드합니다.

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-08-27T00:00:00.000Z",
  "platform": "android|ios",
  "commit": "<40-character-lowercase-sha1>",
  "toolchain": { "runner": "..." },
  "commands": ["cap:sync", "build", "launch", "deep-link"],
  "result": "passed|failed"
}
```

`native:environment`가 `targets.ios` 또는 `targets.android`를 `false`로 반환하면
로컬 결과는 native PASS가 아니라 PENDING입니다. 같은 이유로 Playwright browser
profile 성공만으로 Issue #24 또는 Epic #13을 닫지 않습니다. native artifact와 exact
head가 모두 확인된 뒤에만 해당 완료 조건을 체크합니다.

## PWA·오프라인 캐시 계약

`@angular/pwa`와 `@angular/service-worker`를 Angular 공식 Service Worker 구성으로
사용합니다. production build에서만 `ngsw-worker.js`를 등록하며, HTTPS 운영 환경과
`localhost` 개발 예외를 전제로 합니다. Service Worker 설정은
[Angular Service Worker 시작 가이드](https://angular.dev/ecosystem/service-workers/getting-started)와
[ngsw-config 참조](https://angular.dev/ecosystem/service-workers/config)의
`assetGroups`·`dataGroups` 계약을 따릅니다.

- 해시가 붙은 JavaScript·CSS, `index.html`, manifest와 아이콘은 versioned app shell로
  prefetch합니다.
- 현재 백엔드에는 인증 없는 master-data API가 없으므로 `/api/public/master-data/**`만
  향후 재사용 가능한 read-only 경계로 등록하고, freshness 1시간·최대 20개로 제한합니다.
  tenant/auth, patient, appointment, admin 응답은 data group에 추가하지 않습니다.
- 인증 scope가 있는 GET/HEAD나 `withCredentials` 요청에는 `ngsw-bypass: true`를
  붙여 cookie·Bearer 응답이 Service Worker에 들어가지 않게 합니다. POST·PUT·PATCH·DELETE는
  `Cache-Control: no-store`와 `Pragma: no-cache`를 사용하며, offline이면 status `0`의
  `OFFLINE_MUTATION` 오류를 반환합니다. 예약 mutation queue나 background sync는 제공하지
  않습니다.
- 앱 셸의 status region은 offline·online 전환과 update available을 `aria-live="polite"`로
  알립니다. update 적용과 `ngsw:` cache reset은 사용자가 명시적으로 실행하며 다른
  application cache는 삭제하지 않습니다.

```bash
npm run pwa:verify   # production ngsw.json·manifest·인증 캐시 경계 검증
npm run test:pwa     # PWA validator fixture 계약 테스트
```

manifest·offline 전환은 Chromium E2E로 확인하지만, 실제 Service Worker lifecycle과
Capacitor WebView의 native cache 저장소는 이 모듈의 브라우저 검증에 포함하지 않습니다.
실기기 설치·업데이트·offline 동작은 [Issue #27](https://github.com/bluetape4k/clinic-appointment/issues/27),
운영 origin·cookie 보안은 [Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24)에서
후속 검증합니다.

## 테스트

```bash
npm test -- --watch=false   # Vitest 기반 Angular 단위·계약 테스트
npm run test:bundle         # WebView bundle fixture 계약 테스트
npm run test:pwa             # PWA cache/installability fixture 계약 테스트
npm run test:e2e             # Playwright Chromium 브라우저 시나리오
```

`npm run test:e2e`에는 320·375·393·430px viewport에서 calendar와
appointments/portal lazy navigation 및 가로 overflow를 확인하는 WebView smoke가
포함됩니다. 이 검증은 브라우저 산출물 범위이며, Xcode·Android SDK와 실제
WebView/디바이스 검증은 [Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24)의
범위입니다.

환자 포털의 취소 흐름은 등록된 사유 code만 전송합니다. 환자 화면에는
한국어 label이 표시되고, 요청에는 최신 ETag와 `Idempotency-Key`가 포함됩니다.
취소 후에는 `CANCELLED` terminal step을 표시하며 관리자·직원용 상세 사유는
포털 응답에 노출하지 않습니다.

브라우저 테스트를 처음 실행하는 환경에서는
`npx playwright install chromium`으로 Chromium을 준비하세요.

## 설계 문서

- [프론트엔드 설계](../../docs/requirements/frontend.md)
