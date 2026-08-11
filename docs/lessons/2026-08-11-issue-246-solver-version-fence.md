# Issue #246 appointment-solver version fence 원자화

## 발견

기존 `verifySourceVersions`는 별도 transaction에서 snapshot version을 읽고
`Boolean`만 반환했다. 호출자가 그 결과를 확인한 뒤 assignment를 저장하면, 확인과
저장 사이에 다른 writer가 version을 소비해도 stale 결과가 반영될 수 있었다.

## 결정

`SolverService.applyOptimizedAssignments`를 실제 반영 port로 제공한다. 이 메서드는
source appointment rows를 `FOR UPDATE`로 잠근 뒤 snapshot version을 비교하고, 각
assignment를 expected version 조건이 포함된 CAS로 갱신한다. 하나라도 stale이거나
CAS가 실패하면 예외를 이용해 전체 transaction을 rollback하고 `false`를 반환한다.

`isSourceVersionCurrentAdvisory`는 호출자에게 최신성 참고값만 제공한다. 기존
`verifySourceVersions` 이름은 호환을 위해 deprecated alias로 남기되, 결과 반영에는
사용하지 않는다.

## 테스트·fixture 규칙

- stale race: advisory 확인 뒤 별도 writer가 version을 소비하면 원자 apply가 거부된다.
- rollback: 동일 결과의 중복 assignment로 두 번째 CAS를 실패시켜 첫 번째 갱신도
  rollback되는지 검증한다.
- constraint 검증: 부분 초기화 planning entity의 9개 constraint를 constraint별
  명명 테스트로 분리해 실패 위치를 즉시 식별한다.
- 일반 Exposed fixture는 `SchemaUtils.createMissingTablesAndColumns(...)`를 사용하고
  `@BeforeEach`의 역순 `deleteAll()` cleanup을 유지한다.

## 검증

- 기준선: `:appointment-solver:cleanTest :appointment-solver:test` 68 tests 통과
- RED: 원자 apply port가 없어 `SolverServiceTest` 컴파일이 실패함을 재현
- targeted: `SolverServiceTest`와 `ConstraintVerifierTest` 38 tests 통과
- 최종: `:appointment-solver:cleanTest :appointment-solver:test` 79 tests 통과
- 최종 check: `:appointment-solver:check` 통과, Kover generate/cached verify/verify 포함
- 정적: solver test fixture 직접 `SchemaUtils.create(...)` 0건, JUnit
  `assertThrows` 0건, `git diff --check` 통과

## 남은 범위

원격 CI, production DB 동시성 canary, PR 생성·merge 및 다른 모듈 호출자의 실제
`applyOptimizedAssignments` 전환은 별도 delivery 게이트에서 실행하지 않았다.
