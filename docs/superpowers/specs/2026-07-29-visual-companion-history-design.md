# 예약 서비스 시각 동반 문서 이력 설계

- **날짜**: 2026-07-29
- **상태**: 승인됨
- **작업 유형**: Type E - Maintenance
- **대상**: 설계·구현 계획·운영 문서의 이해를 돕는 독립 실행형 HTML
- **기준 저장소**: `clinic-appointment`

## 1. 문제

예약 서비스의 설계 문서는 상태 전이, 행위자 권한, tenant·clinic 정책 합성,
동시성 제어, 장애 복구가 서로 연결된다. Markdown은 결정과 근거를 정확히
보존하는 데 적합하지만 다음 내용을 한눈에 전달하기 어렵다.

- 환자 예약과 관리자 직접 예약이 어디에서 갈라지는가
- 가예약, 확정, 취소, 재조정이 어떤 조건으로 전이되는가
- tenant 기본 정책과 clinic override가 어떤 결과를 만드는가
- 정책 version과 스냅숏이 과거 예약 결정에 어떻게 연결되는가
- 설계, 구현 계획, 실제 구현, 운영 복구가 어느 Issue와 PR에서 바뀌었는가

설계 중 사용하는 브라우저 시각 동반 문서는 대화를 돕는 임시 화면이다. 이를
그대로 장기 문서로 취급하면 기준 문서, 공개 범위, 로케일, 변경 이력과 검증
책임이 불명확해진다.

## 2. 목표

1. Markdown을 설계와 결정의 원본으로 유지한다.
2. 승인된 설계에서 이해 비용이 높은 흐름을 독립 실행형 HTML로 설명한다.
3. HTML을 Issue, PR, commit, API, runbook과 연결해 개발 이력을 탐색할 수 있게 한다.
4. 공개 가능한 HTML만 명시적인 manifest로 선별한다.
5. 저장소 clone과 중앙 문서 사이트에서 같은 상대 경로 계약을 유지한다.
6. 외부 CDN이나 실행 서버 없이 과거 commit의 HTML도 열리게 한다.
7. Markdown과 HTML의 누락, 깨진 링크, 잘못된 공개 범위를 CI에서 검출한다.

## 3. 비목표

- Markdown 전체를 HTML로 자동 변환하지 않는다.
- HTML을 새로운 업무 규칙의 원본으로 만들지 않는다.
- 실제 예약 API나 production 동작을 변경하지 않는다.
- `clinic-appointment` 저장소 전체 `docs/`를 공개하지 않는다.
- 이 설계 문서만으로 GitHub Pages 설정, workflow dispatch, 중앙 사이트 배포 권한을 만들지 않는다.
- 승인된 delivery 범위는
  [구현 계획](../plans/2026-07-29-visual-companion-history-plan.md)이 관리하며,
  `clinic-appointment`와 `bluetape4k.github.io`의 PR·merge gate를 각각 통과한다.
- 임시 `.superpowers/brainstorm/` session 파일을 Git에 그대로 추가하지 않는다.

## 4. 현재 근거

저장소에는 이미 다음 독립 실행형 HTML이 추적되고 있다.

- `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html`
- `docs/superpowers/specs/2026-07-27-scheduling-policy-foundation-design.html`
- `docs/superpowers/plans/2026-07-26-appointment-plan-foundation.html`
- `docs/superpowers/plans/2026-07-27-scheduling-policy-foundation-plan.html`

`README.md`, `README.ko.md`, `docs/superpowers/INDEX.md`와 Markdown 설계·계획
문서도 이 HTML을 상대 링크로 참조한다. 따라서 새 체계는 기존 파일을 이동하거나
URL을 바꾸지 않고, 현재 선례에 공개·검증 계약을 추가해야 한다.

workspace의 공식 웹 문서는 별도 `bluetape4k.github.io` 저장소가 Astro와
Starlight 기반 GitHub Pages를 소유한다. 서비스 저장소마다 별도 Pages를
운영하면 navigation, 로케일, 보안 검토, 링크 검증이 중복된다.

## 5. 결정

### 5.1 Markdown 원본 + HTML 시각 동반 문서

동일 주제의 문서는 가능한 한 같은 basename을 사용한다.

