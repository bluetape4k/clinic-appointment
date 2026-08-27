# Issue #24 native WebView 검증 구현 7-Tier review

## 범위와 판정

- 대상 branch: `feat/issue-24-native-webview-validation`
- stacked base: `feat/issue-27-native-webview-bridge` (`379a52fca753a6094f4bf136f54cfeb67e620685`)
- 검토 범위: Capacitor iOS/Android build-smoke workflow, environment/report validator,
  Playwright mobile contract, 날짜 deep-link 보정, README와 운영 증적
- 판정: P0=0, P1=0, P2=1, P3=0
- 현재 상태: 구현 검증은 통과했지만 native hosted runner receipt가 없으므로 Issue #24와
  Epic #13은 PENDING이다.

## 모듈별 결과

| 모듈/경계                                          | 7-Tier 결과     | 근거                                                                                                             | 미확인/후속                                               |
| -------------------------------------------------- | --------------- | ---------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| `frontend/appointment-frontend` TypeScript/Angular | PASS            | native validator 3건, report 4건, workflow 2건, calendar-state 18건, mobile contract 4건, 전체 Chromium E2E 23건 | hosted native runner receipt 필요                         |
| Playwright WebKit iPhone / Chromium Pixel profile  | PASS            | `mobile-webview-contract.spec.ts` 4 passed, exact route/auth/tenant/overflow/focus/deep-link 확인                | 실제 WKWebView/Android WebView와 동일하다고 승격하지 않음 |
| Capacitor Android/iOS metadata                     | PASS            | 기존 `bridge:verify`, `cap:sync`, `npx cap doctor` 및 workflow marker                                            | emulator/simulator build와 process는 CI에서 실행 필요     |
| `:appointment-api` Kotlin/Spring                   | PASS (N/A 변경) | `./gradlew :appointment-api:build` 성공, 기존 `bluetape4k-assertions` 경계 보존                                  | Kotlin source 변경 없음                                   |
| `bluetape4k-assertions`                            | PASS (N/A 변경) | appointment-api build/kover 검증 경계를 재사용하고 새 assertion dependency를 추가하지 않음                       | native smoke의 backend 실 origin은 별도 환경에서 확인     |

## 7-Tier 판정

| Tier                        | 검토 질문                                                          | 판정 | 증적                                                                                         |
| --------------------------- | ------------------------------------------------------------------ | ---- | -------------------------------------------------------------------------------------------- |
| 1. Performance              | mobile runner와 report가 불필요한 반복/무제한 출력을 만들지 않는가 | PASS | Playwright `--workers=1`, report field/line bounded, emulator timeout                        |
| 2. Stability                | exact ref, 실패 전파, 재현 가능한 route가 보장되는가               | PASS | `ref`+`expected_sha`, checkout SHA assertion, smoke outcome enforce, local date formatter    |
| 3. Security                 | token/cookie/raw log가 새 artifact나 storage로 유출되지 않는가     | PASS | report forbidden-content contract, workforce JWT memory-only, browser storage null assertion |
| 4. Operator/Ops             | CI 결과를 읽고 실패를 복구할 수 있는가                             | PASS | workflow dispatch, platform별 report/APK artifact, environment probe, actionlint             |
| 5. Developer/API            | public script와 schema가 작고 명확한가                             | PASS | `native:*` scripts, strict report schema, workflow validator, Korean README                  |
| 6. User/Caller              | mobile viewport에서 실제 사용 흐름이 유지되는가                    | PASS | calendar→appointments, detail/deep-link, tenant Bearer, 320/375 overflow/focus               |
| 7. Main-session integration | stacked train과 issue/PR 증적 경계가 지켜지는가                    | PASS | base가 #27 exact head, merge unchecked, native receipt 없이는 PENDING                        |

## 검증 명령과 결과

```text
npm run test:native:environment       PASS (3)
npm run test:native:report            PASS (4)
npm run test:native:workflow          PASS (2)
npm run native:environment            PASS; targets.ios=false, targets.android=false (로컬 capability 부족)
npm run native:workflow               PASS
npm test -- --watch=false                  PASS (52 files, 386 tests)
npm test -- --watch=false --include=...calendar-state.service.spec.ts  PASS (18)
npx playwright test e2e/mobile-webview-contract.spec.ts --project=mobile-ios --project=mobile-android --workers=1  PASS (4)
npm run test:e2e -- --workers=1       PASS (27; Chromium 23 + mobile profiles 4)
actionlint .github/workflows/native-webview-ci.yml  PASS
npx tsc --noEmit -p tsconfig.app.json  PASS
npm run build                          PASS
npm run cap:sync                       PASS
npx cap doctor                         PASS
./gradlew :appointment-api:build       PASS
npm audit --omit=dev --audit-level=moderate  PASS (0 vulnerabilities)
```

## P2와 후속 조건

현재 macOS에는 full Xcode와 Android SDK/`adb`가 없고 simulator/emulator도 없다.
따라서 local probe는 정확히 `false`를 반환하며 native PASS로 집계하지 않는다. branch를
push한 뒤 다음과 같이 exact SHA workflow를 dispatch하고, Android/iOS report의
`platform`, `commit`, `result`와 job conclusion을 read-back해야 P2를 닫을 수 있다.

```bash
HEAD_SHA="$(git rev-parse HEAD)"
gh workflow run native-webview-ci.yml \
  --ref feat/issue-24-native-webview-validation \
  -f ref="$HEAD_SHA" -f expected_sha="$HEAD_SHA"
```

native report가 없거나 `result=failed`이면 Issue #24 완료 checkbox를 체크하지 않고
Epic #13을 닫지 않는다.
