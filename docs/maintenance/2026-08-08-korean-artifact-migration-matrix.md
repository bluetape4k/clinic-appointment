# clinic-appointment 한국어 기술문서 전환 매트릭스

## 목적

`clinic-appointment`를 한국어 사용자와 유지보수자가 읽는 예제 저장소로
정비한다. 원문에 포함된 코드, 명령, API 이름, 식별자, URL, 정확한 오류 문구,
머신 판독용 토큰은 보존하고, 그 주변 설명만 자연스러운 한국어 기술문체로
작성한다.

## 기준 시점

- 기준 시점: 2026-08-08 KST
- Markdown 파일: 236개
- GitHub Issue: 69개(열림 15개, 닫힘 54개)
- GitHub Pull Request: 166개(열림 0개, 병합 137개, 닫힘 29개)
- Pull Request 작성자: `debop` 116개, `app/dependabot` 50개

## 분류 규칙

| 대상 | 처리 | 보존 경계 | 완료 증거 |
|---|---|---|---|
| 루트·모듈 `README*.md` | 한국어 본문으로 정비 | 코드, 명령, 링크, 버전, 배지 URL | 링크 검사와 `git diff --check` |
| `docs/**/*.md` 사용자 문서 | 한국어 기술문체로 재작성 | 코드 블록, 경로, API, 수치, 외부 인용의 출처 | 문서별 원문 대조와 Markdown 검사 |
| `CHANGELOG.md`, `WIP.md`, 계획·리뷰·lesson·runbook | 한국어 제목·표·설명으로 정비 | 릴리스 버전, 이슈/PR 번호, 명령, SHA | 변경 이력·계획 링크와 기술값 대조 |
| Kotlin KDoc·reader-facing 주석 | 소스와 함께 한국어로 정비 | 심볼명, 타입명, 예외명, 코드 예시 | 컴파일·KDoc 대상 검사 |
| GitHub Issue 69개 | 사람이 작성한 제목·본문을 한국어로 정비 | 번호, 상태, 라벨, 링크, 코드·명령 | live `gh` 재조회 |
| 사람 작성 PR 116개 | 제목·본문·DoD를 한국어로 정비 | 커밋 SHA, 파일 경로, CI 토큰, `## DoD Status` | live head·CI·본문 재조회 |
| Dependabot PR 50개 | 유지(N/A) | 자동 생성 제목·본문·의존성 메타데이터 | 작성자와 자동 생성 여부 증명 |
| `AGENTS.md`, `CLAUDE.md` | 유지(N/A) | 에이전트 운영 계약은 영어 유지 | 운영 파일 변경 없음 |
| HTML/SVG/PNG 시각 동반 자산 | 기존 locale 계약 유지, 필요한 한국어 문서 링크만 정비 | 시각 자산 manifest와 파일명 | manifest·링크·시각 자산 검사 |

## 배치 상태

1. 루트·모듈 README, `CHANGELOG.md`, `WIP.md`, 소형 lesson/log 문서: 주 세션이
   한국어 본문으로 정비했습니다.
2. API·정책·lesson 배치: `writer-a`가 한국어로 정비했습니다.
3. runbook·review·plan·research·lesson 배치: `writer-b`가 한국어로 정비했습니다.
4. 대형 superpowers checklist·plan 배치: `writer-c`가 한국어로 정비했습니다.
5. KDoc·코드 주석과 GitHub Issue·사람 작성 PR: 한국어로 정비하고 live 조회로
   확인했습니다.

## 제외 사유

- `AGENTS.md`와 `CLAUDE.md`는 LLM-facing 운영 계약이므로 영어를 유지합니다.
- Dependabot PR은 GitHub가 자동 생성하는 외부 메타데이터이므로 본문을 임의로
  번역하거나 덮어쓰지 않습니다.
- 시각 동반물은 영어·한국어 locale 파일이 함께 배포되는 계약을 유지하고,
  문서 링크와 설명만 한국어 범위에서 정비합니다.

## 완료 조건

- 각 대상이 `한국어 정비`, `유지`, `N/A` 중 하나로 분류됩니다.
- 기술값과 링크가 원문·소스와 일치합니다.
- `git diff --check`와 대상 문서 검사가 통과합니다.
- GitHub live Issue/PR 메타데이터가 분류표와 일치합니다.
- 병합 전에는 정확한 PR head, CI, 본문 `## DoD Status`를 다시 확인하고,
  사용자의 최신 병합 승인을 별도로 받습니다.
