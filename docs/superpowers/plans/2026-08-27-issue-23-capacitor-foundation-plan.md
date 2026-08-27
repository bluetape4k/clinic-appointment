# Issue #23 Capacitor foundation 구현 계획

> **For agentic workers:** 이 계획은 Issue #23 전용 slice다. 후속 PR은
> #430·#431·#26·#25·#27·#24의 책임을 침범하지 않는다.

**목표:** Angular 22 frontend를 기존 브라우저 계약 그대로 Capacitor iOS·Android
WebView에서 실행할 수 있는 재현 가능한 foundation을 만든다.

**아키텍처:** Angular production bundle을 `dist/appointment-frontend/browser`에
만들고 Capacitor의 `webDir`로 복사한다. `TenantApiClient`, route, 인증 상태와
responsive shell은 재사용하며 native 기능·API origin·PWA는 후속 slice에 둔다.

**기술 스택:** Angular 22, Node 22 toolchain, Capacitor 8.5.0, npm lockfile,
iOS·Android Capacitor platform project.

---

## 구현 범위와 기준

- 기준 브랜치: `origin/develop`의 `49a86b1f2bb4ea733795b5ad7c5d92551e510814`
- 작업 브랜치: `feat/issue-23-capacitor-foundation`
- frontend 경로: `frontend/appointment-frontend`
- 새 Capacitor dependency는 core/CLI/iOS/Android 모두 `8.5.0`으로 고정한다.
- 현재 `environment*.ts`의 `/api`, patient cookie/XSRF, workforce Bearer
  scope는 변경하지 않는다.
- `@capacitor/app`, keyboard, status-bar, push notification plugin은 이
  계획에 포함하지 않는다.

## 파일 책임 지도

- 수정: `frontend/appointment-frontend/package.json` — Capacitor dependency와
  재현 가능한 `cap:*` 명령
- 수정: `frontend/appointment-frontend/package-lock.json` — npm dependency
  resolution
- 생성: `frontend/appointment-frontend/capacitor.config.ts` — app identity와
  Angular output 경계
- 생성: `frontend/appointment-frontend/ios/` — Capacitor iOS project
- 생성: `frontend/appointment-frontend/android/` — Capacitor Android project
- 수정: `frontend/appointment-frontend/README.md` — 한국어 setup/sync 안내
- 수정: `frontend/appointment-frontend/README.ko.md` — 한국어 setup/sync 안내
- 생성: `docs/lessons/2026-08-27-issue-23-capacitor-foundation.md` — 결정과
  검증 경계
- 생성: `docs/superpowers/reviews/2026-08-27-issue-23-capacitor-foundation-plan-review.ko.md` —
  Step 3-R 여섯 관점 통합 검토

## Task 1: dependency와 명령을 고정한다

**Files:**

- Modify: `frontend/appointment-frontend/package.json`
- Modify: `frontend/appointment-frontend/package-lock.json`

- [ ] **Step 1: npm metadata를 다시 확인한다**

Run from `frontend/appointment-frontend`:

```bash
npm view @capacitor/core@8.5.0 engines peerDependencies --json
npm view @capacitor/cli@8.5.0 engines peerDependencies --json
npm view @capacitor/ios@8.5.0 peerDependencies --json
npm view @capacitor/android@8.5.0 peerDependencies --json
```

Expected: CLI engine이 Node 22 이상이고 iOS·Android package가 core 8.5.0을
peer dependency로 요구한다.

- [ ] **Step 2: RED — dependency가 아직 없는지 확인한다**

```bash
npm ls @capacitor/core @capacitor/cli @capacitor/ios @capacitor/android --depth=0
```

Expected: current package에 해당 dependency가 없거나 설치되지 않았다는
결과다. 이미 8.5.0이 있으면 기존 상태를 기록하고 중복 설치하지 않는다.

- [ ] **Step 3: 최소 dependency를 추가한다**

```bash
npm install --save-exact @capacitor/core@8.5.0 @capacitor/ios@8.5.0 @capacitor/android@8.5.0
npm install --save-dev --save-exact @capacitor/cli@8.5.0
```

