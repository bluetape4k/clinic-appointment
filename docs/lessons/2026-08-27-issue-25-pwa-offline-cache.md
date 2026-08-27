# Issue #25 PWA·오프라인 캐시 lesson

## 재사용 원칙

이번 slice는 별도 offline 데이터 계층을 새로 만드는 일이 아니라, Angular 공식
Service Worker와 기존 API/auth 경계를 연결하는 일이었다.

- `@angular/pwa` schematic이 정하는 `ngsw-config.json`·`ngsw-worker.js` 산출물과
  Angular application builder를 재사용했다. Workbox나 자체 service worker를 추가하지
  않았다.
- 기존 `API_AUTH_SCOPE`, `TenantApiClient`, patient HttpOnly cookie/XSRF와 workforce
  Bearer 흐름을 `pwaNetworkInterceptor`가 소비한다. URL을 추측해 인증을 판별하지 않고
  caller context와 `withCredentials`를 기준으로 `ngsw-bypass`를 붙인다.
- app root의 기존 standalone shell·Material button·#26 `MobileViewportDirective`를
  그대로 사용하고, PWA 상태는 작은 `role="status"` region으로만 추가했다. 페이지마다
  online/cache 상태를 복제하지 않는다.
- backend CORS는 기존 `ApiCorsProperties`/`ApiCorsConfiguration`을 확장해 PWA가 실제
  cross-origin preflight를 통과하도록 했다. 새 endpoint나 별도 security filter는 만들지
  않았고, 테스트는 기존 `bluetape4k-assertions`를 유지했다.

## 고정한 계약

1. app shell은 해시 JS·CSS, `index.html`, manifest, favicon/icon을 prefetch한다.
2. data group은 `/api/public/master-data/**`만 freshness 1시간·최대 20개로 허용한다.
   현재 backend에는 인증 없는 master-data endpoint가 없으므로 실제 tenant/auth 응답은
   cache 대상이 아니다.
3. auth scope 또는 credentials가 있는 GET/HEAD는 `ngsw-bypass: true`로 우회한다.
   POST/PUT/PATCH/DELETE는 `Cache-Control: no-store`와 `Pragma: no-cache`를 사용한다.
4. offline mutation은 queue·background sync·fake success 없이 status `0`
   `OFFLINE_MUTATION`으로 종료한다. online 복귀 뒤 기존 API 흐름이 다시 요청을 소유한다.
5. cache reset은 `caches.keys()` 중 `ngsw:` prefix만 삭제한다. 다른 애플리케이션 cache와
   native storage는 침범하지 않는다.
6. update activation과 full-page reload는 production에서만 실행한다. development/unit
   환경에서는 `SwUpdate`를 optional로 주입해 app test가 worker 설치에 의존하지 않는다.

## 검증 결과

- PWA fixture 3 tests, 기존 bundle fixture 4 tests, frontend unit 50 files/353 tests가
  통과했다.
- production build와 bundle validator가 `initialBytes=633018`와 4개 lazy route를
  확인했고, PWA validator가 shell 47개·data group 1개·forbidden path 0개를 확인했다.
- TypeScript compile과 Chromium E2E 20건(새 manifest/offline 2건 포함)이 통과했다.
- `appointment-api` CORS properties/source targeted test 6건은
  `BUILD SUCCESSFUL`이며 `bluetape4k.assertions` assertion을 사용한다.

## 놓치기 쉬운 경계와 다음 방어선

- Angular dev server는 Service Worker를 production처럼 등록하지 않으므로 browser E2E의
  offline 전환은 status UX와 network interceptor 계약을 검증하는 evidence다. 실제
  install/update/persistence를 PASS라고 부르지 말고 #27 native lane에서 확인한다.
- `ngsw-bypass`, `Cache-Control`, `Pragma`는 cross-origin preflight에 포함되므로 API
  allowed headers를 함께 갱신해야 한다. frontend만 변경하면 native/WebView에서 login이나
  예약 mutation이 preflight 단계에서 실패할 수 있다.
- public master-data endpoint가 생기더라도 먼저 개인정보·tenant scope를 검토하고,
  `ngsw-config.json` data group과 static validator fixture를 같이 갱신한다. 예약 생성·변경·
  취소 응답을 offline cache에 넣거나 queue로 승격하지 않는다.
- `npm audit fix --force`는 Angular major/version contract를 바꿀 수 있으므로 이번
  작업에서 실행하지 않았다. dependency upgrade는 별도 issue와 lockfile evidence로
  다룬다.

## 결과

공통 Angular/Capacitor/API 경계를 재사용해 PWA installability와 안전한 offline 읽기
경험을 추가했으며, 인증·예약 mutation의 일관성을 cache 편의보다 우선했다. 다음 stacked
slice는 #27 native WebView 검증이고, #25 PR은 Epic #13 전체 완료 전 merge하지 않는다.
