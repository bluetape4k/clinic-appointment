# 임시 작업 디렉터리 ignore와 PR 전달 lesson

## 배경

`.superpowers/`와 `.workflow-inputs/`는 로컬 작업 중 생기는 임시 산출물이다. 처음에는
디렉터리 이동·삭제를 검토했지만, 최신 요청은 두 경로를 보존하면서 Git 추적만 막고
PR과 merge까지 완료하는 것이었다.

## 결정

- `.gitignore`에 `.superpowers/`와 `.workflow-inputs/`를 추가한다.
- 두 디렉터리의 로컬 파일은 삭제하거나 `docs/superpowers/`로 이동하지 않는다.
- 기존 `appointment-event/README.ko.md`와
  `frontend/appointment-frontend/angular.json` 변경은 이 PR에 섞지 않고
  경로 지정 stash로 보존한다.
- 로컬 편집으로 멈추지 않고 PR 본문, exact head CI, 리뷰 상태, merge 결과까지
  확인한다.

## 결과와 검증

ignore 규칙은 임시 디렉터리 내부 파일에 적용되고, `git diff --check`는 통과해야 한다.
PR은 이 변경만 포함하며, 기존 작업 변경과 임시 디렉터리의 내용은 보존한다.

## 놓친 점과 재발 방지

작업 완료를 로컬 파일 변경으로만 판단하면 전달 단계가 누락된다. 앞으로 사용자가
PR·merge를 요구하면 exact head 기준의 PR 메타데이터와 CI를 확인한 뒤, 최신 명시
승인을 받고 merge한다. 서로 무관한 dirty 변경은 경로 지정 stash로 먼저 보존한다.

## 문서 게이트

- SPW-01~05: PASS — 배경, 결정, 결과·검증, 놓친 점, 재발 방지를 기록했다.
- KO-01~06: PASS — 경로·명령·식별자는 그대로 두고 한국어 문장과 제목을 검토했다.
- KO-07: PASS — 이 문서에 대한 용어 감사 결과를 PR 검증에 기록한다.
