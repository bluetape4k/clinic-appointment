# Issue #27 네이티브 ↔ WebView typed bridge 구현 계획

> **For agentic workers:** 이 계획은 Epic #13 stacked PR train의 #27 slice다. PR은
> `feat/issue-25-pwa-offline-cache`를 base로 만들고 Epic 전체 완료 전에는 병합하지
> 않는다. 각 단계는 checkbox와 fresh evidence로 진행한다.

**목표:** `@capacitor/app` URL lifecycle을 기존 Angular route·tenant·workforce session
계약에 연결하고, browser no-op·typed event·fail-closed deep-link 검증을 native
scheme 등록과 함께 제공한다.

**구조:** 순수 `native-deep-link.ts`가 URI parser와 route command를 담당하고,
`NativeWebViewBridgeService`가 주입 가능한 Capacitor platform/plugin adapter,
`AuthService`, `TenantContextService`, `Router`를 조합한다. `App`은 기존
`WorkforceAuthBootstrapService.restore()` 뒤에 bridge를 idempotent하게 시작한다.

**기술:** Angular 22 standalone, TypeScript 6, Capacitor core/CLI/iOS/Android
8.5.0, `@capacitor/app` 8.1.1, RxJS, Vitest, Playwright, iOS Info.plist,
Android Manifest.

## 파일 책임과 write scope

- Modify: `frontend/appointment-frontend/package.json` — `@capacitor/app@8.1.1`
  runtime dependency와 bridge 검증 script가 필요할 때만 추가한다.
- Modify: `frontend/appointment-frontend/package-lock.json` — npm resolution.
- Create: `frontend/appointment-frontend/src/app/core/api/native-deep-link.ts` —
  scheme/host/tenant/route/query parser와 typed route command.
- Create: `frontend/appointment-frontend/src/app/core/api/native-deep-link.spec.ts` —
  순수 parser RED/GREEN contract.
- Create: `frontend/appointment-frontend/src/app/core/services/native-webview-bridge.service.ts` —
  Capacitor adapter, auth authorization, router navigation, event stream, lifecycle.
- Create: `frontend/appointment-frontend/src/app/core/services/native-webview-bridge.service.spec.ts` —
  browser/native-like adapter, launch URL, rejection, cleanup, no-token-storage tests.
- Modify: `frontend/appointment-frontend/src/app/core/api/index.ts` — public bridge
  parser/token export.
- Modify: `frontend/appointment-frontend/src/app/core/services/index.ts` — service export.
- Modify: `frontend/appointment-frontend/src/app/app.ts` — auth restore 후 bridge start.
- Modify: `frontend/appointment-frontend/src/app/app.spec.ts` — bootstrap ordering과
  browser no-op 검증.
- Modify: `frontend/appointment-frontend/e2e/workforce-auth.spec.ts` 또는 새
  `e2e/native-webview-bridge.spec.ts` — browser no-op과 동일 route/session contract.
- Modify: `frontend/appointment-frontend/android/app/src/main/AndroidManifest.xml` —
  `VIEW`/`DEFAULT`/`BROWSABLE` custom-scheme intent filter.
- Modify: `frontend/appointment-frontend/ios/App/App/Info.plist` — 동일 scheme
  `CFBundleURLTypes`.
- Modify: `frontend/appointment-frontend/README.md`, `README.ko.md` — Korean-only
  deep-link syntax, host handoff, browser/native boundary와 #24 handoff.
- Create: `docs/superpowers/specs/2026-08-27-issue-27-native-webview-bridge-design.md` —
  approved design.
- Create: `docs/superpowers/plans/2026-08-27-issue-27-native-webview-bridge-plan.md` —
  this plan.
- Create: `docs/superpowers/reviews/2026-08-27-issue-27-native-webview-bridge-implementation-review.ko.md` —
  final 7-Tier review and P0/P1 convergence.
- Create: `docs/lessons/2026-08-27-issue-27-native-webview-bridge.md` — reusable
  bridge/deep-link/lifecycle lesson.

## 계약 traceability

| 수용 기준 | 구현 task | fresh proof |
| --- | --- | --- |
| 지원 URL이 tenant·route command와 versioned event로 이동 | 2, 3 | parser/service unit, app integration, E2E |
| malformed·unknown tenant·unauthorized fail-closed | 2, 3 | parser matrix와 auth/router spy |
| workforce token 비영속 | 3 | storage spy/source audit와 AuthService contract |
| native listener/launch lifecycle 및 browser no-op | 3, 4 | fake plugin handle, `stop()`, App test |
| browser/native 동일 router/session 결과 | 3, 4 | adapter parity contract와 browser E2E |
| build/unit/TS/E2E/native static/diff | 5, 6, 7 | exact command 결과와 CI |

