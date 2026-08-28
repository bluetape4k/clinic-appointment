# Issue #26 모바일 Safe Area·키보드·viewport 구현 7-Tier 검토

## 검토 범위와 기준

- 대상 branch: `feat/issue-26-safe-area-keyboard`
- stacked base: #431 PR #434 head `9050199c8a6574547b3574792235d9de1fa35eab`
- 범위: 공통 `appMobileViewport` directive, staff/patient scroll 경계, Safe Area·키보드 inset CSS,
  touch target, Angular unit·Playwright browser contract, README·lesson
- 제외: Xcode/Android SDK, 실기기 IME·orientation, native keyboard/status-bar plugin(#27/#24)
- 기준: `bluetape-workflow`, `bluetape-full-feature`, `bluetape-writer`,
  `bluetape-kotlin-patterns`와 모듈별 7-Tier review
- 병합 정책: Epic #13의 모든 stacked slice가 끝나기 전 PR을 병합하지 않는다.

## 7-Tier 결과

| Tier | P0 | P1 | P2 | P3 | 근거와 판정 |
|---|---:|---:|---:|---:|---|
| 1. Performance | 0 | 0 | 1 | 0 | `visualViewport`의 `resize`·`scroll`마다 CSS 변수만 갱신하며 polling·retry·새 dependency가 없다. production initial `622,912` bytes가 `1,000,000` bytes budget 아래다. 이벤트 빈도 측정과 native cold-start는 #24 후속 범위다. |
| 2. Stability | 0 | 0 | 0 | 0 | directive가 `ngOnDestroy`에서 두 listener를 제거하고, visualViewport·`requestAnimationFrame` 미지원 fallback을 가진다. 2개 unit test와 11개 browser contract가 이를 고정한다. |
| 3. Security | 0 | 0 | 0 | 0 | DOM focus와 CSS custom property만 다루며 API·cookie·JWT·native bridge·외부 요청을 추가하지 않았다. 새 plugin/npm dependency와 비밀값 출력도 없다. |
| 4. Operator/Ops | 0 | 0 | 1 | 0 | README에 재현 명령과 browser/native 경계를 기록하고 bundle·docs validator를 사용한다. iOS/Android SDK·실기기 evidence는 이 호스트에서 실행하지 않아 #27/#24로 분리했다. |
| 5. Developer/API | 0 | 0 | 0 | 0 | 기존 Angular standalone `shared` barrel, Angular Material shell, Capacitor `webDir`, Playwright 설정을 재사용했다. 페이지별 viewport 계산과 중복 keyboard plugin을 만들지 않았다. |
| 6. User/Caller | 0 | 0 | 1 | 0 | 320·375·393·430px portrait와 짧은 landscape에서 focus 입력, form action, 44px touch target, safe-area scroll padding과 horizontal overflow를 확인한다. Chromium 통과만 native IME 성공으로 승격하지 않는다. |
| 7. Main-session integration | 0 | 0 | 1 | 0 | spec·plan·review·lesson·README와 issue write scope를 연결하고 #431 exact head 위에 올린다. PR exact-head CI와 workflow receipt live read-back은 push 후 남은 gate다. |

### 종합 판정

- **P0 = 0, P1 = 0, P2 = 4, P3 = 0**
- P2는 이벤트 빈도/native 측정 경계, 실기기 IME·orientation 책임, browser 결과의
  native 승격 금지, push 후 exact-head receipt gate다. 구현을 차단하는 결함은 아니며
  후속 이슈와 stacked workflow에서 추적한다.
- `bluetape-kotlin-patterns`: 이번 slice는 Kotlin production/test 파일을 수정하지
  않아 직접 적용은 N/A다. #430 backend의 tenant·assertion 계약과 기존 모듈 경계를
  재사용하며 Kotlin 스타일을 frontend에 기계적으로 복제하지 않는다.
- `bluetape4k-assertions`: frontend는 TypeScript/Playwright assertion을 사용하므로
  JVM assertion dependency를 추가하지 않았다. 기존 #430의 `bluetape4k-assertions`
  계약을 우회하거나 중복하지 않는다.

## 재사용·경계 확인

- `appMobileViewport`는 `src/app/shared/index.ts`에서 export하고 `App`의 portal root와
  staff mobile layout에 함께 적용한다.
- `100dvh`를 fallback으로 두고 `visualViewport.height`·keyboard inset을 CSS 변수로
  공유하여 auth, portal shell, appointment form이 같은 scroll 계약을 소비한다.
- Angular Material button과 기존 bottom navigation을 유지하고, 글로벌 44px 규칙은
  emulated component style의 범위 누락을 보완하는 최소 selector로 제한한다.
- 새 Capacitor plugin은 추가하지 않는다. native status bar, keyboard event, back button,
  cookie/bridge와 release signing은 #27/#24에서 SDK/device evidence로 별도 검증한다.

## 문서·작업 품질 gate

| Gate | 결과 | 증거 |
|---|---|---|
| SPW-01 Spec | PASS | `docs/superpowers/specs/2026-08-27-issue-26-safe-area-keyboard-design.md` |
| SPW-02 Plan | PASS | `docs/superpowers/plans/2026-08-27-issue-26-safe-area-keyboard-plan.md` |
| SPW-03 Review | PASS | 본 문서의 7-Tier·재사용 경계 판정 |
| SPW-04 Lesson | PASS | `docs/lessons/2026-08-27-issue-26-safe-area-keyboard.md` |
| SPW-05 Korean artifact audit | PASS | `audit-korean-terms.mjs`: 6 file(s), findings=0 |

## 검증 증거

| 영역 | 명령 | 결과 |
|---|---|---|
| Directive unit | `npm test -- --watch=false --include=src/app/shared/directives/mobile-viewport.directive.spec.ts` | 1 file, 2 tests passed |
| Frontend unit | `npm test -- --watch=false` | 48 files, 342 tests passed |
| Production build | `npm run build` | Angular production build passed |
| Bundle contract | `npm run bundle:verify` | `ok: true`, `initialBytes: 622912`, 4 lazy routes, `failures: []` |
| TypeScript | `npx tsc --noEmit -p tsconfig.app.json` | passed |
| Browser contract | `npx playwright test e2e/patient-portal.spec.ts e2e/mobile-lazy-routes.spec.ts` | 11 tests passed |
| Docs contract | `npm run docs:verify` | `ok: true`, `documentsChecked=10`, `sourceChecks=8`, `failures=[]` |
| Diff hygiene | `git diff --check` | passed after implementation·docs additions |

최종 PR exact-head CI와 GitHub metadata/workflow receipt는 implementation commit과
push 후 추가한다. 이 문서는 native build/device PASS를 주장하지 않는다.

## 결론

**PASS — 구현·로컬 검증 후 PR 생성과 exact-head CI gate로 진행 가능.** 기존
Angular/Material/Capacitor 경계를 재사용했고 P0/P1은 없다. PR은 다음 #25 slice의
base로만 사용하며 Epic #13 전체 완료 전에는 병합하지 않는다.
