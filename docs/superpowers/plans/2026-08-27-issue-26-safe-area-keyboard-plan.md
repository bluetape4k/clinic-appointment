# 모바일 Safe Area·키보드·뷰포트 구현 계획

> **For agentic workers:** 이 계획은 현재 stacked worktree에서 순서대로 실행한다. 각 단계의 체크 결과와 명령을 workflow receipt에 기록하고, PR은 merge하지 않는다.

**Goal:** 기존 Angular·Capacitor 셸을 재사용하면서 Safe Area, dynamic viewport, 키보드 focus scroll, touch target 계약을 모바일 브라우저와 WebView 경계에 맞게 고정한다.

**Architecture:** `MobileViewportDirective`가 host별 `visualViewport` 높이와 키보드 inset을 CSS 변수로 관리하고 focus된 HTMLElement를 중앙으로 스크롤한다. staff mobile shell과 patient portal root에만 directive를 부착하며, CSS는 `100dvh`, `env(safe-area-inset-*)`, `scroll-padding`을 조합한다. 네이티브 Keyboard/StatusBar plugin과 API 코드는 변경하지 않는다.

**Tech Stack:** Angular 22 standalone components, TypeScript 6, SCSS, Vitest, Playwright, Capacitor 8.5.0.

---

## 설계 traceability

| 수용 기준 | 구현/검증 단계 |
|---|---|
| dynamic viewport와 Safe Area | Task 2–3, Task 5 portrait/landscape E2E |
| focus input과 form action 가시성 | Task 1–2, Task 5 keyboard/focus E2E |
| 44px touch target | Task 3–4, Task 5 computed-style E2E |
| listener cleanup | Task 1–2 directive 단위 테스트 |
| 새 dependency 없음 | Task 4 package diff 검증, Task 6 bundle/build |
| native 경계 정직성 | Task 6 README/lesson/review, Issue #27/#24 링크 |

## Task 1: RED — viewport directive 계약 테스트 작성

**Files:**

- Create: `frontend/appointment-frontend/src/app/shared/directives/mobile-viewport.directive.spec.ts`
- Modify: `frontend/appointment-frontend/src/app/shared/index.ts` (export 확인은 구현 단계에서만)

- [ ] **Step 1: 실패 테스트를 먼저 작성한다**

  TestBed host component에 `[appMobileViewport]`를 붙이고 다음 동작을 명시한다.

  ```ts
  it('visualViewport 높이와 keyboard inset을 host CSS 변수로 반영한다', () => {
    const viewport = new EventTarget() as EventTarget & { height: number; offsetTop: number };
    viewport.height = 600;
    viewport.offsetTop = 0;
    vi.stubGlobal('innerHeight', 800);
    vi.stubGlobal('visualViewport', viewport);
    const fixture = TestBed.createComponent(HostComponent);

    fixture.detectChanges();

    expect(fixture.nativeElement.style.getPropertyValue('--mobile-viewport-height')).toBe('600px');
    expect(fixture.nativeElement.style.getPropertyValue('--mobile-keyboard-inset')).toBe('200px');
  });

  it('focus된 입력을 한 번 scrollIntoView하고 destroy 시 listener를 제거한다', () => {
    const remove = vi.spyOn(EventTarget.prototype, 'removeEventListener');
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const input = document.createElement('input');
    const scrollIntoView = vi.fn();
    input.scrollIntoView = scrollIntoView;
    fixture.nativeElement.append(input);

    input.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'center', inline: 'nearest' });

    fixture.destroy();
    expect(remove).toHaveBeenCalled();
  });
  ```

- [ ] **Step 2: RED를 확인한다**

  Run: `cd frontend/appointment-frontend && npm test -- --watch=false --include='src/app/shared/directives/mobile-viewport.directive.spec.ts'`

  Expected: `FAIL` with the directive/selector missing. 기존 구현이 없어야 하며 테스트가 즉시 통과하면 테스트 설정을 먼저 고친다.

## Task 2: GREEN — directive 최소 구현

**Files:**

