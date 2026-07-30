# 시각 동반 문서 이력 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 복잡한 예약 설계의 동작과 결정 이력을 영문·한글 self-contained HTML로 보존하고, 명시적 allowlist와 pinned commit snapshot을 통해 중앙 `bluetape4k.github.io` GitHub Pages에서 안전하게 탐색할 수 있게 한다.

**Architecture:** `clinic-appointment`가 Markdown 원본, locale별 HTML, presentation profile, validator를 소유한다. 중앙 사이트는 clinic 저장소의 merge된 SHA와 manifest를 명시적으로 동기화해 digest가 포함된 snapshot과 공개 HTML을 commit하며, 배포 시에는 외부 저장소를 fetch하지 않고 snapshot 무결성과 Astro build만 검증한다.

**Tech Stack:** Node.js built-ins (`node:test`, `fs`, `path`, `crypto`, `child_process`), self-contained HTML/CSS/JavaScript, GitHub Actions, Astro 6, Starlight 0.39, npm, GitHub Pages

---

## 실행 경계

- 작업 유형은 두 저장소 모두 `Type E - Maintenance`다.
- `clinic-appointment` head는 `docs/visual-companion-history`, base는 `develop`이다.
- `bluetape4k.github.io` head는 `docs/clinic-visual-companions`, base는 `develop`이다.
- 첫 공개 대상은 승인된 설계 HTML 2종만이다. 구현 계획 HTML 2종은 manifest에 넣지 않는다.
- 두 설계는 모두 `hybrid` profile을 사용하고 기본 view는 `simulation`이다.
- 기존 한국어 unsuffixed HTML 경로는 깨지지 않게 보존한다.
- 영문 동등본은 legacy-path 예외로 `*.en.html`을 사용한다.
- 중앙 사이트 route는 다음으로 고정한다.
  - English: `/visual-companions/clinic-appointment/{document-id}/`
  - 한국어: `/ko/visual-companions/clinic-appointment/{document-id}/`
- Kotlin production code, 예약 API 동작, DB/Flyway, release tag와 package publish는 변경하지 않는다.
- 각 PR은 현재 review thread와 CI가 모두 통과한 뒤 exact PR/head를 보고하고 별도의 merge 승인을 받는다. 이전의 설계·계획 승인은 merge 승인으로 간주하지 않는다.

## 최종 파일 지도

### `clinic-appointment`

| 파일 | 책임 |
|---|---|
| `docs/superpowers/specs/2026-07-29-visual-companion-history-design.md` | 승인 상태와 최종 locale/manifest 계약 |
| `docs/visual-companions/README.md` | contributor용 작성·공개·검증 규칙 |
| `docs/visual-companions/manifest.json` | 공개 가능한 문서와 locale 파일의 유일한 allowlist |
| `scripts/visual-companions/contract.mjs` | schema, path, presentation profile 순수 검증 함수 |
| `scripts/validate-visual-companions.mjs` | 저장소 파일과 HTML/Markdown 양방향 계약 검증 CLI |
| `tests/visual-companions/contract.test.mjs` | manifest/profile/path 단위 테스트 |
| `tests/visual-companions/validator.test.mjs` | fixture 기반 validator 회귀 테스트 |
| `.github/workflows/visual-companions.yml` | docs-only 변경에도 실행되는 전용 CI |
| `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html` | 한국어 hybrid companion |
| `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.en.html` | 영문 source-equivalent hybrid companion |
| `docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.html` | 한국어 hybrid companion |
| `docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.en.html` | 영문 source-equivalent hybrid companion |
| `README.md` | 영문 companion 진입점 |
| `README.ko.md` | 한국어 companion 진입점 |
| `docs/superpowers/INDEX.md` | Markdown 원본과 locale별 HTML 색인 |

### `bluetape4k.github.io`

| 파일 | 책임 |
|---|---|
| `src/data/visual-companions/repositories.json` | 허용 저장소, source ref, manifest 경로 registry |
| `src/data/visual-companions/clinic-appointment.snapshot.json` | source SHA, 문서 metadata, locale route, SHA-256 digest |
| `scripts/visual-companions/lib/repositories.mjs` | registry와 source-root 경계 검증 |
| `scripts/visual-companions/lib/manifest.mjs` | clinic manifest parsing과 공개 entry projection |
| `scripts/visual-companions/lib/snapshot.mjs` | allowlisted 파일 복사, route 생성, digest 계산 |
| `scripts/visual-companions/sync.mjs` | 명시적 local source와 merged SHA로 snapshot 갱신 |
| `scripts/visual-companions/validate-snapshot.mjs` | deploy 전 committed snapshot/asset 무결성 검증 |
| `tests/visual-companions/repositories.test.mjs` | registry/path containment 단위 테스트 |
| `tests/visual-companions/snapshot.test.mjs` | allowlist, locale route, digest 회귀 테스트 |
| `public/visual-companions/clinic-appointment/*/index.html` | 영문 공개 HTML snapshot |
| `public/ko/visual-companions/clinic-appointment/*/index.html` | 한국어 공개 HTML snapshot |
| `src/content/docs/visual-companions/clinic-appointment.mdx` | 영문 landing page |
| `src/content/docs/ko/visual-companions/clinic-appointment.mdx` | 한국어 landing page |
| `scripts/manual/lib/sidebar.mjs` | 로케일별 `Visual Companions` 탐색 항목 |
| `package.json` | sync와 snapshot validation 명령 |
| `.github/workflows/deploy.yml` | snapshot validation을 Pages build의 선행 gate로 실행 |

