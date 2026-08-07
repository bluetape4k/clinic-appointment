# Timefold Solver 2 consumer 마이그레이션

## 배경

appointment solver는 Timefold score, constraint-verifier, score director, move
API를 사용합니다. Timefold Solver 2.1에서 이 API가 모두 이동하거나 변경되었습니다.

## 결정

2.1 API로 직접 마이그레이션하고 로컬 solver 동작은 변경하지 않습니다.

## 결과

- Score import는 이제 `ai.timefold.solver.core.api.score.*`를 사용합니다.
- Constraint weight는 `HardSoftScore`에 `Long` 값을 반환합니다.
- `ConstraintVerifier`는 core artifact에서 import합니다.
- Move filtering 테스트는 preview `Move` API와 `SequencedCollection` 반환
  타입을 사용합니다.
- Timefold 2가 허용 문자 집합 밖의 ID를 거부하므로 constraint ID에 더 이상
  `:`를 넣지 않습니다.

## 검증

- `./gradlew :appointment-solver:compileTestKotlin --no-daemon`
- `./gradlew :appointment-solver:test --no-daemon`