- Create: `frontend/appointment-frontend/src/app/shared/directives/mobile-viewport.directive.ts`
- Modify: `frontend/appointment-frontend/src/app/shared/index.ts`
- Test: `frontend/appointment-frontend/src/app/shared/directives/mobile-viewport.directive.spec.ts`

- [ ] **Step 1: `MobileViewportDirective`를 구현한다**

  ```ts
  @Directive({ selector: '[appMobileViewport]', standalone: true })
  export class MobileViewportDirective implements OnDestroy {
    private readonly host = inject(ElementRef<HTMLElement>).nativeElement;
    private readonly document = inject(DOCUMENT);
    private readonly viewport = this.document.defaultView?.visualViewport;
    private readonly onViewportChange = () => this.updateViewport();

    constructor() {
      this.updateViewport();
      this.viewport?.addEventListener('resize', this.onViewportChange, { passive: true });
      this.viewport?.addEventListener('scroll', this.onViewportChange, { passive: true });
    }

    @HostListener('focusin', ['$event'])
    onFocusIn(event: FocusEvent): void {
      const target = event.target;
      if (!(target instanceof HTMLElement) || !this.host.contains(target)) return;
      requestAnimationFrame(() => target.scrollIntoView({ block: 'center', inline: 'nearest' }));
    }

    ngOnDestroy(): void {
      this.viewport?.removeEventListener('resize', this.onViewportChange);
      this.viewport?.removeEventListener('scroll', this.onViewportChange);
    }

    private updateViewport(): void {
      const windowRef = this.document.defaultView;
      const height = this.viewport?.height ?? windowRef?.innerHeight ?? 0;
      if (height <= 0) return;
      const inset = Math.max(0, (windowRef?.innerHeight ?? height) - (this.viewport?.offsetTop ?? 0) - height);
      this.host.style.setProperty('--mobile-viewport-height', `${Math.round(height)}px`);
      this.host.style.setProperty('--mobile-keyboard-inset', `${Math.round(inset)}px`);
    }
  }
  ```

  Use Angular `DOCUMENT` injection and no direct native plugin. Export the directive from `shared/index.ts` so standalone `App` imports one stable symbol.

- [ ] **Step 2: GREEN을 확인한다**

  Run: `cd frontend/appointment-frontend && npm test -- --watch=false --include='src/app/shared/directives/mobile-viewport.directive.spec.ts'`

  Expected: 모든 directive 계약 테스트 `PASS`.

- [ ] **Step 3: 전체 frontend unit을 재실행한다**

  Run: `cd frontend/appointment-frontend && npm test -- --watch=false`

  Expected: 기존 테스트와 새 테스트가 모두 `PASS`.

## Task 3: shell·Safe Area·scroll boundary 스타일 적용

**Files:**

- Modify: `frontend/appointment-frontend/src/app/app.html`
- Modify: `frontend/appointment-frontend/src/app/app.ts`
- Modify: `frontend/appointment-frontend/src/app/app.scss`
- Modify: `frontend/appointment-frontend/src/styles.scss`
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/patient-portal-shell.component.scss`

- [ ] **Step 1: directive를 두 root에 부착한다**

  `app.html`의 `.portal-root`와 `.mobile-layout`에 `appMobileViewport`를 추가하고 `app.ts`의 standalone imports에 `MobileViewportDirective`를 추가한다.

- [ ] **Step 2: 동적 높이와 inset을 CSS로 연결한다**

  `app.scss`에는 다음 계약을 적용한다.

  ```scss
  :host { min-height: 100dvh; }
  .portal-root, .mobile-layout {
    min-height: var(--mobile-viewport-height, 100dvh);
    scroll-padding: 24px 0 calc(24px + env(safe-area-inset-bottom) + var(--mobile-keyboard-inset, 0px));
  }
  .mobile-content {
    min-height: 0;
    padding-top: calc(16px + env(safe-area-inset-top));
    padding-bottom: calc(16px + env(safe-area-inset-bottom));
    scroll-padding-bottom: calc(24px + env(safe-area-inset-bottom) + var(--mobile-keyboard-inset, 0px));
  }
  .bottom-nav { padding-bottom: env(safe-area-inset-bottom); }
  ```

  `styles.scss`에는 `html, body`의 `min-height: 100%`, `overscroll-behavior-x: none`, `box-sizing` 계약을 추가하되 기존 Material typography를 덮어쓰지 않는다.

- [ ] **Step 3: portal shell의 mobile scroll과 touch target을 보완한다**

  `@media (max-width: 560px)`에서 portal frame/main이 `min-height: var(--mobile-viewport-height, 100dvh)`, `padding-bottom: calc(20px + env(safe-area-inset-bottom))`을 사용하고, nav anchor와 button이 최소 44px을 유지하도록 한다. desktop selector에는 영향을 주지 않는다.

- [ ] **Step 4: style unit/build를 확인한다**

  Run: `cd frontend/appointment-frontend && npm run build && npm run bundle:verify`

  Expected: production build와 bundle 계약이 `PASS`; anyComponentStyle 8kB budget을 넘지 않는다.

## Task 4: auth·예약 form의 keyboard-friendly 계약 고정

**Files:**

- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-login-page.component.scss`
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-register-page.component.scss`
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-appointments-page.component.ts`
- Modify: `frontend/appointment-frontend/src/app/features/appointments/appointment-form/appointment-form.component.scss`