## Phase 1 — `clinic-appointment` source contract

### Task 0: 승인된 계획을 `$bluetape-workflow` Type E run으로 시작

**Files:**
- No repository source changes
- Runtime receipt: `/Users/debop/work/bluetape4k/.bluetape`

- [ ] 사용자가 선택한 실행 방식에 맞춰 Type E run을 초기화하고 현재 `docs/visual-companion-history` HEAD를 approval evidence에 기록한다.
- [ ] `manifest-contract`, `repository-validator`, `bilingual-companions`, `docs-navigation`, `docs-ci`, `clinic-delivery` component와 required check를 topology에 등록한다.
- [ ] 각 lane의 exact write scope를 mutation 전에 등록하고, 범위를 넓혀야 하면 receipt topology를 먼저 갱신한다.
- [ ] 실행 순서는 `run_created → plan_approved → run_started → lane_created → lane_started → startup_ack → topology_registered`를 지킨다.
- [ ] 완료 시 lane을 먼저 complete한 뒤 check result와 component evidence를 기록하고, `completion-check → complete → verify` 순서로 영수증을 닫는다.

### Task 1: 승인된 설계 계약을 실행 가능한 형태로 고정

**Files:**
- Modify: `docs/superpowers/specs/2026-07-29-visual-companion-history-design.md`
- Create: `docs/visual-companions/README.md`
- Create: `docs/visual-companions/manifest.json`

- [ ] 설계 문서의 상태를 `승인됨`으로 바꾸고 manifest 예제를 `locales.en`/`locales.ko` 구조로 교체한다.
- [ ] 설계 단계의 비목표와 승인된 delivery 범위를 구분해, clinic PR/merge와 별도 중앙 사이트 PR/merge/Pages 검증은 이 구현 계획이 통제한다고 명확히 한다.
- [ ] locale 계약에 “기존 한국어 unsuffixed 파일 유지 + 영문 `.en.html` 추가”를 명시하고, 신규 문서의 일반 규칙과 legacy 예외를 구분한다.
- [ ] `docs/visual-companions/README.md`에 source-of-truth, profile 선택 기준(`history`, `simulation`, `hybrid`), 공개 allowlist, 접근성, 개인정보 금지, 로컬 검증 명령을 기록한다.
- [ ] 아래 두 문서만 `public: true`로 manifest에 등록한다.

```json
{
  "schemaVersion": 1,
  "repository": "bluetape4k/clinic-appointment",
  "documents": [
    {
      "id": "appointment-plan-and-capacity",
      "source": "docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.md",
      "status": "approved",
      "public": true,
      "presentation": {
        "mode": "hybrid",
        "defaultView": "simulation",
        "views": ["simulation", "history"]
      },
      "locales": {
        "en": {
          "title": "Appointment Plan and Capacity",
          "html": "docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.en.html"
        },
        "ko": {
          "title": "예약 계획과 수용량",
          "html": "docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html"
        }
      }
    },
    {
      "id": "scheduling-policy-foundation",
      "source": "docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.md",
      "status": "approved",
      "public": true,
      "presentation": {
        "mode": "hybrid",
        "defaultView": "simulation",
        "views": ["simulation", "history"]
      },
      "locales": {
        "en": {
          "title": "Scheduling Policy Foundation",
          "html": "docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.en.html"
        },
        "ko": {
          "title": "Scheduling Policy 기반",
          "html": "docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.html"
        }
      }
    }
  ]
}
```

- [ ] JSON syntax와 변경 범위를 확인한다.

Run:

```bash
node -e "JSON.parse(require('node:fs').readFileSync('docs/visual-companions/manifest.json', 'utf8'))"
git diff --check
```

Expected: 두 명령 모두 exit code `0`; manifest에는 정확히 2개 document가 있다.

- [ ] Commit:

```bash
git add docs/superpowers/specs/2026-07-29-visual-companion-history-design.md \
  docs/visual-companions/README.md \
  docs/visual-companions/manifest.json
git commit -m "Make visual publication an explicit repository contract" \
  -m "Constraint: Existing Korean HTML paths must remain stable
Rejected: Discover all HTML under docs | It would publish internal plan artifacts
Confidence: high
Scope-risk: narrow
Directive: Add public companions only through the manifest
Tested: JSON parse and git diff check
Not-tested: HTML contract validator is added in the next task"
```

### Task 2: Manifest와 presentation profile을 TDD로 검증

**Files:**
- Create: `tests/visual-companions/contract.test.mjs`
- Create: `scripts/visual-companions/contract.mjs`

- [ ] RED: 다음 case를 `node:test`로 먼저 작성한다.
  - valid `hybrid` document passes
  - duplicate document `id` fails
  - unknown top-level key fails closed
  - unknown `presentation.mode` fails
  - `history`/`simulation` mode와 `views` 불일치가 실패한다
  - `hybrid`가 두 view 중 하나를 잃으면 실패한다
  - `defaultView`가 `views`에 없으면 실패한다
  - locale이 `en`과 `ko`를 모두 갖지 않으면 실패한다
  - absolute path, `..`, repository 밖 path가 실패한다
  - manifest repository가 `bluetape4k/clinic-appointment`가 아니면 실패한다
- [ ] 테스트가 구현 모듈 부재로 실패하는지 확인한다.

Run:

```bash
node --test tests/visual-companions/contract.test.mjs
```

Expected: `ERR_MODULE_NOT_FOUND` 또는 명시한 missing export로 non-zero exit.

