# Type A review gate 백필 lesson

Issue #208에서 PR #200, #202, #205, #207의 historical review evidence를 재구성했다.

## 결정

- PR body의 “independent review” 문구는 exact-head durable artifact를 대체하지 못한다.
- Type A 순서는 `2-R spec → 3-R plan → implementation remediation → 6-R/seven-tier`다.
- upstream contract가 바뀌면 downstream evidence를 무효화하고 다시 실행한다.
- historical exact head의 결함은 원래 head에 소급해 PASS하지 않는다. 결함, remediation commit, focused validation을 각각 연결한 뒤 remediation head에만 최종 verdict를 부여한다.
- seven-tier는 performance, stability, security/privacy, operations, developer/API, user/caller의 여섯 관점과 main-session integration을 모두 이름으로 남긴다.
- 사후에 작성한 retrospective assessment는 merge 전 independent gate의 증거가 아니다. reviewer identity와 당시 receipt가 없으면 historical gate는 `NOT PROVEN` 또는 reviewed `N/A`로 남긴다.
- remediation PR은 PR head와 merge commit을 별도 필드로 기록한다. tree가 같아도 exact-head review anchor를 merge SHA로 대체하지 않는다.
- suspend materializer의 각 blocking DB 경계는 source-text 존재 검사만으로 PASS하지 않으며, 호출 경로별 IO dispatcher 동작 테스트가 필요하다.

## 이 저장소 적용 결과

| PR | historical 상태 | 최종 근거 |
|---|---|---|
| #200 | `runBlocking` P1 | PR #215 merge `9899dac...`, Reactor/suspend bridge, focused API tests |
| #202 | registry lifecycle contract와 구현 일치 | exact-head 2-R/3-R/6-R 기록 |
| #205 | JVM monitor 안 suspend Exposed 작업 P1 | PR #215 merge `9899dac...`, Mutex + IO dispatcher |
| #207 | touched test generic assertions P2 | PR #215의 28개 테스트 정리와 compliance test |

## 재사용 체크

1. live GitHub exact head와 changed-file list를 먼저 고정한다.
2. canonical spec/plan 위치와 `docs/review` artifact 존재를 확인한다.
3. 명세와 계획의 PASS를 구현 PASS로 혼동하지 않는다.
4. remediation 후 source scan, focused tests, `git diff --check`를 새로 실행한다.
5. issue/PR에는 P0/P1/P2/P3와 follow-up을 명시한다.
6. retrospective PASS와 current remediation PASS를 분리하고, local index와 issue DoD를 같은 blocked/pending 상태로 유지한다.
7. receipt authority를 현재 `.bluetape` run에 고정하고, stale `.omx` checkpoint를 현재 lane·검증 결과의 증거로 재사용하지 않는다.