- [ ] **Step 1: auth page의 높이·padding·touch target을 수정한다**

  `.auth-page`의 `min-height`를 `var(--mobile-viewport-height, 100dvh)`로 바꾸고 `padding-top/bottom`에 `env(safe-area-inset-top/bottom)`과 keyboard inset을 반영한다. submit/button은 모바일에서 `min-height: 48px`을 유지한다.

- [ ] **Step 2: patient appointment form의 scroll padding과 action row를 수정한다**

  inline styles에 `scroll-margin-block: 24px`, `scroll-padding-block-end: calc(24px + var(--mobile-keyboard-inset, 0px))`를 추가하고 action row를 좁은 화면에서 세로로 쌓을 수 있게 한다. form 입력의 기존 name/label/API 동작은 바꾸지 않는다.

- [ ] **Step 3: staff appointment form의 narrow layout을 수정한다**

  `.appointment-form-container`와 `.form-actions`에 `padding-bottom: calc(24px + env(safe-area-inset-bottom) + var(--mobile-keyboard-inset, 0px))`, 560px breakpoint에서 one-column grid와 wrap을 적용한다. Material field와 기존 form controls를 재사용한다.

- [ ] **Step 4: 관련 component unit을 확인한다**

  Run: `cd frontend/appointment-frontend && npm test -- --watch=false`

  Expected: form 제출·validation·navigation 기존 테스트가 모두 `PASS`.

## Task 5: RED-GREEN browser contract

**Files:**

- Modify: `frontend/appointment-frontend/e2e/patient-portal.spec.ts`
- Modify: `frontend/appointment-frontend/e2e/mobile-lazy-routes.spec.ts`

- [ ] **Step 1: keyboard/focus와 orientation 실패 시나리오를 먼저 추가한다**

  `patient-portal.spec.ts`에 320·375·393·430px에서 login input focus 후 `document.activeElement`의 bounding box가 visual viewport 안에 있고, submit action이 viewport 하단 Safe Area/keyboard padding 밖에 있는지 검사한다. `page.evaluate`에서 `visualViewport.height`를 600으로 정의한 뒤 `resize`를 발생시켜 CSS 변수 갱신도 확인한다. landscape(667×375)에서는 overflow가 false이고 nav/button touch height가 44px 이상인지 검사한다.

- [ ] **Step 2: RED를 확인한다**

  Run: `cd frontend/appointment-frontend && npm run test:e2e -- e2e/patient-portal.spec.ts e2e/mobile-lazy-routes.spec.ts`

  Expected: 새 selector 또는 CSS variable assertion이 기존 구현에서 `FAIL`한다.

- [ ] **Step 3: 구현 후 GREEN을 확인한다**

  Run: `cd frontend/appointment-frontend && npm run test:e2e -- e2e/patient-portal.spec.ts e2e/mobile-lazy-routes.spec.ts`

  Expected: 대상 browser contract가 모두 `PASS`; native device 테스트로 해석하지 않는다.