- [ ] GREEN: `scripts/visual-companions/contract.mjs`에 side effect 없는 다음 export를 구현한다.
  - `validateManifest(manifest)`
  - `validatePresentation(presentation)`
  - `validateRepositoryRelativePath(value, field)`
- [ ] 허용 key, locale, mode, view를 고정된 `Set`으로 선언하고 unknown 값은 오류로 처리한다.
- [ ] 오류는 document index와 field path를 포함한 `Error`로 반환해 CI에서 원인을 바로 찾게 한다.
- [ ] 단위 테스트를 다시 실행한다.

Run:

```bash
node --test tests/visual-companions/contract.test.mjs
```

Expected: 모든 subtest가 pass하고 exit code `0`.

- [ ] Commit:

```bash
git add scripts/visual-companions/contract.mjs \
  tests/visual-companions/contract.test.mjs
git commit -m "Fail closed when visual publication metadata drifts" \
  -m "Constraint: The validator must run without adding a repository dependency
Rejected: JSON Schema package | Node built-ins are sufficient for the small contract
Confidence: high
Scope-risk: narrow
Directive: Keep unknown modes, views, locales, and keys rejected
Tested: node contract tests
Not-tested: Repository file and HTML content checks are added next"
```

### Task 3: Repository 파일과 양방향 링크 validator를 TDD로 구현

**Files:**
- Create: `tests/visual-companions/validator.test.mjs`
- Create: `scripts/validate-visual-companions.mjs`

- [ ] RED: 임시 fixture 저장소를 만드는 integration test를 작성한다.
  - valid bilingual hybrid pair passes
  - missing source or locale HTML fails
  - `#history` or `#simulation` anchor missing fails
  - hybrid view 간 양방향 anchor link가 없으면 실패한다
  - Markdown → 각 locale HTML 링크 누락이 실패한다
  - HTML → Markdown backlink 누락이 실패한다
  - `<html lang="en">`/`<html lang="ko">` 불일치가 실패한다
  - status, source, baseline commit을 포함한 provenance block 누락이 실패한다
  - external script/style/font, analytics, form, `fetch`, `XMLHttpRequest`, WebSocket이 실패한다
  - manifest에 없는 HTML이 검사 대상이나 publication 대상으로 자동 추가되지 않는다
- [ ] validator import가 아직 없어 실패하는지 확인한다.

Run:

```bash
node --test tests/visual-companions/validator.test.mjs
```

Expected: missing module로 non-zero exit.

- [ ] GREEN: CLI와 재사용 가능한 `validateRepository(root)` export를 구현한다.
- [ ] manifest의 `source`와 locale별 `html`만 검사하고 repository glob으로 공개 파일을 찾지 않는다.
- [ ] realpath containment와 허용 root `docs/superpowers/specs/`를 모두 검사한다.
- [ ] anchor, backlink, lang, provenance, 금지 network surface를 정적 text 검사로 검증한다.
- [ ] CLI가 오류를 stderr에 한 줄씩 출력하고 non-zero exit하며, 성공 시 document/locale count를 출력하게 한다.
- [ ] fixture test를 통과시킨다.

Run:

```bash
node --test tests/visual-companions/validator.test.mjs
```

Expected: 모든 subtest pass.

- [ ] 실제 manifest는 아직 companion 보강 전이므로 validator가 선언된 두 문서의 현재 계약 차이를 보고하는지 확인한다.

Run:

```bash
node scripts/validate-visual-companions.mjs
```

Expected: non-zero exit. 오류는 두 `.en.html` 파일 부재와 기존 한국어 HTML/Markdown의 anchor, provenance, locale link 차이에 한정되고 manifest schema/path 자체는 통과해야 한다.

- [ ] Commit:

```bash
git add scripts/validate-visual-companions.mjs \
  tests/visual-companions/validator.test.mjs
git commit -m "Detect broken visual history before it is published" \
  -m "Constraint: Publication must be validated from the repository clone
Rejected: Browser-only validation | It cannot prove allowlist and backlink integrity
Confidence: high
Scope-risk: narrow
Directive: Keep publication discovery manifest-only
Tested: node validator fixture tests and expected missing-English failure
Not-tested: Real companion files are completed in the next tasks"
```

## Phase 2 — Bilingual hybrid companions

### Task 4: 예약 계획·수용량 companion을 hybrid로 보강

**Files:**
- Modify: `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html`
- Create: `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.en.html`
- Modify: `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.md`

- [ ] 한국어 HTML의 기존 내용을 보존하면서 semantic section `id="simulation"`과 `id="history"`를 추가한다.
- [ ] `simulation`에는 환자 요청, clinic capacity, hold/confirm/release 결과를 입력-판정-상태 흐름으로 보여준다.
- [ ] `history`에는 Issue → 설계 → 구현 계획 → API/운영 문서 연결을 날짜순으로 보여준다. 존재하지 않는 Issue/PR 번호는 만들지 않고 repo 내부 문서와 commit link만 사용한다.
- [ ] 두 section에 서로를 향하는 `href="#history"`와 `href="#simulation"` navigation을 둔다.
- [ ] 화면 상단 provenance에 status `approved`, 원본 Markdown 상대 링크, 설계 기준일, 원본 HTML baseline commit `e3ae0ce`를 표시한다.
- [ ] keyboard focus, `prefers-reduced-motion`, print stylesheet, 좁은 viewport를 지원한다.
- [ ] 동일한 정보 구조와 technical identifier를 유지하는 영문 동등본을 작성하고 `<html lang="en">`을 사용한다.
- [ ] Markdown 원본에서 한국어와 영문 HTML을 모두 링크한다.
- [ ] validator를 실행한다. 두 번째 문서의 영문 파일만 아직 없다는 오류만 남아야 한다.

