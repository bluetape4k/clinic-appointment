# 시각 동반 문서 계약

이 디렉터리는 `clinic-appointment`의 공개 가능한 HTML 시각 동반 문서를
선별하고 검증하는 계약을 관리한다. 업무 규칙과 설계 결정의 원본은 항상 대응
Markdown 문서다. HTML은 역할별 흐름, 상태 전이, 정책 합성, 실패·복구, 변경
이력을 이해하기 쉽게 재구성한다.

## 공개 허용목록

`manifest.json`만 공개 허용목록이다. 중앙 문서 사이트는 저장소 glob으로
`docs/**/*.html`을 탐색하지 않으며, `public: true`인 manifest 항목과 그
`locales` 파일만 가져간다. manifest에 없는 구현 계획, 검토 기록, 임시 HTML은
저장소 내부 문서로 남는다.

각 문서는 다음 값을 명시한다.

- stable `id`
- 원본 Markdown `source`
- `approved` 등의 `status`
- 명시적으로 선택한 `presentation`
- 원본과 같은 의미를 갖는 `en`/`ko` title과 HTML path

## 표현 프로파일

| 모드 | 독자의 핵심 질문 | 필수 보기 |
|---|---|---|
| `history` | 왜 이렇게 바뀌었는가? | `history` |
| `simulation` | 조건에 따라 어떻게 동작하는가? | `simulation` |
| `hybrid` | 어떻게 동작하며 왜 그렇게 결정됐는가? | `simulation`, `history` |

프로파일은 작성자가 선택합니다. validator는 문서 내용에서 모드를 추론하지 않습니다.
`hybrid`는 두 관점이 실제로 필요하고 서로 이동할 수 있을 때만 사용한다.

## HTML 요구사항

- UTF-8 self-contained `<!doctype html>` 문서
- 외부 CDN, font, analytics, API, form, network 요청 없음
- JavaScript가 없어도 읽을 수 있는 본문과 navigation
- 원본 Markdown backlink, status, 기준일, baseline commit을 담은 provenance
- mode별 stable section anchor와 양방향 navigation
- keyboard focus, 좁은 viewport, print, `prefers-reduced-motion` 지원
- 색상만으로 의미를 전달하지 않음
- 실제 patient, clinic, tenant 식별자, token, credential, 운영 URL은 넣지 않습니다.

기존 한국어 접미사 없는 HTML은 경로를 보존한다. 이 저장소의 첫 두 공개 문서는
영문 동등본에 `.en.html` 접미사를 사용한다.

## 예약 신뢰도 업무 흐름

`booking-reliability-workflow`는 Issue #176의 승인된 업무 흐름을 설명한다.
manifest의 기본 문서는 밝은 테마이며, 같은 내용의 어두운 테마와 두 locale
변형도 함께 보관한다. HTML은 시뮬레이션과 결정 이력을 제공하고, PNG는 README나
오프라인 문서에서 바로 읽을 수 있는 캡처다.

| Locale | Light | Dark | PNG |
|---|---|---|---|
| English | [HTML](booking-reliability-workflow-en-light.html) | [HTML](booking-reliability-workflow-en-dark.html) | [PNG](booking-reliability-workflow-en-light.png), [dark](booking-reliability-workflow-en-dark.png) |
| 한국어 | [HTML](booking-reliability-workflow-ko-light.html) | [HTML](booking-reliability-workflow-ko-dark.html) | [PNG](booking-reliability-workflow-ko-light.png), [dark](booking-reliability-workflow-ko-dark.png) |

정적 ERD·sequence·class 자료는 [README 다이어그램 디렉터리](../images/readme-diagrams/)에
SVG 원본과 PNG를 함께 둔다. HTML/PNG와 정적 다이어그램 모두 기준 Markdown과
코드 계약을 보충할 뿐, 별도의 정책 의미를 정의하지 않는다.

## 검증

```bash
node --test tests/visual-companions/*.test.mjs
node scripts/validate-visual-companions.mjs
actionlint .github/workflows/visual-companions.yml
git diff --check develop...HEAD
```

validator를 통과한 뒤 두 locale 문서를 실제 브라우저에서 desktop과 좁은
viewport로 열고 `#simulation`, `#history`, keyboard focus, print, Markdown
backlink를 확인한다. HTML parse 성공은 시각 검토를 대신하지 않는다.
