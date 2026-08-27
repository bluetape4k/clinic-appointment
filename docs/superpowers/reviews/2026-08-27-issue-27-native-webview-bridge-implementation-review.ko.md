# Issue #27 네이티브 WebView typed bridge 구현 7-Tier 검토

## 검토 범위와 기준

- 대상 branch: `feat/issue-27-native-webview-bridge`
- stacked base: Issue #25 exact head `a3996b0ad66d984c324c304042fc2332d26f9e14`
- 범위: `@capacitor/app` dependency, 순수 deep-link parser, Angular typed bridge,
  workforce 인증·tenant scope 재사용, browser no-op, versioned event, Android/iOS
  scheme metadata, contract validator, Angular unit·Playwright browser 계약과 한국어 문서
- 모듈: `frontend/appointment-frontend` production/test/static metadata. Kotlin source와
  Exposed repository를 변경하지 않고 `:appointment-api:build`로 회귀만 확인한다.
- 제외: push notification, native token/cookie 저장, patient portal deep link, 실제
  Xcode/Android SDK build, simulator/emulator/device cold-start·background smoke. 이
  heavy validation은 다음 stacked slice인 Issue #24가 소유한다.
- 기준: `bluetape-workflow`, Type A `bluetape-full-feature`,
  `bluetape-kotlin-patterns`, `bluetape-writer`, 모듈별 7-Tier review.
- 병합 정책: Epic #13의 모든 child issue 완료 전에는 이 PR을 병합하지 않는다.

## 7-Tier 결과

| Tier                        |  P0 |  P1 |  P2 |  P3 | 근거와 판정                                                                                                                                                                                                                                                                                                                                     |
| --------------------------- | --: | --: | --: | --: | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Performance              |   0 |   0 |   1 |   0 | parser는 URL 길이를 2048자로 제한하고 bounded query/route만 생성한다. bridge는 polling·retry·무제한 queue 없이 Capacitor listener 하나와 cold-start URL 하나만 처리한다. native cold-start/메모리 측정은 #24에서 실제 기기로 확인한다.                                                                                                          |
| 2. Stability                |   0 |   0 |   1 |   0 | `start()` promise와 listener handle을 idempotent하게 관리하고 `stop()`/`ngOnDestroy()`에서 remove를 await한다. plugin 또는 launch URL 오류는 `native-unavailable`로 내려가며 browser는 `browser-noop`이다. OS lifecycle 재개·부분 설치 복구는 SDK/device 증거가 없어 P2로 남긴다.                                                               |
| 3. Security·개인정보        |   0 |   0 |   1 |   0 | scheme/host/credentials/port/fragment/path/query/tenant를 navigation 전에 fail-closed로 검증하고 `allowedTenants` membership 이후에만 tenant scope를 설정한다. event는 `clinic.native.navigation.v1`/`version: 1`과 정규화된 query만 포함하며 token/raw URL/storage를 포함하지 않는다. 실제 OS URL dispatch와 cookie 경계는 #24에서 재검증한다. |
| 4. Operator/Ops             |   0 |   0 |   2 |   0 | `bridge:verify`가 package/source/Android/iOS metadata drift를 fail-closed로 검사하고 `cap:sync`·`cap doctor`가 plugin을 동기화한다. 이 호스트에는 Xcode full toolchain, Android SDK/adb가 없어 signed build·install/rollback·observability는 #24 운영 증거로 분리한다.                                                                          |
| 5. Developer/API            |   0 |   0 |   0 |   0 | Angular `Router`, `AuthService`, `TenantContextService`, `signal`, `Subject`와 Capacitor 공식 `App.addListener`/`getLaunchUrl`을 재사용했다. parser는 순수 함수, adapter는 injection token, public stream은 readonly Observable이며 새 route/auth/storage abstraction을 만들지 않았다.                                                          |
| 6. User/Caller              |   0 |   0 |   1 |   0 | 허용 URL은 기존 `/calendar`, `/appointments`, `/management` route command로 이동하고 role guard를 우회하지 않는다. browser에서는 기존 workforce handoff와 tenant session이 유지되고 native event는 성공 navigation에만 발행된다. 실제 iOS/Android IME·orientation·background deep link UX는 #24에서 확인한다.                                   |
| 7. Main-session integration |   0 |   0 |   1 |   0 | #25 remote head 위에만 쌓고 root의 unrelated dirty 파일을 건드리지 않았다. spec/plan/lesson/README, workflow receipt, Issue/PR exact head와 CI read-back을 push 후 갱신한다. Epic 전체 완료 전 merge/branch deletion/local develop sync는 금지한다.                                                                                             |

### 종합 판정

- **P0 = 0, P1 = 0, P2 = 7, P3 = 0**
- P2는 실제 native SDK/device, 운영 signing/lifecycle, cold-start 성능과 final
  exact-head CI/live receipt 경계다. 구현을 차단하는 결함은 없으며 Issue #24와 Epic
  최종 gate가 책임진다.

## 모듈별 Kotlin·bluetape4k 적용 점검