Run:

```bash
node scripts/validate-visual-companions.mjs
```

Expected: appointment pair 관련 오류는 `0`; 남은 오류는 아직 수정하지 않은 scheduling policy의 영문 파일 부재와 한국어 anchor/provenance/link 차이에 한정된다.

- [ ] Commit:

```bash
git add docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.md \
  docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html \
  docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.en.html
git commit -m "Explain appointment capacity through behavior and history" \
  -m "Constraint: The existing Korean path is already referenced externally
Rejected: Replace the Korean file with English | It would break established readers
Confidence: high
Scope-risk: narrow
Directive: Keep both locale variants source-equivalent
Tested: visual companion validator for the appointment pair
Not-tested: Browser visual review runs after both pairs are complete"
```

### Task 5: Scheduling Policy companion을 hybrid로 보강

**Files:**
- Modify: `docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.html`
- Create: `docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.en.html`
- Modify: `docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.md`

- [ ] 한국어 HTML에 `simulation`과 `history` semantic section 및 양방향 navigation을 추가한다.
- [ ] `simulation`에는 tenant default, clinic override, effective policy, version/snapshot 결과를 동일 입력에 대한 비교 흐름으로 보여준다.
- [ ] `history`에는 설계 기준, 구현 계획, 후속 검증 문서와 baseline commit `9008d3e`의 관계를 표시한다.
- [ ] provenance, keyboard focus, reduced motion, print, narrow viewport 계약을 적용한다.
- [ ] source-equivalent 영문 동등본과 Markdown 양 locale 링크를 추가한다.
- [ ] 전체 contract와 repository validator를 통과시킨다.

Run:

```bash
node --test tests/visual-companions/*.test.mjs
node scripts/validate-visual-companions.mjs
git diff --check
```

Expected: 모든 test pass, validator가 `2 documents / 4 locale files` 성공을 출력, diff check exit `0`.

- [ ] Commit:

```bash
git add docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.md \
  docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.html \
  docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.en.html
git commit -m "Connect scheduling policy outcomes to their rationale" \
  -m "Constraint: Policy composition and historical snapshots must be understandable together
Rejected: Timeline-only presentation | It would not explain runtime policy outcomes
Confidence: high
Scope-risk: narrow
Directive: Preserve the bidirectional simulation and history navigation
Tested: node tests, repository validator, and diff check
Not-tested: Browser viewport review runs in the final clinic verification"
```

### Task 6: README와 문서 색인을 locale별 companion으로 연결

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `docs/superpowers/INDEX.md`

- [ ] `README.md`는 두 영문 `.en.html`을 독자용 companion으로 연결한다.
- [ ] `README.ko.md`는 기존 한국어 unsuffixed HTML 링크를 유지한다.
- [ ] `INDEX.md`에 이번 설계·구현 계획과 함께 원본 Markdown, English HTML, 한국어 HTML, profile, 공개 상태를 분리해 표시한다.
- [ ] 상대 링크가 실제 파일을 가리키는지 validator와 명시적 link scan으로 확인한다.

Run:

```bash
node scripts/validate-visual-companions.mjs
rg -n "appointment-plan-and-capacity-design(\\.en)?\\.html|scheduling-policy-foundation-design(\\.en)?\\.html" \
  README.md README.ko.md docs/superpowers/INDEX.md
git diff --check
```

Expected: validator pass; 세 문서에서 의도한 locale link가 보임; diff check exit `0`.

- [ ] Commit:

```bash
git add README.md README.ko.md docs/superpowers/INDEX.md
git commit -m "Route each reader to the matching visual companion" \
  -m "Constraint: Reader-facing documentation must remain source-equivalent across locales
Rejected: Link both READMEs to Korean assets | It would make the English surface incomplete
Confidence: high
Scope-risk: narrow
Directive: Keep locale links paired when companions change
Tested: repository validator, link scan, and diff check
Not-tested: Central Pages routes are introduced after clinic merge"
```

### Task 7: docs-only 변경을 검증하는 전용 CI 추가

**Files:**
- Create: `.github/workflows/visual-companions.yml`

- [ ] workflow path filter에 manifest, companion HTML/Markdown, validator, tests, README, INDEX를 포함한다.
- [ ] Node 26을 설정하고 dependency install 없이 다음을 순서대로 실행한다.

```bash
node --test tests/visual-companions/*.test.mjs
node scripts/validate-visual-companions.mjs
git diff --check origin/develop...HEAD
```

- [ ] workflow permission은 `contents: read`만 허용하고 PR/push에서 실행한다.
- [ ] `actions/checkout`은 `fetch-depth: 0`으로 base ref를 포함해 전체 branch diff check가 가능하게 한다.
- [ ] 로컬에서 workflow syntax와 전체 검사 세트를 실행한다.

Run:

```bash
actionlint .github/workflows/visual-companions.yml
node --test tests/visual-companions/*.test.mjs
node scripts/validate-visual-companions.mjs
git diff --check develop...HEAD
```

Expected: 모두 exit code `0`.

- [ ] Commit:

```bash
git add .github/workflows/visual-companions.yml
git commit -m "Keep documentation-only visual changes inside CI" \
  -m "Constraint: The main CI intentionally ignores docs-only changes
Rejected: Broaden the full application CI path filter | It would run unrelated Kotlin builds
Confidence: high
Scope-risk: narrow
Directive: Keep this workflow dependency-free and docs-scoped
Tested: actionlint, node tests, repository validator, and diff check
Not-tested: GitHub-hosted execution runs after push"
```