## 위험·롤백

| 위험 | 조기 signal | 완화·rollback | 재실행 지점 |
| --- | --- | --- | --- |
| `@capacitor/app` peer mismatch | `npm ls` invalid 또는 `cap sync` plugin error | 8.1.1과 core 8.5.0 조합만 유지하고 package/lock을 직전 commit으로 되돌린다 | Task 1 전체 |
| URL parser가 encoded path/query를 우회 | `%2F`, duplicate key, fragment 테스트 실패 | parser에서 decode 후 delimiter/control/allowlist 검사, navigation 전에 거부 | Task 2 parser suite |
| 인증 전에 tenant가 바뀜 | unauthorized test에서 `setTenant` 호출 | membership 검사를 먼저 수행하고 실패 시 token/session을 변경하지 않는다 | Task 3 service suite |
| app bootstrap race | launch URL이 기본 `/calendar`로 덮임 | App constructor에서 restore → start 순서를 고정하고 launch URL service test를 둔다 | Task 3 + App spec |
| listener leak/duplicate callback | `addListener` 호출 횟수 증가 또는 remove 미호출 | idempotent start promise와 단일 handle, async stop을 사용한다 | Task 3 lifecycle tests |
| native metadata가 실제 scheme과 drift | XML/plist static assertion 불일치 | parser 상수와 native metadata fixture를 같은 exact token으로 검증한다 | Task 4 static tests |
| native SDK 미설치 | `xcodebuild`, `adb`, `sdkmanager`, `simctl` missing | browser/adapter proof를 native build로 과장하지 않고 #24 heavy lane으로 넘긴다 | Task 5 native probe |

## Task 1 — dependency와 baseline을 고정한다

**Files:** `frontend/appointment-frontend/package.json`, `package-lock.json`.

- [ ] **Step 1: 현재 기준과 plugin metadata를 기록한다**

Run:

```bash
cd frontend/appointment-frontend
npm ls @capacitor/core @capacitor/app --depth=0 || true
npm view @capacitor/app@8.1.1 peerDependencies engines --json
git status --short
```

Expected: 현재 `@capacitor/app`가 없고 `@capacitor/core`는 8.5.0이며, plugin peer가
`@capacitor/core >=8.0.0`이다. root의 unrelated dirty files는 수정하지 않는다.

- [ ] **Step 2: RED — plugin symbol을 확인한다**

```bash
node -e "import('@capacitor/app').then(({App}) => { if (!App.addListener || !App.getLaunchUrl) process.exit(1) })"
```

Expected: dependency가 없어서 module resolution이 실패한다. 이것은 구현 전 RED이며
테스트 실패와 혼동하지 않는다.

- [ ] **Step 3: exact dependency를 추가한다**

```bash
npm install --save-exact @capacitor/app@8.1.1
```

Expected: runtime dependency와 lockfile만 갱신되고 core/CLI/iOS/Android 8.5.0은
교체되지 않는다.

- [ ] **Step 4: GREEN — dependency와 plugin API를 확인한다**

```bash
npm ls @capacitor/core @capacitor/app --depth=0
node -e "import('@capacitor/app').then(({App}) => { if (!App.addListener || !App.getLaunchUrl) process.exit(1); console.log('capacitor app API ok') })"
```

Expected: core 8.5.0, app 8.1.1, API 확인 성공.

## Task 2 — 순수 parser를 TDD로 만든다

**Files:** `src/app/core/api/native-deep-link.ts`, `native-deep-link.spec.ts`.

- [ ] **Step 1: parser RED 테스트를 작성한다**

테스트는 다음을 직접 호출한다.

```ts
parseNativeDeepLink('io.bluetape4k.clinic.appointment://open/clinic-a/calendar?view=week&date=2026-08-27')
parseNativeDeepLink('io.bluetape4k.clinic.appointment://open/clinic-a/appointments?id=42')
parseNativeDeepLink('io.bluetape4k.clinic.appointment://open/clinic-a/management?section=doctors')
```

성공 결과는 `route`, `tenantCode`, `query`, `routerCommands`를 확인하고, 다음 입력은
`ok: false`와 구조화된 reason을 확인한다: 다른 scheme, host, credential, port,
fragment, uppercase/empty tenant, portal/unknown route, malformed date, duplicate
query, unknown query, empty value, encoded slash, id `0` 또는 10자리.

- [ ] **Step 2: RED 실행을 관찰한다**