```text
docs/superpowers/specs/
├── YYYY-MM-DD-<topic>-design.md
└── YYYY-MM-DD-<topic>-design.html
```

Markdown은 다음 내용을 소유한다.

- 문제, 목표, 비목표
- 대안과 선택 근거
- 업무 불변조건과 실패 의미론
- 정확한 API·type·schema 계약
- 검증 기준과 승인 상태

HTML은 다음 내용만 시각적으로 재구성한다.

- 역할별 여정
- 상태 전이와 분기
- 정책 합성과 데이터 흐름
- 실패·복구 시나리오
- 설계에서 구현·운영 문서로 이어지는 이력 지도

HTML에만 존재하는 업무 규칙은 허용하지 않는다. HTML에서 설명하는 규칙은
대응 Markdown section이나 별도 API/runbook 문서로 역링크한다.

### 5.2 임시 시각 동반 문서와 영구 시각 동반 문서 분리

brainstorm 단계의 `.superpowers/brainstorm/<session>/` 파일은 선택지 비교와
피드백 수집에 사용한다. 설계가 승인되면 필요한 화면을 다시 편집해
`docs/superpowers/specs/` 또는 `docs/superpowers/plans/`의 독립 HTML로 만든다.

영구 시각 동반 문서는 임시 session의 helper script, event endpoint, local server에
의존하지 않는다. 선택 UI가 필요하면 상태를 저장하지 않는 client-side
interaction만 사용한다.

### 5.3 문서별 표현 profile

시각 동반 문서의 설명 방식은 저장소 전체에서 하나를 선택하지 않는다. 각 설계
문서가 독자의 핵심 질문에 따라 다음 profile 중 하나를 명시한다.

| Mode | 핵심 질문 | 기본 구성 | 적합한 예 |
|---|---|---|---|
| `history` | 왜 이렇게 바뀌었는가? | Issue → 설계 → PR → API·runbook timeline | 예약 생성 멱등성 |
| `simulation` | 조건이 바뀌면 어떻게 동작하는가? | 행위자·정책·상태 입력과 판정 결과 비교 | 예약 상태 전이 |
| `hybrid` | 어떻게 동작하며 왜 그렇게 결정됐는가? | simulation과 history의 상호 탐색 | Scheduling Policy |

profile은 문서 작성자가 명시한다. validator는 mode별 필수 view와 허용값을
검사하지만, 문서 내용에서 mode를 자동 추론하지 않는다. 같은 저장소의 설계마다
다른 profile을 선택할 수 있다.

`hybrid`는 모든 문서의 기본값이 아니다. 두 관점이 실제로 필요하고 서로 연결될
때만 사용한다. 단순히 화면을 풍부하게 보이게 하려고 중복 내용을 추가하지 않는다.

### 5.4 공개 허용목록

저장소에 HTML이 존재한다는 사실만으로 공개 대상이 되지 않는다.

```text
docs/visual-companions/
├── README.md
└── manifest.json
```

`manifest.json`은 중앙 사이트가 가져갈 수 있는 파일만 열거한다.

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
    }
  ]
}
```

consumer는 repository glob으로 HTML을 수집하지 않고 manifest entry만 처리한다.
manifest에 없는 HTML은 저장소 내부 문서이며 공개되지 않는다.

presentation 계약:

- `history`는 `defaultView == "history"`이고 `views`에 `history`만 둔다.
- `simulation`은 `defaultView == "simulation"`이고 `views`에 `simulation`만 둔다.
- `hybrid`는 `views`에 `history`와 `simulation`을 모두 포함한다.
- `defaultView`는 반드시 `views`의 원소다.
- `views`는 중복 없는 배열이며 mode가 허용한 값만 포함한다.
- view 순서는 navigation의 기본 표시 순서를 결정한다.
- `history` view는 `id="history"`, `simulation` view는 `id="simulation"`인
  section을 가진다. `hybrid` 문서는 두 section과 양방향 navigation을 모두 가진다.
- 알 수 없는 mode나 view는 fail closed한다.

### 5.5 중앙 문서 사이트가 publication을 소유

공개 렌더링은 `bluetape4k.github.io`가 소유한다. 중앙 사이트의 build가 pinned
repository ref에서 manifest를 읽고, 열거된 HTML만 정적 asset으로 복사한다.

```text
clinic-appointment
  Markdown + HTML + manifest
            │ pinned ref
            ▼