### Task 8: `clinic-appointment` 시각 검토, push, PR

**Files:**
- Review only: all files changed in Tasks 1–7

- [ ] working tree와 commit 범위를 확인한다.

Run:

```bash
repo-status
git log --oneline develop..HEAD
repo-diff develop...HEAD
```

Expected: 의도한 docs/tooling/workflow 파일만 존재하고 Kotlin/API/DB 변경이 없다.

- [ ] 두 locale × 두 문서를 local HTTP server에서 desktop `1440×1000`과 narrow `390×844`로 연다.
- [ ] 다음 server와 URL을 사용하고 browser screenshot은 검토 증거로만 보관하며 저장소에는 commit하지 않는다.

Run:

```bash
python3 -m http.server 8000 --bind 127.0.0.1 --directory .
```

Open:

```text
http://127.0.0.1:8000/docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.en.html
http://127.0.0.1:8000/docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html
http://127.0.0.1:8000/docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.en.html
http://127.0.0.1:8000/docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.html
```

- [ ] 각 화면에서 overflow, keyboard navigation, `#simulation`/`#history`, Markdown backlink, print preview를 확인한다.
- [ ] 검사 결과를 `docs/visual-companions/README.md`의 contributor checklist와 대조하고 발견한 결함을 수정한 뒤 Task 7 검증을 다시 실행한다.
- [ ] remote branch를 push한다.

Run:

```bash
git push -u origin docs/visual-companion-history
```

Expected: `origin/docs/visual-companion-history`가 local HEAD와 동일하다.

- [ ] English PR을 생성한다.

```bash
gh pr create \
  --repo bluetape4k/clinic-appointment \
  --base develop \
  --head docs/visual-companion-history \
  --title "Publish validated visual companions for appointment designs" \
  --body-file /tmp/clinic-visual-companion-pr.md
```

PR body는 Summary, Scope, Validation, Visual QA, Publication Contract, `## DoD Status` 순서로 작성하고 `## DoD Status`를 마지막 section으로 둔다.

- [ ] 생성된 PR URL을 네 locale HTML의 provenance `Related change`에 추가하고, validator/test/actionlint를 다시 실행한 뒤 Lore commit으로 push한다.
- [ ] PR URL을 추가한 commit의 메시지는 intent line `Connect published visual history to its review record`를 사용하고 `Tested:` trailer에 전체 docs validation을 기록한다.
- [ ] linked issue가 있으면 assignee, milestone, labels를 동일하게 맞춘다. linked issue가 없으면 존재하지 않는 metadata를 만들지 않는다.
- [ ] `gh pr view --json body,headRefName,baseRefName,assignees,labels,milestone,statusCheckRollup`으로 live body와 metadata를 재검증한다.

### Task 9: `clinic-appointment` merge gate와 local sync

**Files:**
- No source changes unless review feedback requires them

- [ ] CI, review, unresolved thread를 확인하고 feedback이 있으면 같은 branch에서 수정·재검증·push한다.
- [ ] `gh pr checks docs/visual-companion-history --repo bluetape4k/clinic-appointment --watch --fail-fast`로 현재 head의 필수 check를 끝까지 확인한다.
- [ ] exact PR number, head SHA, passing checks, review/thread 상태를 사용자에게 merge-ready로 보고한다.
- [ ] **STOP:** `clinic-appointment` PR merge에 대한 새 명시적 승인을 받기 전에는 merge하지 않는다.
- [ ] 승인 후 PR을 merge하고 상태를 확인한다.

```bash
gh pr merge docs/visual-companion-history \
  --repo bluetape4k/clinic-appointment \
  --merge
gh pr view docs/visual-companion-history \
  --repo bluetape4k/clinic-appointment \
  --json state,mergedAt,mergeCommit
```

Expected: state `MERGED`와 merge commit SHA가 표시된다.

- [ ] root checkout의 `develop`을 fast-forward하고 remote parity를 확인한다.

```bash
git -C /Users/debop/work/bluetape4k/clinic-appointment pull --ff-only origin develop
git -C /Users/debop/work/bluetape4k/clinic-appointment rev-list --left-right --count develop...origin/develop
```

Expected: `0 0`.

- [ ] 다음 phase의 pinned source ref를 merge된 `origin/develop` SHA로 기록한다.

```bash
CLINIC_SOURCE_REF="$(git -C /Users/debop/work/bluetape4k/clinic-appointment rev-parse origin/develop)"
printf '%s\n' "$CLINIC_SOURCE_REF"
```

Expected: 40-character merged commit SHA. 이후 중앙 registry와 sync 명령은 이 값을 사용한다.

## Phase 3 — Central `bluetape4k.github.io` publication

### Task 10: 중앙 사이트 작업을 별도 worktree로 격리

**Files:**
- Create worktree: `/Users/debop/work/bluetape4k/bluetape4k.github.io/.worktrees/docs-clinic-visual-companions`

- [ ] 중앙 저장소가 clean이고 `develop...origin/develop`이 `0 0`인지 확인한다.
- [ ] `docs/clinic-visual-companions` branch가 remote/local에 없는지 확인한다.
- [ ] base를 갱신하고 worktree를 만든다.