```bash
npm test -- --watch=false --include='src/app/core/api/native-deep-link.spec.ts'
```

Expected: module/function이 없어 의도한 symbol failure가 발생한다.

- [ ] **Step 3: 최소 parser를 구현한다**

`URL` 생성과 raw length bound를 사용하고 credentials/port/hash/host를 먼저 검사한다.
pathname segment를 decode한 뒤 tenant regex와 route allowlist를 적용한다. `URLSearchParams`
각 key의 `getAll()` 길이가 1인지 확인하고 route별 query allowlist·날짜 실제 유효성·id
범위를 검사한다. 성공 시 query를 `Object.freeze`한 readonly record와 absolute router
commands를 반환하며 raw URL은 결과에 넣지 않는다.

- [ ] **Step 4: GREEN과 parser matrix를 실행한다**

```bash
npm test -- --watch=false --include='src/app/core/api/native-deep-link.spec.ts'
```

Expected: supported 3개와 rejection matrix 전체 PASS.

## Task 3 — typed bridge service와 App bootstrap을 TDD로 연결한다

**Files:** `native-webview-bridge.service.ts`, its spec, `app.ts`, `app.spec.ts`,
`core/api/index.ts`, `core/services/index.ts`.

- [ ] **Step 1: service RED 테스트를 작성한다**

주입 token double로 `platform.isNativePlatform`, plugin `addListener`/`getLaunchUrl`,
plugin handle `remove`, Router `navigate`, AuthService signals/methods,
TenantContextService를 구성한다. 다음을 각각 검증한다.

1. browser: `start()`가 plugin을 호출하지 않고 `browser-noop` 상태를 만든다.
2. native launch: `getLaunchUrl()`과 appUrlOpen callback이 같은 parser/router/session
   path를 사용하고 valid URL은 tenant 설정 후 navigation/event를 한 번 수행한다.
3. invalid/unknown/unauthorized: router와 tenant setter를 호출하지 않고 rejection
   reason만 반환한다.
4. navigation false/reject: event를 발행하지 않는다.
5. duplicate `start()`는 listener 하나만 만들고 `stop()`/`ngOnDestroy()`는 handle
   remove를 한 번만 호출한다.
6. event payload는 `clinic.native.navigation.v1`, `version: 1`, tenant, route, query만
   가지며 token/raw URL/storage key가 없다.

- [ ] **Step 2: RED 실행을 관찰한다**

```bash
npm test -- --watch=false --include='src/app/core/services/native-webview-bridge.service.spec.ts' --include='src/app/app.spec.ts'
```

Expected: service/token/module 누락으로 실패한다.

- [ ] **Step 3: 최소 typed adapter/service를 구현한다**

`CAPACITOR_PLATFORM`과 `NATIVE_APP_PLUGIN` injection token을 제공하고 실제 factory는
Capacitor `Capacitor`/`App`를 반환한다. `start()`는 browser no-op 또는 동일 promise를
재사용하고 native에서 listener 등록 → launch URL 처리를 수행한다. `handleUrl()`은
parser → auth membership → tenant set → router navigate → event publish 순서만
허용한다. `Subject`는 private로 두고 readonly `Observable`만 노출한다.

`NativeNavigationEvent`는 다음 shape를 고정한다.

```ts
{
  name: 'clinic.native.navigation.v1',
  version: 1,
  tenantCode: string,
  route: 'calendar' | 'appointments' | 'management',
  query: Readonly<Record<string, string>>
}
```

stop cleanup은 `PluginListenerHandle.remove()`를 await하고, cleanup 오류가 앱 destroy를
실패시키지 않도록 흡수한다. JWT를 읽거나 storage에 쓰는 코드는 추가하지 않는다.

- [ ] **Step 4: GREEN과 App ordering을 확인한다**

```bash
npm test -- --watch=false --include='src/app/core/services/native-webview-bridge.service.spec.ts' --include='src/app/app.spec.ts'
```

Expected: service lifecycle/auth/event와 App의 restore-before-start가 PASS.

- [ ] **Step 5: public export와 browser E2E를 연결한다**

`core/api/index.ts`, `core/services/index.ts`에 실제 export를 추가하고 새 Playwright
contract에서 browser app이 plugin callback을 등록하지 않으며 기존 workforce handoff와
router/session 결과가 변하지 않는지 확인한다.

## Task 4 — native scheme metadata와 문서를 연결한다

**Files:** Android Manifest, iOS Info.plist, README 두 개.

- [ ] **Step 1: static metadata RED를 작성한다**