## Task 6: 문서·검토·검증 evidence

**Files:**

- Modify: `frontend/appointment-frontend/README.md`
- Modify: `frontend/appointment-frontend/README.ko.md`
- Create: `docs/lessons/2026-08-27-issue-26-safe-area-keyboard.md`
- Create: `docs/superpowers/reviews/2026-08-27-issue-26-safe-area-keyboard-implementation-review.ko.md`

- [ ] **Step 1: README와 lesson을 갱신한다**

  재사용한 Angular/Capacitor 경계, `100dvh`·`visualViewport`·Safe Area·focus scroll 계약, 실행 명령, native #27/#24 제외 범위를 Korean-only 정책에 맞게 기록한다.

- [ ] **Step 2: 7-Tier review를 기록한다**

  Performance/Correctness/Security/Operability/Developer·API/User 관점과 main integration을 최신 diff에 적용한다. P0=0, P1=0을 필수로 하고 P2/P3는 수정 또는 후속 이슈로 명시한다. Kotlin production/test와 `bluetape4k-assertions`는 변경 scope가 frontend TypeScript/SCSS뿐이라는 구체적 근거로 N/A를 기록한다.

- [ ] **Step 3: 로컬 전체 검증을 실행한다**

  ```bash
  cd frontend/appointment-frontend
  npm run test:bundle
  npm run build
  npm run bundle:verify
  npm test -- --watch=false
  npx tsc --noEmit -p tsconfig.app.json
  npm run test:e2e
  npm run docs:verify
  git diff --check
  ```

  Expected: 모든 명령이 성공하고 기존 docs contract failure가 새 diff와 무관하면 review/lesson에 정확히 기록한다.

- [ ] **Step 4: writer/terminology gate와 plan self-review를 통과한다**

  `audit-korean-terms.mjs` 및 Prettier check를 변경 문서/소스에 실행하고, `TBD|TODO` placeholder와 spec-to-plan 누락을 제거한다. 모든 SPW-01..05 결과를 review evidence에 남긴다.

## Task 7: workflow receipt·commit·stacked PR

**Files:**

- Modify: `.bluetape` via `bluetape-flow.py` only
- Update: live Issue #26 and PR body/metadata

- [ ] **Step 1: required workflow checks를 순서대로 기록한다**

  `spec`, `plan`, `module-build`, `module-unit`, `typescript`, `browser-e2e`, `review`, `diff-check` 결과를 현재 exact head로 `check-result`에 기록하고 component/lane evidence를 부착한다.

- [ ] **Step 2: Lore commit으로 변경을 저장하고 push한다**

  ```bash
  git diff --check
  git status --short
  git add <scoped-files>
  git commit -m $'모바일 Safe Area와 키보드 focus 계약을 보완한다\n\nConstraint: #26은 #431 위 stacked slice이며 native plugin과 device 검증은 후속 범위다.\nRejected: 페이지별 CSS 복제 | 공통 directive와 기존 shell 재사용이 누락을 줄인다.\nConfidence: high\nScope-risk: moderate\nDirective: visualViewport 계약 변경 시 browser/native 경계를 함께 검증한다.\nTested: frontend 전체 검증과 7-Tier review\nNot-tested: iOS/Android 실기기 IME 및 orientation'
  git push -u origin feat/issue-26-safe-area-keyboard
  ```

- [ ] **Step 3: PR #26을 #431 exact head 위에 만들고 live read-back한다**

  PR base는 `feat/issue-431-lazy-loading-webview`, head는 `feat/issue-26-safe-area-keyboard`로 고정한다. 제목/본문/label/assignee/milestone을 Issue #26과 맞추고 `## DoD Status`를 마지막 heading으로 둔다. PR은 merge하지 않으며 exact-head CI dispatch와 결과를 기다린다.

- [ ] **Step 4: Issue #26과 PR의 다음 slice handoff를 기록한다**

  Issue checklist, PR URL, exact head, CI URL, local evidence, native #27/#24 unchecked 항목을 live reread한다. 모든 조건이 충족되면 PR #26을 다음 #25의 base로 보류한다.