```bash
git -C /Users/debop/work/bluetape4k/bluetape4k.github.io fetch origin
git -C /Users/debop/work/bluetape4k/bluetape4k.github.io pull --ff-only origin develop
git -C /Users/debop/work/bluetape4k/bluetape4k.github.io worktree add \
  /Users/debop/work/bluetape4k/bluetape4k.github.io/.worktrees/docs-clinic-visual-companions \
  -b docs/clinic-visual-companions develop
```

Expected: 새 worktree가 clean `docs/clinic-visual-companions` branch를 가리킨다.

- [ ] 이 phase에 대해 `bluetape-workflow` Type E run을 새로 만들고 중앙 저장소의 파일 범위와 검증 topology를 등록한다.

### Task 11: 중앙 registry와 snapshot 경계를 TDD로 고정

**Files:**
- Create: `src/data/visual-companions/repositories.json`
- Create: `tests/visual-companions/repositories.test.mjs`
- Create: `scripts/visual-companions/lib/repositories.mjs`
- Modify: `.gitignore` only if an existing rule hides the required committed snapshot path

- [ ] RED: registry loader test를 먼저 작성한다.
  - exact repository `bluetape4k/clinic-appointment` passes
  - source ref는 40-character lowercase Git SHA만 허용한다
  - manifest path는 repository-relative이며 `..`와 absolute path를 거부한다
  - duplicate repository와 unknown key를 거부한다
  - `sourceRoot`는 registry에 저장하지 않고 sync CLI argument로만 받는다
- [ ] missing module 실패를 확인한다.

Run:

```bash
node --test --test-name-pattern="visual companion repository" \
  tests/visual-companions/repositories.test.mjs
```

Expected: missing module로 non-zero exit.

- [ ] GREEN: manual publication의 registry/path-containment 관례를 재사용하되 시각 동반 문서 전용 module로 구현한다.
- [ ] registry에 Task 9에서 출력한 `CLINIC_SOURCE_REF` 값과 `docs/visual-companions/manifest.json`을 기록한다.
- [ ] test를 통과시킨다.

Run:

```bash
node --test --test-name-pattern="visual companion repository" \
  tests/visual-companions/repositories.test.mjs
```

Expected: 관련 test pass.

- [ ] Commit:

```bash
git add src/data/visual-companions/repositories.json \
  scripts/visual-companions/lib/repositories.mjs \
  tests/visual-companions/repositories.test.mjs
git commit -m "Pin visual publication to an reviewed source revision" \
  -m "Constraint: Central deploys must not consume a moving branch
Rejected: Fetch origin/develop during Pages build | It would make deploys non-reproducible
Confidence: high
Scope-risk: narrow
Directive: Update source refs only through an intentional sync commit
Tested: visual companion repository tests
Not-tested: Snapshot copying and digest validation are added next"
```

### Task 12: Allowlisted HTML snapshot sync를 TDD로 구현

**Files:**
- Create: `tests/visual-companions/snapshot.test.mjs`
- Create: `scripts/visual-companions/lib/manifest.mjs`
- Create: `scripts/visual-companions/lib/snapshot.mjs`
- Create: `scripts/visual-companions/sync.mjs`
- Create: `scripts/visual-companions/validate-snapshot.mjs`
- Create: `src/data/visual-companions/clinic-appointment.snapshot.json`
- Create: `public/visual-companions/clinic-appointment/appointment-plan-and-capacity/index.html`
- Create: `public/visual-companions/clinic-appointment/scheduling-policy-foundation/index.html`
- Create: `public/ko/visual-companions/clinic-appointment/appointment-plan-and-capacity/index.html`
- Create: `public/ko/visual-companions/clinic-appointment/scheduling-policy-foundation/index.html`

- [ ] RED: fixture repository로 다음을 검증한다.
  - manifest의 `public: true` 두 document와 locale 파일만 복사한다
  - manifest 밖 HTML은 무시한다
  - English/Korean route가 고정 규칙으로 생성된다
  - source root의 HEAD가 registry SHA와 다르면 실패한다
  - source path symlink escape와 destination traversal이 실패한다
  - snapshot digest가 asset 변경 시 실패한다
  - stale destination asset이 남으면 validation이 실패한다
- [ ] missing implementation 실패를 확인한다.

Run:

```bash
node --test --test-name-pattern="visual companion snapshot" \
  tests/visual-companions/snapshot.test.mjs
```

Expected: non-zero exit.

- [ ] GREEN: `sync.mjs`가 `--source-root`와 `--source-ref`를 필수로 받고 registry SHA와 local Git HEAD를 모두 검증하게 한다.
- [ ] manifest projection에서 중앙 사이트가 이해하는 field만 snapshot에 기록하고 unknown source metadata는 통과시키지 않는다.
- [ ] 각 copied byte의 SHA-256, source path, locale, public route를 snapshot에 기록한다.
- [ ] sync 전 시각 동반 문서 destination만 bounded cleanup하고, allowlist 밖 `public/` 파일은 절대 건드리지 않는다.
- [ ] `validate-snapshot.mjs`는 network나 clinic checkout 없이 committed JSON과 public file만 검증한다.
- [ ] fixture test를 통과시킨다.
- [ ] 실제 merged clinic source로 snapshot을 생성한다.

Run:

```bash
CLINIC_SOURCE_REF="$(git -C /Users/debop/work/bluetape4k/clinic-appointment rev-parse origin/develop)"
node scripts/visual-companions/sync.mjs \
  --repository bluetape4k/clinic-appointment \
  --source-root /Users/debop/work/bluetape4k/clinic-appointment \
  --source-ref "$CLINIC_SOURCE_REF"
node scripts/visual-companions/validate-snapshot.mjs
```

