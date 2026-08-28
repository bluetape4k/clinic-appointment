# Issue #23 Capacitor foundation 설계

## 문제

`frontend/appointment-frontend`는 Angular 22 standalone·signals·zoneless
SPA로 직원 화면과 환자 포털을 제공한다. Epic #13은 이를 iOS·Android
WebView에서도 재사용하도록 요구하지만, 현재 Capacitor 설정과 native
platform project가 없다. 첫 번째 slice에서는 기존 Angular 앱을 다른
frontend framework로 옮기지 않고, 빌드 산출물을 Capacitor가 소비할 수 있는
경계를 고정해야 한다.

## 기준 정보

- `frontend/appointment-frontend/package.json`은 Angular 22와 TypeScript 6을
  사용하며 Capacitor 의존성은 아직 없다.
- `angular.json`의 application builder는 production 결과를
  `dist/appointment-frontend/browser` 아래에 만들고 root `index.html`을
  그 디렉터리에 둔다.
- `app.routes.ts`는 calendar, appointments, portal, management를
  `loadChildren`으로 나눈다. foundation은 이 route 경계를 변경하지 않는다.
- 앱 셸은 모바일 하단 탭, `viewport-fit=cover`,
  `env(safe-area-inset-bottom)`을 이미 사용한다.
- API·인증은 기존 `TenantApiClient`, patient cookie/XSRF, workforce Bearer
  scope를 후속 slice에서 그대로 소비한다. #23에서는 API origin이나
  인증 저장 방식을 변경하지 않는다.

## 선택지

### 권장: 기존 Angular SPA + Capacitor thin shell

Angular production build를 Capacitor `webDir`로 복사하고, iOS·Android
project는 Capacitor가 생성한다. native 기능은 후속 이슈가 typed boundary를
통해 추가한다.

- 장점: 기존 route, API client, 인증 상태, responsive UI와 테스트를
  재사용한다. 변경 범위가 `package.json`, lockfile, Capacitor 설정과
  platform project로 제한된다.
- 단점: native origin의 API·cookie 정책은 별도 slice에서 해결해야 하며,
  iOS·Android SDK가 없는 호스트에서는 build를 완전히 검증할 수 없다.

### 대안: Ionic 컴포넌트 기반 재작성

Ionic navigation과 UI 컴포넌트로 화면을 다시 구성한다.

- 기각: 이미 Angular Material과 모바일 셸이 있고, Epic의 목표는 UI
  재작성보다 WebView 실행이다. 새 UI abstraction과 dependency를 늘린다.

### 대안: iOS·Android 별도 native 앱

각 플랫폼에서 API와 인증을 별도 구현한다.

- 기각: tenant·cookie·CSRF·route 계약이 중복되고 한 명의 개발자가
  유지할 수 있는 범위를 넘는다. 기존 Angular source 재사용이라는
  요구에도 맞지 않는다.

## 설계

### 빌드 경계

Capacitor 설정은 다음 계약을 가진다.

- `appId`: 저장소의 reverse-domain 식별자 규칙에 맞는 고정 값
- `appName`: 사용자에게 표시할 병원 예약 앱 이름
- `webDir`: `dist/appointment-frontend/browser`
- `npx cap sync`: Angular build 결과의 `index.html`, 정적 자산과 lazy
  chunk를 native project에 복사한다.

Capacitor 공식 설정도 `webDir`에 최종 `index.html`이 있어야 한다고
요구하므로, 설정값과 실제 Angular output을 build 후 계약 테스트로
검증한다.

### 의존성과 명령

`@capacitor/core`, `@capacitor/cli`, `@capacitor/ios`,
`@capacitor/android`를 같은 호환 major로 고정한다. 선택한 major의
Angular·Node 호환성은 구현 전에 공식 문서와 package metadata로 확인한다.

재현 가능한 명령은 다음 경계를 갖는다.

```text
npm run build
npx cap sync
npx cap open ios
npx cap open android
```

`npm run build`와 `npx cap sync`는 #23에서 검증한다. `open`, native build,
실기기·에뮬레이터 smoke는 #24에서 검증하며 SDK가 없는 호스트의 browser
통과를 native 통과로 간주하지 않는다.

### 보안·책임 경계

- #23은 `TenantApiClient`, patient cookie, workforce Bearer token의 동작을
  변경하지 않는다.
- API origin, CORS, credentials, CSRF, cookie 전송은 #430의 계약이다.
- native storage에 patient JWT를 복제하지 않는다.
- deep link와 native event는 #27의 typed bridge에서 다룬다.

## 실패 모드와 대응

1. `webDir`에 `index.html`이 없거나 output 구조가 바뀐다.
   - build 후 `test -f dist/appointment-frontend/browser/index.html`와
     sync 결과를 검사하고, 실패하면 Capacitor sync를 중단한다.
2. Capacitor package major가 현재 Node·Angular toolchain과 맞지 않는다.
   - package metadata와 공식 호환성 정보를 고정하고 lockfile을 함께
     갱신한다. 호환성이 확인되지 않으면 의존성 추가를 진행하지 않는다.
3. iOS·Android SDK가 없는 개발 호스트에서 native build를 시도한다.
   - #23은 project generation과 sync까지만 증명하고, native build 결과는
     전용 CI 또는 SDK가 설치된 호스트의 #24 증거로 제한한다.
4. Capacitor 초기화가 기존 Angular route 또는 asset base path를 바꾼다.
   - 기존 browser E2E와 lazy route 산출물 검증을 실행하고, route/API 변경이
     발생하면 #23 범위를 중단해 후속 issue로 되돌린다.
5. generated platform 파일이 매번 다른 설정을 만든다.
   - 생성 명령과 ignore 범위를 확인하고, 재생성 diff가 안정적일 때만
     platform project를 추적한다.

## 호환성·마이그레이션

- Angular 22와 기존 browser 실행을 유지한다.
- Node 검증은 저장소가 고정한 Node 22 toolchain을 기준으로 한다.
- 기존 `npm run start`, proxy, API path, browser E2E는 계속 동작해야 한다.
- Capacitor app은 같은 Angular bundle을 실행하며 native 기능은 plugin
  boundary 뒤에 둔다.

## 완료 조건

- [ ] 같은 Capacitor major의 core, CLI, iOS, Android package와 lockfile이
  등록된다.
- [ ] `capacitor.config.ts`가 실제 Angular browser output을 가리킨다.
- [ ] `npm run build`와 `npx cap sync`가 성공하고 lazy chunk가 복사된다.
- [ ] iOS·Android platform project 생성이 재현 가능하다.
- [ ] browser route, tenant API, patient/workforce auth 계약을 변경하지
  않는다.
- [ ] frontend unit/contract, TypeScript, browser E2E와
  `git diff --check`가 통과한다.
- [ ] README에 setup·sync 명령과 SDK 검증 경계를 한국어로 기록한다.

## DoD

- 변경 범위는 #23 foundation으로 제한하고 #430·#431·#26·#25·#27·#24의
  책임을 침범하지 않는다.
- 새 dependency는 공식 package metadata와 lockfile로 재현된다.
- build/sync와 기존 frontend 검증의 fresh evidence가 남는다.
- 7-Tier review에서 P0/P1이 없고, 후속 API/auth·native test risk가
  이슈에 연결되어 있다.