| 모듈/영역                                  | 판정        | 점검                                                                                                                                                                                                                            |
| ------------------------------------------ | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `frontend/appointment-frontend` TypeScript | PASS        | Angular standalone DI/signal, readonly `Observable`, Capacitor 공식 App API, 기존 Router/Auth/Tenant/PWA/bundle contract를 재사용했다. parser와 service 경계를 분리하고 adapter double로 browser/native-like parity를 고정했다. |
| Kotlin production source                   | N/A         | 이번 slice에서 Kotlin 파일을 변경하지 않았다. `$bluetape-kotlin-patterns`의 null safety·immutability·API naming을 새 Kotlin API에 억지로 복제하지 않고, 기존 Kotlin 모듈은 회귀 build로만 확인한다.                             |
| Kotlin test source                         | N/A         | Kotlin test fixture를 추가하지 않았다. frontend에는 Vitest/Playwright의 언어-native assertion을 사용한다.                                                                                                                       |
| `bluetape4k-assertions`                    | PASS (회귀) | TypeScript에 JVM assertion dependency를 추가하지 않았다. 기존 assertion 사용 모듈의 `./gradlew :appointment-api:build`와 `koverVerify`를 통과시켜 contract regression을 확인했다.                                               |
| Native metadata                            | PASS (정적) | Android `VIEW`/`DEFAULT`/`BROWSABLE` + `@string/custom_url_scheme`/`open`, iOS `CFBundleURLTypes` + 동일 scheme을 validator와 fixture로 확인했다.                                                                               |

기존 ecosystem API를 재사용하는 것이 이 example site의 일관성과 유지보수성을 높인다.
새 native auth, cookie, push, storage 경로를 추가하지 않았고, tenant session persistence는
기존 `TenantContextService`의 범위로만 남겼다.

## 수용 기준 추적

| 기준                                                  | 증거                                                                    | 상태        |
| ----------------------------------------------------- | ----------------------------------------------------------------------- | ----------- |
| 허용 URL → tenant/route command + versioned event     | `native-deep-link.spec.ts`, `native-webview-bridge.service.spec.ts`     | PASS        |
| malformed/unknown/unauthorized를 navigation 전에 거부 | parser rejection matrix와 service auth tests                            | PASS        |
| token 비영속                                          | bridge source audit와 browser E2E storage assertion                     | PASS        |
| listener/launch lifecycle + browser no-op             | service lifecycle tests, App ordering test, Playwright browser contract | PASS        |
| browser/native 동일 parser·router/session boundary    | injection adapter service tests + workforce fixture E2E                 | PASS        |
| native registration·문서·contract drift               | `test:bridge`, `bridge:verify`, Korean README 두 파일                   | PASS (정적) |
| native SDK/device 실행                                | `xcodebuild` full Xcode/`adb`/`sdkmanager` unavailable                  | N/A → #24   |

## 검증 증거

| 영역                   | 명령/증거                                                                                               | 결과                                                                   |
| ---------------------- | ------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Dependency/API         | `npm ls @capacitor/core @capacitor/app --depth=0`; dynamic App API probe                                | core `8.5.0`, app `8.1.1`, API symbols PASS                            |
| Parser unit            | `npm test -- --watch=false --include='src/app/core/api/native-deep-link.spec.ts'`                       | 20 tests passed                                                        |
| Bridge/App unit        | `npm test -- --watch=false --include=...native-webview-bridge.service.spec.ts --include=...app.spec.ts` | 35 tests passed                                                        |
| Frontend unit          | `npm test -- --watch=false`                                                                             | 52 files, 383 tests passed                                             |
| Bridge contract        | `npm run test:bridge`                                                                                   | 3 tests passed                                                         |
| Native static contract | `npm run bridge:verify` + XML/plist assertion                                                           | `ok=true`, failures=[]                                                 |
| TypeScript             | `npx tsc --noEmit -p tsconfig.app.json`                                                                 | passed                                                                 |
| Production build       | `npm run build`                                                                                         | Angular production build passed                                        |
| Existing bundle/PWA    | `npm run test:bundle`, `npm run bundle:verify`, `npm run test:pwa`, `npm run pwa:verify`                | 4 + 3 fixture tests passed; both `ok=true`                             |
| Browser E2E            | `npx playwright test --workers=1`                                                                       | Chromium 20 tests passed; bridge contract 1 test 포함                  |
| Capacitor sync/doctor  | `npm run cap:sync`, `npx cap doctor`                                                                    | iOS/Android looking great; `@capacitor/app@8.1.1` synced               |
| Backend regression     | `./gradlew :appointment-api:build`                                                                      | `BUILD SUCCESSFUL`, `koverVerify` passed                               |
| Runtime audit          | `npm audit --omit=dev --audit-level=moderate`                                                           | 0 vulnerabilities                                                      |
| Korean docs            | `audit-korean-terms.mjs`                                                                                | 6 files, findings=0                                                    |
| Diff hygiene           | `npx prettier --write` + `git diff --check`                                                             | passed                                                                 |
| Native toolchain probe | `xcodebuild -version`, `adb version`, `sdkmanager --version`, `xcrun simctl list devices available`     | full Xcode/adb/sdkmanager unavailable; device result intentionally N/A |

## 결론

**PASS — 구현·로컬 검증 기준 P0/P1 0, PR 생성과 exact-head CI gate로 진행 가능.**
기존 bluetape4k/Angular/Capacitor 경계를 적극 재사용했고, 실제 native 실행을 browser
증거로 과장하지 않았다. 다음 단계는 branch push → PR/Issue/receipt live read-back 후
Issue #24 native SDK/device 검증이며, Epic #13 전체 child 완료 전에는 병합하지 않는다.
