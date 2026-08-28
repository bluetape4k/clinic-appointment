# PWA·Service Worker·오프라인 캐시 설계

## 목표

Epic #13의 다섯 번째 stacked slice로, 검증된 #26 frontend head 위에 Angular PWA
지원과 제한된 오프라인 경험을 추가한다. Angular CLI·Angular Service Worker·기존
Capacitor `webDir`·shared HTTP 경계를 재사용하고, 환자/직원 인증 정보와 예약 변경
mutation은 영속 캐시에서 제외한다.

## 현재 근거

- Angular 22 standalone application builder와 `src/main.ts` 기반 production build를
  사용한다.
- `angular.json`은 public assets와 hashed JS/CSS를 이미 production 산출물에 포함한다.
- `app.config.ts`는 `provideHttpClient`와 auth/XSRF/error interceptor를 한 곳에서
  조합한다.
- `TenantApiClient`는 모든 tenant API에 `API_AUTH_SCOPE`를 명시하고 patient cookie와
  workforce bearer 경계를 보존한다.
- Capacitor `8.5.0`의 `webDir`는 Angular build output을 그대로 소비하므로 native
  project나 plugin은 이 slice에서 바꾸지 않는다.
- 현재 backend read API는 tenant/auth 보호 대상이다. 따라서 인증 API 응답을
  Service Worker data group에 직접 넣지 않고, 별도 public master-data 경로만 opt-in
  cache 대상으로 선언한다.

## 선택한 접근

### A안 — Angular PWA schematic 산출물 + 명시적 public cache 경계 (채택)

`@angular/pwa`와 `@angular/service-worker`를 Angular 22.1 계열로 추가하고,
`provideServiceWorker('ngsw-worker.js', { enabled: environment.production, ... })`와
`ngsw-config.json`, manifest를 현재 CLI build에 연결한다. app shell과 hashed static
asset은 Angular asset group의 versioned cache를 사용한다. API data group은
`/api/public/master-data/**`만 허용하며, 현재 tenant/auth endpoint는 이 경로에 속하지
않으므로 인증 응답이 캐시되지 않는다.

장점은 새 cache runtime을 만들지 않고 Angular가 생성하는 immutable asset manifest와
update lifecycle을 활용한다는 점이다. `PwaStatusService`는 Angular `SwUpdate`와
브라우저 `online/offline` 이벤트를 signals로 노출하고 update apply·ngsw cache reset
동작을 제공한다. `pwaNetworkInterceptor`는 auth-scoped request에 `ngsw-bypass`를
붙이고 모든 mutation에는 `Cache-Control: no-store`를 붙인다. offline mutation은
`HttpErrorResponse`로 즉시 실패해 조용한 성공이나 queue를 만들지 않는다.

### B안 — Workbox 또는 custom Service Worker 도입

별도 cache runtime과 lifecycle을 추가하면 Angular hashed asset/update contract와
중복되고, Capacitor `webDir`와 service worker registration을 두 군데서 관리하게 된다.
새 dependency·운영 경계가 늘어나므로 채택하지 않는다.

### C안 — 인증 tenant API GET을 Service Worker data group에 직접 캐시

GET만 캐시하더라도 patient cookie 또는 workforce bearer의 응답이 사용자/tenant를
넘어 재사용될 수 있다. 현재 backend는 tenant/auth 보호 read API만 제공하므로 public
경계를 신설하지 않은 채 직접 캐시하지 않는다. 안전한 public master-data API가
추가되는 경우에만 고정된 `/api/public/master-data/**` group을 사용한다.

## 컴포넌트와 데이터 흐름

1. Angular build가 `ngsw-config.json`을 읽어 `ngsw.json`과 `ngsw-worker.js`를
   hashed app shell과 함께 `dist/appointment-frontend/browser`에 생성한다.
2. `app.config.ts`가 production에서만 service worker를 등록한다. 개발 서버와 unit
   test에서는 worker가 활성화되지 않는다.
3. `PwaStatusService`가 `SwUpdate.versionUpdates`의 `VERSION_READY`를 감지하고,
   online/offline 상태·update available·cache reset 상태를 signal로 공유한다.
4. `App` shell은 offline 또는 update available일 때만 status region을 표시한다.
   사용자는 새 버전 적용 또는 Angular `ngsw:` cache reset을 명시적으로 실행한다.
5. `pwaNetworkInterceptor`가 `API_AUTH_SCOPE != none` 또는 credentials request를
   `ngsw-bypass`로 우회한다. mutation은 online일 때도 no-store이고 offline이면
   network call 전에 status 0 오류로 종료한다.
6. public master-data GET은 인증 header/credentials 없이 호출될 때만 data group에
   들어갈 수 있으며, query는 cache key에 남겨 서로 다른 날짜/tenant를 합치지 않는다.

## 캐시 정책

