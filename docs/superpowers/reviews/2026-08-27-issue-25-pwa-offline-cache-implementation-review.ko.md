# Issue #25 PWA·오프라인 캐시 구현 7-Tier 검토

## 검토 범위와 기준

- 대상 branch: `feat/issue-25-pwa-offline-cache`
- stacked base: #26 exact head `bd27c0c6d02e666ccc6151d1d522ca19681eeb43`
- 범위: Angular Service Worker wiring, manifest/installability metadata, 제한된
  public master-data cache, 인증·예약 mutation network-only interceptor, offline/update
  status UX, production artifact validator와 Chromium 계약
- 제외: offline mutation queue/background sync, push notification, Capacitor native
  install/update/cache lifecycle와 실제 iOS·Android device 검증(#27/#24)
- 기준: `bluetape-workflow`, `bluetape-full-feature`, `bluetape-writer`,
  `bluetape-kotlin-patterns`, 모듈별 7-Tier review
- 병합 정책: Epic #13의 모든 stacked slice가 끝날 때까지 PR을 병합하지 않는다.

## 7-Tier 결과

| Tier                        |  P0 |  P1 |  P2 |  P3 | 근거와 판정                                                                                                                                                                                                                                                                                                                                                                                                        |
| --------------------------- | --: | --: | --: | --: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1. Performance              |   0 |   0 |   1 |   0 | app shell은 해시 JS·CSS와 lazy chunk를 Angular `prefetch`로 버전 고정한다. `PwaStatusService`는 online event와 `VERSION_READY`만 구독하고 polling·retry·무제한 buffer를 만들지 않는다. 설치 byte와 native cold-start 측정은 #27 후속이라 P2 경계로 남긴다.                                                                                                                                                         |
| 2. Stability                |   0 |   0 |   1 |   0 | `ngsw-config.json`은 freshness `maxSize=20`, `maxAge=1h`, `timeout=5s`로 bounded data cache를 사용하고 `/api/**` navigation fallback을 제외한다. `resetCache()`는 `ngsw:`만 삭제하며 update 실패를 notice로 노출한다. 실제 Service Worker lifecycle·부분 배포 복구는 device/운영 검증 범위다.                                                                                                                      |
| 3. Security                 |   0 |   0 |   1 |   0 | data group은 `/api/public/master-data/**` 하나뿐이며 auth·tenant·patient·appointment·admin pattern을 포함하지 않는다. auth scope/credentials GET은 `ngsw-bypass`, mutation은 `no-store`/`no-cache`, offline mutation은 status `0` `OFFLINE_MUTATION`으로 종료한다. cross-origin preflight·origin·cookie 정책은 #24에서 검증하며 이 slice는 backend CORS source를 변경하지 않는다. |
| 4. Operator/Ops             |   0 |   0 |   1 |   0 | production에서만 `provideServiceWorker`를 활성화하고 `registerWhenStable:30000`으로 등록한다. `pwa:verify`는 `manifest.webmanifest`, `ngsw.json`, `ngsw-worker.js`, asset/data/navigation boundary를 fail-closed로 검사한다. HTTPS certificate, CDN cache header, rollback과 native storage는 이 slice 밖이다.                                                                                                     |
| 5. Developer/API            |   0 |   0 |   0 |   0 | Angular 공식 `@angular/pwa` schematic dependency와 `@angular/service-worker` runtime만 추가했다. 기존 `API_AUTH_SCOPE`, `TenantApiClient`, Angular XSRF/auth/error interceptor 순서를 재사용하고 별도 cache abstraction·endpoint·backend CORS source를 만들지 않았다.                                                                                     |
| 6. User/Caller              |   0 |   0 |   1 |   0 | `aria-live="polite"` status region이 offline·online·update available·cache reset 결과를 설명하고, offline 예약 변경을 성공처럼 표시하지 않는다. browser E2E는 manifest/installability metadata와 전환 UX를 확인하지만 실제 iOS/Android IME·WebView update는 #27/#24의 후속 조건이다.                                                                                                                               |
| 7. Main-session integration |   0 |   0 |   0 |   0 | #26 exact head 위에서만 변경했고 root의 unrelated dirty 파일을 건드리지 않았다. issue/PR/receipt에는 exact full SHA, local metrics, CI와 다음 #27 handoff를 기록하며 Epic 전체 완료 전 merge하지 않는다.                                                                                                                                                                                                           |

### 종합 판정

- **P0 = 0, P1 = 0, P2 = 5, P3 = 0**
- P2는 app-shell/native cold-start, Service Worker lifecycle 복구, 운영 HTTPS/CDN,
  cross-origin cookie/device 검증과 browser-to-native 경계다. 구현을 차단하는 결함은
  없으며 #24/#27의 책임으로 명시했다.

## Kotlin·bluetape4k 적용 점검

이번 slice는 TypeScript/SCSS/JSON 중심이며 backend Kotlin 설정이나 API CORS source를
변경하지 않았다. cross-origin PWA header와 cookie 정책은 #24에서 별도 검증한다.

| 점검                                                | 결과 | 근거                                                                                                                     |
| --------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------ |
| `bluetape-kotlin-patterns` null safety·immutability | N/A | 이번 commit은 Kotlin production source를 변경하지 않는다. 기존 API/auth 계약과 불변 경계를 보존했다. |
| Kotlin naming·API boundary                          | N/A | backend CORS source와 HTTP contract는 변경하지 않고 frontend interceptor가 기존 API 요청 경계만 재사용한다.     |
| Kotlin test pattern                                 | N/A | Kotlin test source를 변경하지 않았다. 기존 JUnit 5와 `bluetape4k-assertions` 사용 영역은 appointment-api build로 회귀 확인했다.                    |
| Frontend pattern                                    | PASS | Angular standalone signal·functional interceptor·`takeUntilDestroyed`와 기존 `API_AUTH_SCOPE`/Material shell을 사용했다. |
| dependency reuse                                    | PASS | 공식 Angular PWA 패키지 외 새 dependency·native plugin·backend public endpoint를 추가하지 않았다.                        |

`bluetape4k-assertions`를 frontend에 억지로 복사하지 않고, 이번 slice에서는 Kotlin
production/test source를 변경하지 않았다. PWA cache는 Angular Service Worker가
소유하고 앱은 status/interceptor로 인증 경계를 명시한다. cross-origin API의 CORS
header·cookie 계약은 backend 변경이 필요한 별도 #24 범위로 남긴다.

## 검증 증거

| 영역             | 명령/증거                                                                                                                                    | 결과                                                                      |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| RED              | `npm test -- --watch=false --include=...pwa-status... --include=...pwa-network...`                                                           | 구현 전 symbol/dependency 누락으로 의도된 실패                            |
| PWA fixture      | `npm run test:pwa`                                                                                                                           | 3 tests passed                                                            |
| Bundle fixture   | `npm run test:bundle`                                                                                                                        | 4 tests passed                                                            |
| Frontend unit    | `npm test -- --watch=false`                                                                                                                  | 50 files, 353 tests passed                                                |
| Production build | `npm run build`                                                                                                                              | Angular production build passed; `dist/appointment-frontend/browser` 생성 |
| Bundle contract  | `npm run bundle:verify`                                                                                                                      | `ok=true`, `initialBytes=633018`, 4 lazy routes, `failures=[]`            |
| PWA artifact     | `npm run pwa:verify`                                                                                                                         | `ok=true`, `shellAssets=47`, `dataGroups=1`, `failures=[]`                |
| TypeScript       | `npx tsc --noEmit -p tsconfig.app.json`                                                                                                      | passed                                                                    |
| Browser E2E      | `npm run test:e2e`                                                                                                                           | Chromium 20 tests passed                                                  |
| Module build     | `./gradlew :appointment-api:build --no-daemon --max-workers=1 --console=plain`                                                              | 906 tests, 3 skipped; `BUILD SUCCESSFUL`                                  |
| Exact-head CI    | `CI run 33066457771`, `Frontend CI run 33066459862`                                                                                         | head `cf13e50b5c0f28b871557ed9a26abae98a5a0f8d` exact match; all applicable jobs passed |
| Docs contract    | `npm run docs:verify`                                                                                                                        | `ok=true`, `documentsChecked=10`, `sourceChecks=8`, `failures=[]`          |
| Korean artifact  | `audit-korean-terms.mjs`                                                                                                                     | 6 files, `findings=0`                                                     |
| Formatting/diff  | `npx prettier --check ...`; `git diff --check`                                                                                                | all changed frontend/docs files formatted; passed                         |

`npm install`이 보고한 개발 도구 vulnerability 3건은 자동 수정하지 않았다. native
SDK/device build, production HTTPS, 실제 Service Worker persistence는 이번 PASS에
포함하지 않으며 #24/#27에서 다시 검증한다.

## 결론

**PASS — PR 생성·exact-head CI·live read-back 완료, merge-ready.** Angular 공식 PWA
산출물과 기존 API/auth/cache 경계를 재사용했고 P0/P1은 없다. PR #436은 receipt
sequence 19의 immutable live report와 함께 기록했으며, Epic #13 전체 완료 전에는
병합하지 않는다.