Expected: `2 documents / 4 locale assets`를 복사하고 snapshot validation pass.

- [ ] Commit:

```bash
git add scripts/visual-companions \
  tests/visual-companions \
  src/data/visual-companions/clinic-appointment.snapshot.json \
  public/visual-companions/clinic-appointment \
  public/ko/visual-companions/clinic-appointment
git commit -m "Publish only reviewed companion bytes from clinic history" \
  -m "Constraint: Pages builds must remain offline and reproducible
Rejected: Clone service repositories during deploy | It couples availability to external fetches
Confidence: high
Scope-risk: moderate
Directive: Regenerate snapshots only from a clean checkout at the pinned SHA
Tested: snapshot tests, real sync, and offline snapshot validation
Not-tested: Astro navigation and full site build are added next"
```

### Task 13: 영문·한글 landing page와 navigation 추가

**Files:**
- Create: `src/content/docs/visual-companions/clinic-appointment.mdx`
- Create: `src/content/docs/ko/visual-companions/clinic-appointment.mdx`
- Modify: `scripts/manual/lib/sidebar.mjs`

- [ ] 영문 landing page에 두 design의 설명, `hybrid` 의미, 영문 공개 route, Markdown source repository link를 추가한다.
- [ ] 한국어 page에 source-equivalent 설명과 한국어 route를 추가한다.
- [ ] 정적 사이드바 생성기에 로케일별 `Visual Companions`/`시각 자료` 섹션을 추가하고 각 랜딩 페이지를 연결한다.
- [ ] 기존 Start/Ecosystem/Manuals/Blog navigation 순서와 locale prefix를 깨지 않는다.
- [ ] content와 sidebar 관련 기존 test를 실행한다.

Run:

```bash
node --test tests/manual/*.test.mjs
npm run check:manual
```

Expected: 기존 manual/content 검증과 새 navigation 검증이 모두 pass.

- [ ] Commit:

```bash
git add src/content/docs/visual-companions/clinic-appointment.mdx \
  src/content/docs/ko/visual-companions/clinic-appointment.mdx \
  scripts/manual/lib/sidebar.mjs
git commit -m "Give clinic visual history a bilingual site entry point" \
  -m "Constraint: Visual assets need stable discovery inside the existing locale navigation
Rejected: Expose raw public files without landing pages | Readers would lose context and provenance
Confidence: high
Scope-risk: narrow
Directive: Keep English and Korean navigation source-equivalent
Tested: sidebar, content, and manual checks
Not-tested: Full production build runs in the next task"
```

### Task 14: Offline snapshot validation을 Pages deploy gate에 연결

**Files:**
- Modify: `package.json`
- Modify: `.github/workflows/deploy.yml`

- [ ] 다음 npm scripts를 추가한다.

```json
{
  "sync:visual-companions": "node scripts/visual-companions/sync.mjs",
  "check:visual-companions": "node scripts/visual-companions/validate-snapshot.mjs"
}
```

- [ ] `test` 또는 deploy test 단계가 `tests/visual-companions/*.test.mjs`를 포함하는지 확인하고 누락 시 포함한다.
- [ ] deploy workflow에서 `npm run check:visual-companions`를 `npm run build` 전에 실행한다.
- [ ] deploy workflow는 clinic 저장소를 checkout/fetch하지 않고 committed snapshot만 사용하게 유지한다.
- [ ] 전체 validation과 production build를 실행한다.

Run:

```bash
actionlint .github/workflows/deploy.yml
npm test
npm run check:manual
npm run check:visual-companions
npm run build
git diff --check
```

Expected: 모두 exit code `0`; `dist/visual-companions/clinic-appointment/...`와 `dist/ko/visual-companions/clinic-appointment/...`에 각 2개 `index.html`이 존재한다.

- [ ] 생성 route count를 명시적으로 확인한다.

Run:

```bash
find dist/visual-companions/clinic-appointment \
  dist/ko/visual-companions/clinic-appointment \
  -name index.html -print | sort
```

Expected: 정확히 4개 companion route.

- [ ] Commit:

```bash
git add package.json .github/workflows/deploy.yml
git commit -m "Block Pages deploys when visual snapshots lose integrity" \
  -m "Constraint: Production builds must be reproducible without service-repository network access
Rejected: Sync during deploy | A moving external checkout would bypass reviewed snapshot bytes
Confidence: high
Scope-risk: moderate
Directive: Validate committed snapshots before every Astro build
Tested: actionlint, npm tests, manual check, snapshot check, production build, route count, and diff check
Not-tested: GitHub Pages production URL is verified after merge"
```

### Task 15: 중앙 사이트 시각 검토, push, PR

**Files:**
- Review only: all files changed in Tasks 11–14

- [ ] production build 결과를 local preview로 열고 다음 route를 desktop과 narrow viewport에서 검토한다.
- [ ] preview는 다음 command로 loopback에만 연다.

Run:

```bash
npm run preview -- --host 127.0.0.1 --port 4321
```

  - `/visual-companions/clinic-appointment/`
  - `/ko/visual-companions/clinic-appointment/`
  - `/visual-companions/clinic-appointment/appointment-plan-and-capacity/`
  - `/ko/visual-companions/clinic-appointment/appointment-plan-and-capacity/`
  - `/visual-companions/clinic-appointment/scheduling-policy-foundation/`
  - `/ko/visual-companions/clinic-appointment/scheduling-policy-foundation/`
- [ ] sidebar locale 전환, landing-to-companion link, companion internal anchors, source backlink, overflow와 keyboard focus를 확인한다.
- [ ] 변경 범위와 source SHA/digest를 검토한다.