Expected: runtime dependency에 core/iOS/Android, devDependency에 CLI가
등록되고 package-lock이 갱신된다.

- [ ] **Step 4: 명령 script를 추가한다**

`package.json`의 `scripts`에 다음 세 항목을 추가한다.

```json
"cap:sync": "npm run build && npx cap sync",
"cap:open:ios": "npx cap open ios",
"cap:open:android": "npx cap open android"
```

`cap:sync`는 항상 최신 Angular bundle을 먼저 만들며, remote `server.url`을
설정하지 않는다.

- [ ] **Step 5: GREEN — dependency와 script를 확인한다**

```bash
npm ls @capacitor/core @capacitor/cli @capacitor/ios @capacitor/android --depth=0
npm pkg get scripts.cap:sync scripts.cap:open:ios scripts.cap:open:android
```

Expected: 네 package가 모두 8.5.0이고 세 script 값이 정확히 반환된다.

## Task 2: Capacitor config와 platform project를 생성한다

**Files:**

- Create: `frontend/appointment-frontend/capacitor.config.ts`
- Create: `frontend/appointment-frontend/ios/`
- Create: `frontend/appointment-frontend/android/`

- [ ] **Step 1: config 계약을 작성한다**

`capacitor.config.ts`는 다음 값을 사용한다.

```ts
import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'io.bluetape4k.clinic.appointment',
  appName: 'Clinic Appointment',
  webDir: 'dist/appointment-frontend/browser',
};

export default config;
```

`server.url`, cleartext 허용, native storage, plugin 설정은 추가하지 않는다.

- [ ] **Step 2: Angular output을 먼저 만든다**

```bash
npm run build
test -f dist/appointment-frontend/browser/index.html
```

Expected: production build가 성공하고 `webDir` root에 `index.html`이 있다.

- [ ] **Step 3: platform project를 생성한다**

```bash
npx cap add ios
npx cap add android
```

Expected: `ios/`와 `android/`가 생성되고 Capacitor project가 config의 appId와
appName을 사용한다. SDK가 없는 호스트에서 platform build가 실패해도 생성과
설정 검증은 별도 증거로 남긴다.

- [ ] **Step 4: sync를 실행한다**

```bash
npm run cap:sync
find ios android -name index.html -o -name 'chunk-*.js'
```

Expected: Angular `index.html`, 정적 asset과 lazy chunk가 각 platform
project의 Capacitor asset 영역에 복사된다. 생성 경로는 명령 결과로 기록한다.
동일 명령을 한 번 더 실행해 재실행 시 불필요한 변경이 생기지 않는지 확인한다.

## Task 3: README setup 계약을 기록한다

**Files:**

- Modify: `frontend/appointment-frontend/README.md`
- Modify: `frontend/appointment-frontend/README.ko.md`

- [ ] **Step 1: Capacitor 섹션을 두 README에 추가한다**

두 문서에 다음 내용을 저장소 실제 경로와 일치하게 추가한다.

````markdown
## Capacitor WebView

Angular bundle을 만든 뒤 native asset을 동기화합니다.

```bash
npm run cap:sync
```

iOS는 Xcode, Android는 Android Studio와 SDK가 필요합니다.

```bash
npm run cap:open:ios
npm run cap:open:android
```