bluetape4k.github.io build
  validate → copy allowlisted files → locale navigation → GitHub Pages
```

서비스 저장소의 Markdown은 clone 사용자에게 상대 링크를 제공한다. 공개 웹 링크는
중앙 사이트의 안정적인 route를 사용한다. 중앙 사이트 도입 전까지 상대 링크는
GitHub file view 또는 로컬 파일 열기 경로로 동작한다.

중앙 사이트 변경은 별도 저장소의 독립된 Type E 작업으로 계획하고 승인받는다.
이 문서만으로 외부 publication 권한이 생기지 않는다.

## 6. HTML 문서 계약

모든 영구 시각 동반 문서는 다음 조건을 만족한다.

### 6.1 자체 완결성

- UTF-8 `<!doctype html>` 문서다.
- CSS와 작은 JavaScript는 파일 안에 포함한다.
- 외부 CDN, font, analytics, API 호출에 의존하지 않는다.
- `file://`과 정적 HTTP hosting에서 모두 핵심 내용이 보인다.
- JavaScript가 비활성화되어도 문서와 navigation을 읽을 수 있다.

### 6.2 provenance

첫 화면이나 metadata 영역에 다음 값을 표시한다.

- 문서 주제와 기준일
- `draft`, `approved`, `implemented`, `superseded` 중 현재 상태
- 원본 Markdown 상대 링크
- 관련 Issue와 PR
- 기준 commit 또는 release
- 후속 API, runbook, verification 문서

commit이 아직 정해지지 않은 draft에서는 commit 항목을 생략한다. 배포 과정에서
임의의 최신 commit을 주입하지 않는다.

### 6.3 navigation과 접근성

- heading과 section anchor를 안정적으로 유지한다.
- keyboard만으로 navigation과 interaction을 사용할 수 있게 한다.
- 색상만으로 상태나 성공·실패를 구분하지 않는다.
- 좁은 viewport에서 가로 overflow 없이 핵심 흐름을 읽을 수 있게 한다.
- animation은 설명에 필요한 경우에만 사용하고 reduced-motion을 존중한다.
- print 시 본문과 provenance가 남고 navigation 장식은 제거된다.

### 6.4 interaction 한계

허용되는 interaction:

- 상태·역할·정책 version별 보기 전환
- 단계별 flow 강조
- 분기와 상세 설명 열기
- 설계 당시와 현재 구현 비교

허용되지 않는 interaction:

- 외부로 데이터를 전송하는 form
- token, 개인정보, 실제 환자·병원 데이터를 포함한 예제
- 저장소나 server 상태를 변경하는 동작
- HTML 내부에만 남는 승인 또는 업무 결정

## 7. 로케일 계약

작업 문서의 기본 설명은 한국어로 작성한다. 공개 HTML을 영문 README와
한글 README 양쪽에서 독자용 자산으로 제공할 때는 로케일별 source-equivalent
파일을 만든다.

신규 문서는 영문 `*.html`과 한국어 `*.ko.html`을 기본 경로로 사용한다. 다만
이미 한국어 unsuffixed `*.html`이 공개된 legacy 문서는 기존 경로를 유지하고,
영문 동등본을 `*.en.html`로 추가한다. 이번에 공개하는 두 설계가 이 예외에 해당한다.

technical identifier, API, command, Issue/PR URL은 로케일 간 동일하게 유지한다.
하나의 HTML 안에서 로케일을 전환하는 새 문서는 만들지 않는다.

## 8. 이력 모델

시각 동반 문서는 날짜별 스냅숏이다. 과거 파일을 새 설계 의미로 덮어쓰지
않는다.

| 상태 | 의미 | 허용 변경 |
|---|---|---|
| `draft` | 설계 검토 중 | 같은 날짜 문서에서 내용 수정 |
| `approved` | 구현 기준으로 승인 | 오탈자·링크 복구, 의미 변경은 새 문서 |
| `implemented` | 실제 구현과 검증 연결 완료 | provenance와 후속 링크 보강 |
| `superseded` | 새 설계로 대체 | 대체 문서 링크 추가, 원문 보존 |

