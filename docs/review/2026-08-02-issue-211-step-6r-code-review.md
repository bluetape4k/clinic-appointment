# Issue #211 solver nullable planning boundaries 최종 사전 PR 리뷰

## 결론

- 판정: `PASS`
- P0: `0`
- P1: `0`
- P2: `0`
- P3: `0`
- 리뷰 기준점: `c5c646644b595b0bdec24375ca65ad97b790e477`
- 비교 기준: `origin/develop` (`a180462821dc4c0cfd9480f8df83b35a1aa36d05`)
- 작업 경계: `fix/issue-211-solver-nullable-boundaries`

`code-reviewer`와 `architect`가 같은 exact HEAD를 독립적으로 검토했다. 두
관점 모두 nullable planning state, constraint/converter 경계, solver/repository
lifecycle invariant, 테스트와 문서 정합성에서 blocking finding을 남기지 않았다.

## 리뷰에서 보정한 항목

| 항목 | 보정 | 결과 |
|---|---|---|
| 내부 lifecycle 예외가 `IllegalArgumentException`으로 노출될 위험 | repository ID와 solver score에 `checkNotNull`을 사용하고 missing-score 회귀 테스트를 `IllegalStateException` 계약으로 고정 | 해소 |
| `withAssigned`가 불필요한 module API가 될 위험 | helper를 `internal`로 제한하고 부분 초기화 semantics를 Korean-first KDoc으로 기록 | 해소 |
| 부분 constraint 회귀 범위가 대표 케이스에 한정됨 | H2, H3, H4a, H4b, H11의 partial-state 경로를 `ConstraintVerifierTest`에 추가 | 해소 |

## 6-R / 7-Tier 결과

| Tier | 판정 | 근거 |
|---|---|---|
| Performance | `PASS` | indexed equality/overlap join을 유지했고 broad filtering replacement가 없다. |
| Stability | `PASS` | `withAssigned`와 `mapNotNull`로 부분 계획 상태를 안전하게 제외하고 내부 invariant는 `checkNotNull`로 구분한다. |
| Security | `PASS` | 예외 context에 clinic/date만 포함하며 patient/member 개인정보를 넣지 않는다. |
| Operations | `PASS` | lifecycle 실패가 caller validation(400)과 다른 `IllegalStateException` 경로로 식별된다. |
| Developer/API | `PASS` | `SolverResult` shape 변경이 없고 helper는 `internal`이며 KDoc이 한국어 우선이다. |
| User/Caller | `PASS` | incomplete non-pinned appointment은 기존처럼 결과에서 제외되고 완전 배정 결과는 유지된다. |
| Integration | `PASS` | 설계·계획·코드·테스트·module build 증거가 이슈 계약과 일치한다. |

## Fresh 검증

| 검증 | 결과 |
|---|---|
| Focused solver suites | `40 passing`, `BUILD SUCCESSFUL` |
| `./gradlew :appointment-solver:build --no-daemon --no-build-cache --rerun-tasks --max-workers=1` | `67 passing`, `koverVerify`, `BUILD SUCCESSFUL` (54초) |
| production static scan | `!!`, `allowsUnassigned`, `forEachIncludingUnassigned`, `runBlocking`, `synchronized(`, `runCatching`, console 출력 0건 |
| `git diff --check` | 통과 |
| quality task inventory | `koverVerify`/`check` 존재; detekt/ktlint task는 없음 |

최종 빌드 직전 병렬 Gradle 실행에서 test-result binary가 충돌해 `EOFException`이
발생한 적이 있다. persistent daemon과 독립 review lane을 종료한 후 leader 단독,
`max-workers=1`로 재실행해 위 성공 결과를 얻었다. 이는 코드 테스트 실패가 아닌
Gradle serialized test-result 상태 문제로 분리했다.

LSP/AST MCP 진단은 transport `closed`로 사용할 수 없었고, Gradle compile/build와
정적 `rg` 점검을 대체 증거로 사용했다. 이는 코드 finding이 아니며 P0/P1/P2/P3
판정에는 영향을 주지 않는다.

## 범위 밖

- remote branch push, PR 생성, GitHub issue/DoD 갱신, CI, merge는 아직 실행하지 않았다.
- 해당 외부 mutation은 target repository/base/head를 지정한 별도 권한과 merge 직전 fresh approval이 필요하다.

## DoD

`P0=0`, `P1=0`, `P2=0`, `P3=0`. 설계·구현·회귀 테스트·module build·독립
6-R/7-Tier 리뷰가 완료되었고, PR 생성 전 로컬 증거가 준비되었다.