Node contract test 또는 shell assertion으로 Android manifest의
`android:scheme="@string/custom_url_scheme"` + host `open` + VIEW/DEFAULT/BROWSABLE,
iOS plist의 `CFBundleURLSchemes`와 `io.bluetape4k.clinic.appointment`를 확인한다.
현재 metadata가 없어 RED가 되는 것을 관찰한다.

- [ ] **Step 2: metadata를 최소 수정한다**

Android activity에 다음 intent filter를 추가한다.

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="@string/custom_url_scheme" android:host="open" />
</intent-filter>
```

iOS `Info.plist`에는 동일 scheme을 가진 `CFBundleURLTypes` 한 항목을 추가한다.

- [ ] **Step 3: README 계약을 갱신한다**

두 Korean README에 deep-link 예제, host handoff가 인증 이전에 주입되어야 한다는 점,
token 비영속, browser E2E와 native SDK/device 검증의 차이, #24 링크를 같은 의미로
추가한다. `@capacitor/app` 설치와 `npm run cap:sync` 명령은 실제 package/script와
일치시킨다.

- [ ] **Step 4: metadata/doc GREEN을 확인한다**

```bash
python3 - <<'PY'
from pathlib import Path
android = Path('frontend/appointment-frontend/android/app/src/main/AndroidManifest.xml').read_text()
plist = Path('frontend/appointment-frontend/ios/App/App/Info.plist').read_text()
assert 'android.intent.action.VIEW' in android and 'android:host="open"' in android
assert 'clinic.appointment' in plist
PY
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  frontend/appointment-frontend/README.md frontend/appointment-frontend/README.ko.md \
  docs/superpowers/specs/2026-08-27-issue-27-native-webview-bridge-design.md \
  docs/superpowers/plans/2026-08-27-issue-27-native-webview-bridge-plan.md --json
```

Expected: static assertion과 terminology findings 0.

## Task 5 — proportional validation과 verifier proof

- [ ] **Step 1: frontend targeted validation을 순서대로 실행한다**

```bash
cd frontend/appointment-frontend
npm test -- --watch=false --include='src/app/core/api/native-deep-link.spec.ts' --include='src/app/core/services/native-webview-bridge.service.spec.ts' --include='src/app/app.spec.ts'
npx tsc --noEmit -p tsconfig.app.json
npm run build
npm run test:bundle
npm run pwa:verify
npm run test:e2e
```

Expected: parser/service/App contract, TypeScript, production bundle, existing PWA/bundle
contracts와 Chromium E2E가 모두 PASS.

- [ ] **Step 2: Capacitor sync와 native toolchain 경계를 확인한다**

```bash
npm run cap:sync
npx cap doctor
xcodebuild -version || true
adb version || true
sdkmanager --version || true
xcrun simctl list devices available || true
```

Expected: plugin이 iOS/Android project에 sync되고 `cap doctor`가 현재 config를 읽는다.
SDK가 없으면 실제 native build/device 결과는 N/A로 기록하고 #24로 넘긴다.

- [ ] **Step 3: docs/diff/source audit를 실행한다**

```bash
cd ../..
npm --prefix frontend/appointment-frontend audit --omit=dev --audit-level=moderate
git diff --check
git status --short
git diff --stat origin/feat/issue-25-pwa-offline-cache...HEAD
rg -n "localStorage|sessionStorage|auth_token|__CLINIC_WORKFORCE_AUTH__" frontend/appointment-frontend/src/app/core/services/native-webview-bridge.service.ts
```

Expected: runtime audit 취약점 0, diff whitespace 0, token storage 신규 문자열 없음,
변경 scope가 이 plan의 write scope로 제한된다. 기존 tenant `sessionStorage` 호출은
service가 직접 만들지 않았음을 코드 근거로 구분한다.

- [ ] **Step 4: spec/plan verifier를 기록한다**

검증 표에서 각 수용 기준에 exact test/output path를 연결하고, N/A native SDK 행에는
명령 결과와 #24 handoff를 기록한다. 불일치가 있으면 implementation으로 돌아가서
문서와 code를 함께 고친다.

## Task 6 — 7-Tier final review와 lesson

- [ ] **Step 1: final checklist를 작성한다**

`bluetape-kotlin-patterns` checklist에서 Kotlin production/test source가 없는 이유를
N/A로 고정하고, 기존 `AuthService`·tenant·Angular signal/DI·Capacitor lifecycle을
실제 파일/라인에 연결한다. `bluetape4k-assertions`는 frontend에 억지로 추가하지 않고
기존 Kotlin module regression build를 required evidence로 기록한다.

- [ ] **Step 2: 7-Tier review artifact를 작성한다**

`docs/superpowers/reviews/2026-08-27-issue-27-native-webview-bridge-implementation-review.ko.md`
에 performance, stability, security, operations, developer/API, user/caller과 main
integration을 각각 표로 남긴다. parser bounds/encoded input, auth fail-closed, event
payload redaction, listener cleanup, native metadata, docs parity를 P0/P1/P2/P3로
정규화하고 P0=0/P1=0을 fresh test와 함께 결론낸다.

- [ ] **Step 3: reusable lesson을 작성한다**

`docs/lessons/2026-08-27-issue-27-native-webview-bridge.md`에 URL contract를 native에
중복하지 않고 WebView parser가 소유하는 이유, appUrlOpen/getLaunchUrl ordering,
token non-persistence, browser/native 검증 차이와 future guard를 기록한다.

- [ ] **Step 4: commit 전 문서 gate를 확인한다**

```bash
npx prettier --check frontend/appointment-frontend/README.md frontend/appointment-frontend/README.ko.md \
  docs/superpowers/specs/2026-08-27-issue-27-native-webview-bridge-design.md \
  docs/superpowers/plans/2026-08-27-issue-27-native-webview-bridge-plan.md \
  docs/superpowers/reviews/2026-08-27-issue-27-native-webview-bridge-implementation-review.ko.md \
  docs/lessons/2026-08-27-issue-27-native-webview-bridge.md
