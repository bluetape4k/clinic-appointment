# Angular 22 호환성 업그레이드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to execute this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Angular 22와 TypeScript 6.0의 peer 계약을 만족시켜 프런트엔드 설치 및 CI를 복구한다.

**Architecture:** 앱 동작이나 예약 도메인을 변경하지 않는다. `package.json`에서 Angular 런타임·도구·CDK/Material·TypeScript를 호환 버전으로 선언하고 npm이 `package-lock.json`을 재생성하게 한다. 최신 `develop` 기준선과 후보를 동일 명령으로 비교한다.

**Tech Stack:** Angular 22.0.8, Angular CDK/Material 22.0.6, TypeScript 6.0.3, npm 11, Node 26, Vitest.

---

### Task 1: 기준선과 패키지 계약 기록

**Files:**

- Read: `frontend/appointment-frontend/package.json`
- Read: `frontend/appointment-frontend/package-lock.json`

- [ ] **Step 1: 현재 기준선 설치와 검증을 기록한다**

Run from `frontend/appointment-frontend`:

```bash
npm ci
npm ls @angular/core @angular/build @angular/compiler-cli typescript --all
npm run build
npm test -- --watch=false
```

Expected: 설치·빌드는 성공한다. 테스트 실패가 있으면 파일/테스트 수와 첫 오류를 기록하여 후보의 새 실패와 구분한다.

- [ ] **Step 2: Angular 22의 peer 계약을 확인한다**

Run:

```bash
npm view @angular/build@22.0.8 peerDependencies engines --json
npm view @angular/cdk@22 version --json
npm view @angular/material@22 version --json
```

Expected: build가 Angular 22와 TypeScript 6.0을 요구하고 CDK/Material의 22.0.6 사용 가능성을 확인한다.

### Task 2: 호환 패키지군을 원자적으로 갱신

**Files:**

- Modify: `frontend/appointment-frontend/package.json`
- Modify: `frontend/appointment-frontend/package-lock.json`

- [ ] **Step 1: Angular 런타임과 UI 패키지를 22 호환선으로 설치한다**

Run from `frontend/appointment-frontend`:

```bash
npm install \
  @angular/animations@22.0.8 \
  @angular/common@22.0.8 \
  @angular/compiler@22.0.8 \
  @angular/core@22.0.8 \
  @angular/forms@22.0.8 \
  @angular/platform-browser@22.0.8 \
  @angular/router@22.0.8 \
  @angular/cdk@22.0.6 \
  @angular/material@22.0.6
```

Expected: `package.json`과 lockfile이 같은 Angular 22 호환선으로 갱신되고 peer dependency 오류가 없다.

- [ ] **Step 2: Angular 도구와 TypeScript를 22 호환선으로 설치한다**

Run:

```bash
npm install --save-dev \
  @angular/build@22.0.8 \
  @angular/cli@22.0.8 \
  @angular/compiler-cli@22.0.8 \
  typescript@~6.0.3
```

Expected: `@angular/build`가 요구하는 Angular 22 및 TypeScript 6.0 peer 계약을 충족한다.

- [ ] **Step 3: lockfile diff를 제한한다**

Run:

```bash
git diff --check
git diff -- frontend/appointment-frontend/package.json frontend/appointment-frontend/package-lock.json
```

Expected: 프런트엔드 의존성 선언과 lockfile 외의 변경은 없다. 컴파일러가 실제 오류를 낼 때만 오류 위치의 최소 앱/설정 파일을 추가한다.

### Task 3: 프런트엔드 호환성 검증

**Files:**

- Test: `frontend/appointment-frontend` npm scripts

- [ ] **Step 1: 깨끗한 설치와 의존성 트리를 검증한다**

Run:

```bash
rm -rf node_modules
npm ci
npm ls @angular/core @angular/build @angular/compiler @angular/compiler-cli @angular/cdk @angular/material typescript --all
```

Expected: npm peer dependency 오류가 없고 Angular 22 및 TypeScript 6.0 패키지가 하나의 호환 트리로 해석된다.

- [ ] **Step 2: 빌드와 테스트를 순차 실행한다**

Run:

```bash
npm run build
npm test -- --watch=false
```

Expected: 빌드는 성공한다. 테스트 결과를 Task 1 기준선과 비교해 새 실패가 없음을 증명한다. 새 실패가 있으면 오류를 재현하고 해당 소스/설정만 수정한 뒤 이 단계 전체를 다시 실행한다.

### Task 4: 전달 준비

**Files:**

- Create: `docs/lessons/2026-07-29-angular-peer-family-upgrade.md`
- Modify: `frontend/appointment-frontend/package.json`
- Modify: `frontend/appointment-frontend/package-lock.json`

- [ ] **Step 1: 재사용 가능한 교훈을 기록한다**

Document that Angular build, compiler, compiler-cli, runtime, and TypeScript must be upgraded as one peer-compatible family; individual Dependabot PRs are not mergeable when they break that contract.

- [ ] **Step 2: 변경을 Lore 커밋으로 만든다**

Run:

```bash
git add frontend/appointment-frontend/package.json frontend/appointment-frontend/package-lock.json docs/lessons/2026-07-29-angular-peer-family-upgrade.md
git commit
```

Expected: 커밋은 패키지 호환성 결정, 배제한 peer 우회, 검증 결과와 남은 테스트 기준선을 Lore trailers로 기록한다.

- [ ] **Step 3: 단일 대체 PR을 생성한다**

Push `feature/angular-22-consolidation` to `origin`, create an English PR to `develop`, assign `debop`, and end its body with `## DoD Status`. Do not close Dependabot PRs until exact-head CI, review, and fresh merge approval are complete.

## Self-review

- 수용 기준별 실행 단계: peer 설치(Task 2), clean install/tree(Task 3.1), build/test comparison(Task 3.2), single PR/CI(Task 4.3).
- 플레이스홀더 없음: 실제 패키지 버전, 파일, 명령, 기대 결과를 명시했다.
- 롤백: 모든 변경은 격리 브랜치에서 수행하며 CI 실패 시 병합하지 않는다.