`cap:sync`는 `dist/appointment-frontend/browser`를 Capacitor `webDir`로
사용합니다. API origin·cookie·CSRF 정책은 [Issue #430](https://github.com/bluetape4k/clinic-appointment/issues/430),
실제 디바이스 검증은 [Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24)에서
다룹니다.
````

문서에는 native build를 브라우저 E2E로 대체할 수 없다는 경계를 포함한다.

- [ ] **Step 2: 문서 read-back과 용어 검사를 실행한다**

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  README.md README.ko.md
git diff --check
```

Expected: terminology audit findings 0, whitespace/error 0, 명령·경로·Issue
링크가 실제 파일과 일치한다.

## Task 4: 비기능 경계를 검증한다

- [ ] **Step 1: 기존 frontend contract를 회귀 검증한다**

```bash
npm run build
npm test -- --watch=false
npx tsc --noEmit -p tsconfig.app.json
npm run test:e2e
```

Expected: production build, 기존 단위 테스트, TypeScript 검사와 Chromium E2E가
통과한다. Node 22 CI에서의 결과를 우선하며, 호스트 Node 버전 차이는 증거에
기록한다.

- [ ] **Step 2: diff와 scope를 확인한다**

```bash
git status --short
git diff --stat origin/develop...HEAD
git diff --check
```

Expected: 변경 파일은 package/lock, Capacitor config/platform, README와 lesson으로
제한된다. API·인증·PWA·UX·bridge 구현은 포함하지 않는다.

- [ ] **Step 3: native toolchain 경계를 확인한다**

```bash
xcodebuild -version
adb version
sdkmanager --version
xcrun simctl list devices available
```

Expected: 설치된 도구와 누락된 도구를 각각 기록한다. 브라우저 검증 성공을
native build 성공으로 간주하지 않으며, SDK가 없으면 #24의 실행 환경 증거로
넘긴다.

## Task 5: lesson과 commit을 남긴다

**Files:**

- Create: `docs/lessons/2026-08-27-issue-23-capacitor-foundation.md`
- Create: `docs/superpowers/reviews/2026-08-27-issue-23-capacitor-foundation-plan-review.ko.md`

- [ ] **Step 1: lesson에 재현 조건과 경계를 기록한다**

Angular output subdirectory와 `webDir`, Capacitor 8.5.0 선택, browser/native
검증 차이, SDK 없는 호스트의 한계와 #24·#430 후속 경계를 한국어로 기록한다.

- [ ] **Step 2: final review와 Lore commit을 실행한다**

```bash
git diff --check
git status --short
git add frontend/appointment-frontend/package.json \
  frontend/appointment-frontend/package-lock.json \
  frontend/appointment-frontend/capacitor.config.ts \
  frontend/appointment-frontend/ios \
  frontend/appointment-frontend/android \
  frontend/appointment-frontend/README.md \
  frontend/appointment-frontend/README.ko.md \
  docs/lessons/2026-08-27-issue-23-capacitor-foundation.md \
  docs/superpowers/reviews/2026-08-27-issue-23-capacitor-foundation-plan-review.ko.md
git commit -m "feat: Angular 앱의 Capacitor WebView foundation을 고정한다"
```

Commit body에는 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`,
`Directive`, `Tested`, `Not-tested` trailer를 포함하고, #430이 API·인증 origin과
cookie/CSRF 계약을 소유한다는 재사용 경계를 명시한다.

## Rollback과 재실행 경계

- npm install 또는 `cap add`가 실패하면 로그와 working tree를 먼저 보존하고,
  임의 revert 대신 실패 원인을 수정한 뒤 같은 명령을 재실행한다.
- `webDir` 검증이 실패하면 Angular output을 다시 확인하고 config의 경로만
  수정한 뒤 build와 sync를 반복한다.
- native SDK가 없으면 생성·sync·config 검증과 native build 검증을 분리하고,
  호스트 한계를 #24 증거로 남긴다.
- API·인증·PWA·UX·bridge 변경 요구가 발견되면 이 branch를 확장하지 않고
  해당 후속 child slice로 넘긴다.

## 최종 acceptance traceability

| Acceptance | Evidence |
|---|---|
| dependency 재현성 | `package.json`, `package-lock.json`, `npm ls` |
| app identity와 output 경계 | `capacitor.config.ts`, `cap config` |
| bundle sync | `npm run cap:sync`, platform asset read-back |
| iOS·Android project | `npx cap add ios`, `npx cap add android` 결과 |
| 기존 browser/API/auth 보존 | build, unit test, TypeScript, E2E와 변경 scope diff |
| setup 문서 | 두 README read-back, Korean terminology audit |
| native toolchain 경계 | `xcodebuild`, `adb`, `sdkmanager`, `simctl` 결과와 lesson |
