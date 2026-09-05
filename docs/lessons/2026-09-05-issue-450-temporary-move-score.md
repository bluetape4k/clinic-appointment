# #450 임시 move의 배정 복원과 저장 점수 복원

## 범위와 확인 결과

Timefold `2.4.0`에서 임시 move를 취소한 뒤 저장 점수가 복원되지 않는 오류를 재현하고,
이 예제 저장소에서 `2.6.0`을 재정의해 같은 테스트가 통과하는지 확인했다.
`dependencyInsight`로 전환 전 core·benchmark `2.4.0`, 전환 후 core·benchmark·jaxb
실행 의존성 `2.6.0`을 확인했다. 중앙 catalog 저장소는 변경하지 않았다.

새 `IncrementalScoreRegressionTest`는 운영 제약 H3·S1·S2를 사용한다.
의사·날짜·시간 변경을 같은 계산기에 반복 반영하고, 각 단계의 명시적 기대 점수와
복제된 현재 배정을 넣은 새 계산기의 결과를 비교한다. 세 변경·복원 테스트는 두 버전에서 모두 통과했다.

네 번째 테스트는 실제 `executeTemporaryMove`의 자동 undo를 실행한다.
10시 예약을 의사 부재 시간인 9시로 변경하면 `-1hard/-1000soft`가 된다.
`2.4.0`에서는 undo 후 예약 시간이 10시로 복원되지만, `solution.score`는 임시 점수로 남아
원래 점수 `0hard/-400soft`와 다르다. 콜백에서 추가 점수 계산을 제거해도 같은 실패가 재현됐다.

## 판단과 경계

- 배정 복원과 solution에 저장된 점수 복원은 서로 다른 검증 항목이다.
- undo 직후 다시 `calculateScore()`부터 호출하면 저장 점수 복원 누락을 덮을 수 있다.
  먼저 배정과 `solution.score`를 검사하고, 그 다음 증분·전체 재계산을 비교한다.
- 이는 내부 temporary move 경로의 실패 재현이다. 실제 서비스가 잘못된 예약을 저장하거나
  잘못된 최종 해를 반환했다는 증거는 아니다.
- `impl` 및 `preview` API는 테스트에만 격리한다. 다음 버전에서 시그니처와 동작을 다시 확인한다.
- 실패 테스트를 비활성화하거나 기대값을 실패 동작에 맞춰 바꾸지 않는다.
  실패를 고친 upstream 버전을 적용한 뒤 동일한 기대값으로 검증한다.

## 잘못된 판단과 재발 방지

처음에는 중앙 catalog를 따라야 한다고 판단해 로컬 버전 전환을 보류했다.
사용자는 예제·워크숍 저장소는 중앙 catalog를 기본 사용하되 버전을 재정의할 수 있다고 정정했다.
라이브러리 저장소의 의존성 관리 정책을 예제 저장소의 절대 조건으로 적용하지 않는다.
다음 전환에서는 저장소 유형과 사용자 지정을 먼저 확인하고, 예제의 로컬 재정의가 허용되면
중앙 catalog 변경이나 릴리스를 선행 조건으로 요구하지 않는다.

core·benchmark 버전만 올렸을 때는 Spring 의존성 관리에 의해 jaxb가 `2.4.0`에 남았다.
Solver 모듈에 Timefold BOM `2.6.0`을 가져와 실행 아티팩트 세 개를 정렬하고,
잠금 파일·검증 메타데이터·의존성 검사 스크립트를 함께 갱신했다.
향후 전환에서도 직접 의존성뿐 아니라 jaxb 등 전이 실행 의존성까지 확인한다.

`compileClasspath`에는 중앙 `compileOnly` 플랫폼에서 유입되는 Timefold BOM `2.4.0`
메타데이터가 남는다. 중앙 BOM은 유지하므로 이 항목은 의도적으로 허용한다.
core·benchmark·jaxb JAR는 compile/runtime/test 구성 모두 `2.6.0`으로 잠겼으며,
검사 스크립트는 실행 의존성을 검증한다. BOM을 포함한 모든 그래프 항목을 `2.6.0`으로
정렬했다고 해석하지 않는다.

## 재현 명령

```bash
./gradlew :appointment-solver:test --tests '*IncrementalScoreRegressionTest' \
  --console=plain --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.incremental=false --max-workers=2
```

- `2.4.0`: 회귀 테스트 4개 중 3개 통과, 저장 점수 복원 1개 실패.
  전체 Solver 테스트도 102개 중 동일한 1개가 실패했다.
- `2.6.0`: 회귀 테스트 4개와 전체 Solver 테스트 102개가 모두 통과했다.
  `ConstraintVerifierTest` 29개와 서비스·PostgreSQL 동시성 테스트 24개를 포함한다.
- 이 결과는 점수 복원과 기존 테스트 범위의 호환성 증거다.
  동일 dataset·seed·종료 조건의 성능 비교나 별도 Jackson 직렬화 왕복은 검증하지 않았다.

## 근거

- [소비자 이슈 #450](https://github.com/bluetape4k/clinic-appointment/issues/450)
- [중앙 전환 #242](https://github.com/bluetape4k/bluetape4k-dependencies/issues/242)
- [v2.4.0 AbstractScoreDirector](https://github.com/TimefoldAI/timefold-solver/blob/v2.4.0/core/src/main/java/ai/timefold/solver/core/impl/score/director/AbstractScoreDirector.java)
- [v2.4.0 MoveDirector](https://github.com/TimefoldAI/timefold-solver/blob/v2.4.0/core/src/main/java/ai/timefold/solver/core/impl/move/MoveDirector.java)
- [upstream 수정 #2596](https://github.com/TimefoldAI/timefold-solver/commit/98bb88ec5394a7b627921e9a7aa4f4830980acaf):
  `MoveDirector.executeTemporary`가 임시 실행 전에 저장한 점수를 undo 후 복원하도록 수정했다.
  이 수정은 v2.6.0 소스에 포함되며, 이번 테스트에서 저장 점수 복원을 확인했다.

공식 소스는 2026-09-05 GitHub API로 확인했다. 외부 소스는 복사하지 않고 검증에 필요한
계약만 요약했다. 다른 저장소 변경을 제외한 승인 범위에 따라 이 저장소에 근거를 보존한다.