`superseded` 문서는 삭제하지 않는다. 상단에 대체 문서를 표시하고 index에서는
현재 문서와 이력 문서를 구분한다.

## 9. 검증

서비스 저장소에 작은 validator를 두고 다음을 검사한다.

1. manifest schema와 중복 `id`
2. `source`와 `html` 경로가 저장소 안에 존재하는가
3. 공개 entry가 허용된 문서 root 아래에 있는가
4. `presentation.mode`, `defaultView`, `views` 조합이 profile 계약과 일치하는가
5. mode별 필수 section anchor가 HTML에 존재하는가
6. Markdown이 HTML을 링크하고 HTML이 Markdown으로 역링크하는가
7. HTML에 provenance 필수값과 `<html lang>`이 있는가
8. 외부 script, analytics, form, network URL이 없는가
9. 로케일 쌍이 선언된 경우 양쪽 entry와 route가 존재하는가
10. manifest 밖의 HTML이 publication artifact에 섞이지 않는가

문서 변경의 최소 검증은 다음과 같다.

```bash
git diff --check
node scripts/validate-visual-companions.mjs
```

GitHub Actions 변경 시 `actionlint`를 추가한다. 중앙 사이트 consumer를 구현할
때는 허용목록 file count와 생성 route를 build 결과에서 다시 확인한다.

시각 검토는 validator를 통과한 뒤 실제 browser에서 desktop과 narrow viewport를
확인한다. HTML parse 성공만으로 시각 검토를 대신하지 않는다.

## 10. 보안과 공개 경계

- manifest는 opt-in 허용목록이다. 자동 탐색이나 전체 `docs/` 복사를 금지한다.
- 실제 tenant, clinic, patient 식별자와 production URL을 넣지 않는다.
- JWT, header, credential, 내부 운영 secret을 예제에 포함하지 않는다.
- 외부 link는 문서 navigation일 뿐 build-time fetch 대상이 아니다.
- 중앙 사이트 build는 pinned commit을 소비해 재현 가능하게 한다.
- 공개 전 diff에서 internal review, test log, WIP 문서가 포함되지 않았는지 확인한다.

## 11. 단계적 적용

### 단계 1: 저장소 계약

- `docs/visual-companions/README.md`에 작성 규칙을 기록한다.
- 기존 공개 후보를 `manifest.json`에 명시한다.
- validator와 fixture 기반 검사를 추가한다.
- 기존 HTML 경로와 README 링크는 유지한다.

### 단계 2: 기존 문서 보강

- 기존 HTML에 Markdown 역링크와 provenance를 추가한다.
- 기존 한국어 문서의 영문 동등본을 만든다.
- `docs/superpowers/INDEX.md`에서 Markdown 원본과 HTML 시각 동반 문서를 구분한다.

### 단계 3: 중앙 사이트 publication

- `bluetape4k.github.io`에 pinned manifest consumer를 추가한다.
- 허용목록 file만 asset으로 복사한다.
- 영문·한글 route와 navigation을 연결한다.
- Pages build와 실제 route를 검증한다.

각 단계는 독립적으로 검증 가능하다. 단계 3이 지연되어도 단계 1과 2의 로컬
문서 및 이력 계약은 유지된다.

## 12. 완료 기준

- Markdown이 업무 규칙과 결정의 원본으로 명시되어 있다.
- 임시 brainstorm 파일과 영구 HTML의 책임이 분리되어 있다.
- 각 설계가 `history`, `simulation`, `hybrid` 중 적합한 profile을 명시한다.
- profile 선택은 자동 추론되지 않으며 manifest 조합으로 검증할 수 있다.
- 기존 HTML과 링크를 이동하지 않는 migration 경로가 있다.
- 공개 대상은 manifest 허용목록으로만 선택된다.
- 중앙 사이트와 서비스 저장소의 publication 책임이 분리되어 있다.
- provenance, locale, accessibility, security, history 계약이 정의되어 있다.
- validator가 검사할 항목과 최소 명령이 구체적이다.
- production 코드, API, DB, 예약 동작은 변경되지 않는다.
- GitHub Pages 공개와 외부 workflow 실행은 승인된 구현 계획의 저장소별
  PR·merge gate를 통과해야 한다.