| 대상                                                                    | 정책                                     | 이유                                   |
| ----------------------------------------------------------------------- | ---------------------------------------- | -------------------------------------- |
| `index.html`, hashed JS/CSS, favicon, manifest, icons                   | app-shell `prefetch`/versioned           | 앱 기동과 새 배포 전환                 |
| `/api/public/master-data/**`                                            | `freshness`, 짧은 `maxAge`, query 보존   | 향후 비민감 read-only catalog만 opt-in |
| `/api/{tenant}/auth/**`, `/appointments/**`, `/patient/**`, `/admin/**` | `ngsw-bypass`, no-store                  | cookie/JWT/개인·예약 데이터 보호       |
| POST/PUT/PATCH/DELETE 전체                                              | network-only, offline 즉시 오류          | mutation queue/background sync 제외    |
| 외부 font CDN                                                           | 기존 index link 유지, 앱 핵심 cache 아님 | 새 외부 dependency와 UI 변경 방지      |

## 오류·호환성 정책

- service worker가 비활성인 브라우저·개발 환경에서는 `PwaStatusService`가 no-op이며
  앱 shell 기능은 그대로 동작한다.
- `SwUpdate`가 제공되지 않은 unit test에서는 optional injection으로 상태 service를
  사용할 수 있다.
- `caches` API가 없거나 cache 이름이 `ngsw:`가 아니면 reset 대상에서 제외한다.
  다른 애플리케이션/third-party cache를 삭제하지 않는다.
- `activateUpdate()` 또는 cache reset 실패는 status region의 한국어 notice로
  노출하고 오류를 삼키지 않는다. 자동 reload·재시도 loop는 만들지 않는다.
- offline mutation 오류는 기존 `errorInterceptor`의 status 0 경계를 재사용하고,
  성공 response나 local queue를 생성하지 않는다.
- `navigationUrls`는 `/api/**`를 제외해 API 오류 응답이 app shell로 대체되지 않게
  한다. unknown route는 기존 Angular router fallback을 유지한다.

## 수용 기준

1. production build에 `ngsw-worker.js`, `ngsw.json`, `manifest.webmanifest`와 icon이
   포함되고 manifest의 start/display/theme metadata가 유효하다.
2. app shell asset group과 명시적인 `/api/public/master-data/**` read-only group만
   `ngsw-config.json`에 존재하며 auth/patient/appointment/admin path는 없다.
3. auth-scoped GET/HEAD와 credentials request가 `ngsw-bypass`가 되고,
   POST/PUT/PATCH/DELETE는 online에서도 `no-store`, offline에서는 next handler를
   호출하지 않고 실패한다.
4. offline/online 전환, `VERSION_READY`, update apply, `ngsw:` cache reset을
   unit/contract로 검증하고 App status region이 이를 표시한다.
5. frontend unit, bundle/manifest contract, TypeScript, production build, browser E2E,
   docs contract, diff check가 통과한다.
6. Kotlin production/test 및 `bluetape4k-assertions`는 변경하지 않는다. 이 slice의
   Kotlin pattern 직접 적용은 N/A이고, #430 backend assertion 계약은 그대로
   소비한다.

## 범위 제외와 후속

- 오프라인 예약 생성·변경·취소 queue, background sync, push notification은 제외한다.
- 현재 protected tenant API를 public으로 재분류하거나 backend endpoint를 신설하지
  않는다. 실제 public catalog가 필요하면 별도 backend Issue와 보안 검토가 필요하다.
- iOS/Android SDK build, service worker가 Capacitor native WebView에서 실행되는지,
  실기기 installability는 #27/#24에서 검증한다.
- 기존 native project, API/JWT/cookie/XSRF 계약, Angular Material UI는 바꾸지 않는다.

## 실패 모드와 완화

| 실패 모드                                       | 완화 및 검증                                                               |
| ----------------------------------------------- | -------------------------------------------------------------------------- |
| auth GET이 Service Worker data cache에 남음     | auth/credentials request에 `ngsw-bypass`, config path deny-list contract   |
| offline mutation이 성공처럼 보임                | interceptor가 status 0 오류를 먼저 반환하고 E2E에서 handler 미호출 확인    |
| stale app shell이 새 API와 섞임                 | Angular manifest version/update event와 명시적 update apply                |
| cache reset이 다른 앱 데이터를 삭제             | `ngsw:` prefix만 삭제하고 unit에서 외부 cache 보존 확인                    |
| localhost 개발에서 worker가 stale 응답을 가로챔 | `environment.production` 조건부 registration과 README HTTPS/localhost 안내 |
| API 404가 index.html로 대체됨                   | `navigationUrls`에서 `/api/**` 제외하고 build contract 검사                |

## 완료 정의

- 설계 수용 기준을 만족하는 implementation, tests, README, 7-Tier review, lesson을
  stacked branch에 기록한다.
- 최종 review에서 P0/P1은 0이고 P2/P3는 수정 또는 후속 native/public API 범위로
  명시한다.
- exact-head CI와 workflow receipt/live report를 Issue #25와 PR에 read-back한다.
- PR은 merge하지 않고 #26 exact head 위에 열어 둔 채, 다음 #27 slice가 이 head를
  base로 사용할 수 있게 handoff한다.