git diff --check
```

Expected: SPW-01~05, Kotlin KT-01~05, 7-Tier main integration 모두 PASS/N/A 근거가
문서와 workflow receipt에 남는다.

## Task 7 — Lore commit, stacked PR와 exact-head gate

- [ ] **Step 1: Korean Lore commit을 만든다**

```bash
git add frontend/appointment-frontend docs/superpowers/specs/2026-08-27-issue-27-native-webview-bridge-design.md \
  docs/superpowers/plans/2026-08-27-issue-27-native-webview-bridge-plan.md \
  docs/superpowers/reviews/2026-08-27-issue-27-native-webview-bridge-implementation-review.ko.md \
  docs/lessons/2026-08-27-issue-27-native-webview-bridge.md
git commit -m "네이티브 WebView typed bridge를 fail-closed 계약으로 연결한다"
```

Commit message에는 Constraint/Rejected/Confidence/Scope-risk/Directive/Tested/
Not-tested trailer를 한국어로 포함한다. `.bluetape` receipt와 unrelated root dirty는
commit에 넣지 않는다.

- [ ] **Step 2: exact base/head를 push한다**

```bash
git push -u origin feat/issue-27-native-webview-bridge
git rev-parse HEAD
git ls-remote origin refs/heads/feat/issue-27-native-webview-bridge
```

Expected: local/remote head가 일치하고 base는 `feat/issue-25-pwa-offline-cache`의
최신 `a3996b0ad66d984c324c304042fc2332d26f9e14`다.

- [ ] **Step 3: PR #27 body와 metadata를 live read-back한다**

PR body는 Korean `왜/무엇/검증/범위·위험` 순서와 마지막 `## DoD Status`를 사용한다.
Issue #27의 milestone `1.4.0`, assignee `debop`, `enhancement`와 frontend 관련
labels를 mirror하고, PR base/head SHA와 #23/#430/#431/#26/#25/#24 링크를 명시한다.
Epic 전체가 끝날 때까지 merge하지 않는다.

- [ ] **Step 4: exact-head CI·review를 통과시킨다**

PR CI와 Frontend CI가 새 commit SHA를 가리키는지 확인하고 모든 applicable job을
통과시킨다. path-filter skip은 workflow 정의와 변경 scope가 일치할 때만 N/A로
기록한다. CI green 뒤 PR review/thread와 Issue body를 다시 읽고 P0/P1을 0으로
수렴시킨다.

- [ ] **Step 5: workflow receipt와 Issue #27을 갱신한다**

`check-result`, `component-evidence`, `lane-complete`, `completion-check`, `complete`,
`live-report-create`를 helper로 순서대로 수행한다. immutable live report의 exact
head/CI/PR URL을 PR body와 Issue #27 checklist에 기록한다. #24 native SDK/device
검증은 unchecked follow-up으로 남긴다.

## 최종 중단 조건

이 slice의 종료는 PR이 exact-head CI와 7-Tier 검토를 통과한 merge-ready 상태다.
CG-16~CG-18은 Epic #13의 모든 child issue가 완료될 때까지 `PENDING`이며, 이번
slice에서는 merge/branch deletion/local develop sync를 수행하지 않는다.
