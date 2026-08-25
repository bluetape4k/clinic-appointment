# Issue #396 notification assertion 7-Tier 검토

검토일: 2026-08-26  
검토 branch: `fix/issue-396-notification-assertions`  
선행 base: `fb865435b23ef8f85437af3537379fe4b549171b` (`#400` PR #417)  
구현 source tip: 구현 commit 생성 후 갱신한다.

## 검토 범위

- notification outbox end-to-end/lifecycle/provider contract 테스트의 예외 assertion
- `NotificationAssertionPatternComplianceTest` source guard
- cancellation·provider 오류의 기존 타입/메시지 계약과 assertion API 재사용
- module test/check/build와 stacked train 전달 경계
- 제외: production notification API, schema readiness 진단, retry 정책 semantics,
  새로운 dependency

## 재사용 판단

| 후보 | 판정 | 근거 |
|---|---|---|
| `io.bluetape4k.assertions.assertFailsWith` | 채택 | 모듈의 기존 테스트가 사용 중인 예외 assertion vocabulary를 그대로 재사용했다. |
| 기존 `shouldBeEqualTo` | 유지 | 변경 대상 파일의 값·boolean 검증을 건드리지 않고 현재 표준을 유지했다. |
| 모듈 source compliance guard | 채택 | 새 테스트가 JUnit/Kotlin generic assertion으로 회귀하는 것을 실제 source scan으로 막는다. |
| 새 assertion wrapper/dependency | 제외 | bluetape4k API만으로 요구사항을 충족하므로 중복 계층과 dependency를 만들지 않았다. |

## 모듈별 7-Tier 판정

| Tier | 판정 | 현재 근거 |
|---|---|---|
| 성능 | PASS | assertion 호출만 교체했고 production/runtime 경로와 테스트 데이터 크기를 변경하지 않았다. |
| 안정성/수명주기 | PASS | `CancellationException`을 retry로 삼키지 않는 기존 테스트 동작과 fenced completion 취소 계약을 유지했다. |
| 보안/데이터 경계 | PASS | provider destination·rendered payload masking 검증과 예외 경계를 그대로 보존했다. |
| 운영/관측성 | PASS | 테스트 실패가 bluetape4k assertion으로 보고되고 generic assertion 회귀 guard가 모듈 source를 검사한다. |
| 개발자/API | PASS | 공개 production API와 dependency graph를 변경하지 않고 모듈 표준 API를 재사용했다. |
| 사용자/호출자 | PASS | 예제 애플리케이션의 notification delivery·cancellation 호출자 동작은 변경하지 않았다. |
| 통합/테스트/빌드 | PASS | 대상 테스트, compliance test, full module test, check/build가 통과했다. |

판정: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## Kotlin checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| KT-01 | PASS | 기존 null-safety·coroutine cancellation 경계와 immutable fixture를 유지했다. |
| KT-02 | PASS | source scan으로 세 generic assertion 사용처와 모듈의 기존 bluetape4k 패턴을 확인한 뒤 최소 범위만 수정했다. |
| KT-03 | PASS | 새 `!!`, `runCatching`, JUnit/Kotlin generic assertion, dependency가 없다. |
| KT-04 | PASS | targeted test 후 full test, check/build, diff/doc audit 순서로 검증했다. |
| KT-05 | PASS | 적용 가능한 Kotlin/testing rows를 모두 확인했으며 production API/auto-configuration 변경은 `N/A`다. |

## Fresh verification

| 명령 | 결과 |
|---|---|
| targeted 대상 3개 + compliance test | `24 passing`, `BUILD SUCCESSFUL` |
| `./gradlew :appointment-notification:test --rerun-tasks --no-daemon` | `220 passing`, `BUILD SUCCESSFUL` |
| `./gradlew :appointment-notification:check --rerun-tasks --no-daemon` | `BUILD SUCCESSFUL` |
| `./gradlew :appointment-notification:build --rerun-tasks --no-daemon` | `BUILD SUCCESSFUL` |
| `git diff --check` | `PASS` |
| generic assertion source scan | 위반 `0` |
| bluetape-writer Korean terminology audit | 최종 문서 3개, findings `[]`로 갱신한다. |

`detekt`는 이 모듈에 독립 task가 없으므로 `:appointment-notification:check`의
authoritative static/build gate를 사용한다. 기존 Exposed deprecation warning은
이번 변경과 무관한 fixture API 경고이며 test/check/build는 성공했다.

## Finding disposition

- P0/P1/P2/P3: 없음.
- 기존 cancellation, provider exception, payload masking 계약은 테스트 결과로
  유지됨을 확인했다.
- 전체 train merge는 #392~#402 모든 child가 완료될 때까지 보류한다.

## PR 전 결론

`PASS` — assertion 표준화·guard·문서 검토 gate를 통과했다. PR delivery에서
#400 exact head 위 stacked 관계, exact-head CI와 live metadata/read-back을 추가로
확인하며 merge approval은 요청하지 않는다.

## 문서 작성 점검

- [x] SPW-01: 범위, 선행 base, 제외 항목을 고정했다.
- [x] SPW-02: 7-Tier, Kotlin checklist, findings와 검증을 포함했다.
- [x] SPW-03: 한국어 기술 문체와 code token을 보존했다.
- [x] SPW-04: source guard·기존 assertion·cancellation 계약을 대조했다.
- [ ] SPW-05: 최종 PR head, CI run, Issue/PR live read-back을 반영한다.