Run:

```bash
repo-status
git log --oneline develop..HEAD
repo-diff develop...HEAD
node scripts/visual-companions/validate-snapshot.mjs
```

Expected: 중앙 site publication 파일만 변경되고 snapshot source SHA가 clinic `origin/develop` SHA와 일치한다.

- [ ] remote branch를 push한다.

```bash
git push -u origin docs/clinic-visual-companions
```

Expected: remote head와 local HEAD가 일치한다.

- [ ] English PR을 생성한다.

```bash
gh pr create \
  --repo bluetape4k/bluetape4k.github.io \
  --base develop \
  --head docs/clinic-visual-companions \
  --title "Publish clinic appointment visual companions" \
  --body-file /tmp/site-clinic-visual-companion-pr.md
```

PR body는 Summary, Pinned Source, Routes, Validation, Visual QA, Rollback, `## DoD Status` 순서로 작성하고 마지막 section을 유지한다.

- [ ] issue metadata가 있으면 assignee/milestone/labels를 맞추고, live PR body와 status checks를 다시 읽어 검증한다.

### Task 16: 중앙 PR merge와 production Pages 검증

**Files:**
- No source changes unless review feedback requires them

- [ ] CI, preview artifact, review, unresolved thread를 확인하고 feedback 수정 후 전체 Task 14 검증을 다시 실행한다.
- [ ] exact PR number, head SHA, pinned clinic SHA, passing checks, review/thread 상태, 예상 production route를 사용자에게 merge-ready로 보고한다.
- [ ] **STOP:** `bluetape4k.github.io` PR merge에 대한 새 명시적 승인을 받기 전에는 merge하지 않는다.
- [ ] 승인 후 merge하고 root `develop`을 fast-forward한다.

```bash
gh pr merge docs/clinic-visual-companions \
  --repo bluetape4k/bluetape4k.github.io \
  --merge
git -C /Users/debop/work/bluetape4k/bluetape4k.github.io pull --ff-only origin develop
git -C /Users/debop/work/bluetape4k/bluetape4k.github.io rev-list \
  --left-right --count develop...origin/develop
```

Expected: PR state `MERGED`; branch parity `0 0`.

- [ ] merge commit을 포함한 Pages deploy workflow가 success가 될 때까지 상태를 확인한다.

```bash
SITE_MERGE_SHA="$(gh pr view docs/clinic-visual-companions \
  --repo bluetape4k/bluetape4k.github.io \
  --json mergeCommit \
  --jq '.mergeCommit.oid')"
PAGES_RUN_ID="$(gh run list \
  --repo bluetape4k/bluetape4k.github.io \
  --workflow deploy.yml \
  --branch develop \
  --commit "$SITE_MERGE_SHA" \
  --json databaseId \
  --jq '.[0].databaseId')"
gh run watch "$PAGES_RUN_ID" \
  --repo bluetape4k/bluetape4k.github.io \
  --exit-status
```

Expected: 대상 merge commit의 deploy run이 terminal conclusion `success`로 끝난다.

- [ ] production URL을 직접 열어 HTTP success와 핵심 marker를 검증한다.

```bash
curl --fail --silent --show-error \
  https://bluetape4k.github.io/visual-companions/clinic-appointment/ \
  | rg "Clinic Appointment"
curl --fail --silent --show-error \
  https://bluetape4k.github.io/ko/visual-companions/clinic-appointment/ \
  | rg "예약"
curl --fail --silent --show-error \
  https://bluetape4k.github.io/visual-companions/clinic-appointment/appointment-plan-and-capacity/ \
  | rg 'id="simulation"|id="history"'
curl --fail --silent --show-error \
  https://bluetape4k.github.io/ko/visual-companions/clinic-appointment/scheduling-policy-foundation/ \
  | rg 'id="simulation"|id="history"'
```

Expected: 네 URL 모두 HTTP success이며 locale landing marker와 hybrid anchor가 검출된다.

- [ ] production desktop/narrow browser에서 locale navigation과 두 companion을 최종 확인한다.
- [ ] clinic source SHA, central snapshot digest, 두 PR merge commit, Pages run URL, production URL을 최종 보고한다.
- [ ] 두 저장소의 임시 worktree는 clean과 branch merge 상태를 확인한 뒤 안전하게 제거한다. root checkout이나 unrelated worktree는 제거하지 않는다.

## 최종 Definition of Done

- [ ] `clinic-appointment` manifest는 정확히 2개 공개 설계와 4개 locale HTML만 허용한다.
- [ ] 두 companion은 `hybrid`, default `simulation`, `#simulation`/`#history` 양방향 navigation을 갖는다.
- [ ] Markdown ↔ locale HTML backlink, provenance, lang, offline/security 계약이 validator로 검증된다.
- [ ] clinic docs-only CI가 GitHub에서 pass한다.
- [ ] clinic PR은 fresh merge approval 후 merge되고 `develop...origin/develop`이 `0 0`이다.
- [ ] 중앙 snapshot은 clinic merge SHA에 고정되고 4개 asset digest를 기록한다.
- [ ] central deploy는 외부 clinic fetch 없이 snapshot validation과 Astro build를 통과한다.
- [ ] 중앙 PR은 fresh merge approval 후 merge되고 Pages deploy가 success다.
- [ ] 2개 landing page와 4개 companion production route가 HTTP와 실제 browser에서 검증된다.
- [ ] Kotlin/API/DB/release artifact에는 변경이 없다.
