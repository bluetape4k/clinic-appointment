# Issue #396 notification assertion 표준화 계획

## 목표

`appointment-notification` 테스트에 남은 JUnit `assertThrows`를
`io.bluetape4k.assertions.assertFailsWith`로 통일한다. cancellation·timeout·provider
오류의 예외 타입과 메시지 계약은 유지하고, 새 테스트가 다시 generic assertion으로
회귀하지 않도록 모듈 단위 source guard를 둔다.

## 기준선과 범위

- 저장소: `bluetape4k/clinic-appointment`
- Issue: [#396](https://github.com/bluetape4k/clinic-appointment/issues/396)
- Epic: [#407](https://github.com/bluetape4k/clinic-appointment/issues/407)
- 선행 stacked head: `fb865435b23ef8f85437af3537379fe4b549171b` (`#400` PR #417)
- 작업 branch: `fix/issue-396-notification-assertions`
- 대상 모듈: `appointment-notification` test
- 포함: 세 테스트 파일의 예외 assertion 교체, 모듈 compliance guard, 7-Tier artifact와 lesson
- 제외: production notification API, retry/cancellation semantics, schema fixture 전략,
  새로운 dependency와 assertion wrapper

## 재사용 결정

1. 이미 모듈 전반에서 사용하는 `io.bluetape4k.assertions.assertFailsWith`를
   재사용한다. JUnit assertion adapter나 별도 helper는 추가하지 않는다.
2. 기존 테스트의 `CancellationException` 전파와 `IllegalArgumentException` 계약은
   본문·검증 순서를 유지한 채 assertion 함수만 교체한다.
3. `NotificationAssertionPatternComplianceTest`가 모든 notification test source를
   순회해 JUnit `assertThrows`, `Assertions`, Kotlin generic `assertFailsWith`를
   검출한다. guard 자신은 검사 대상에서 제외한다.

## 순차 실행 계획

- [x] **Task 1 — live Issue와 선행 head를 고정한다.**
  - Issue #396의 세 대상 파일과 기존 bluetape4k assertion 사용 패턴을 source에서
    확인하고 #400 exact head를 branch base로 삼는다.
- [x] **Task 2 — assertion 표준화와 회귀 guard를 구현한다.**
  - `NotificationOutboxEndToEndTest`, `NotificationOutboxLifecycleTest`,
    `NotificationProviderContractTest`의 generic assertion을 `assertFailsWith`로
    교체하고 source compliance test를 추가했다.
- [x] **Task 3 — Kotlin·7-Tier·문서 검증을 수행한다.**
  - targeted/full test, `check`, `build`, diff check와 한국어 문서 audit을 수행하고
    findings를 기록한다.
- [ ] **Task 4 — stacked PR 전달을 완료한다.**
  - #400 exact head 위에 PR을 생성하고 metadata, exact-head CI, review thread와
    Issue read-back을 검증한다. 전체 train 완료 전 merge하지 않는다.

## 롤백과 재실행

- 예외 타입이나 cancellation 전파가 달라지면 해당 호출부만 기존 assertion 계약과
  대조하고 다른 테스트 리팩터링은 추가하지 않는다.
- compliance guard가 기존 예외 목록을 오탐하면 generic assertion이 실제로 필요한지
  먼저 확인하고, 이슈 범위를 넓히지 않는 최소 예외만 문서화한다.

## 계획 DoD

- 세 대상 파일에 JUnit `assertThrows` import와 호출이 없다.
- cancellation/provider 오류 회귀 테스트와 모듈 compliance guard가 통과한다.
- `:appointment-notification:test`, `:check`, `:build`가 성공한다.
- 7-Tier P0/P1/P2/P3가 `0/0/0/0`이고 문서 audit이 통과한다.
- PR은 #400 exact head 위에 열어 두고 전체 stacked train merge는 보류한다.

## 문서 작성 점검

- [x] SPW-01: Issue/Epic, 선행 head, 대상/제외 범위를 고정했다.
- [x] SPW-02: 재사용 결정, guard, 순차 실행, rollback과 DoD를 포함했다.
- [x] SPW-03: 한국어 기술 문체와 정확한 API·명령·식별자를 유지했다.
- [x] SPW-04: 모듈의 기존 `assertFailsWith` 패턴과 compliance test 구조를 대조했다.
- [ ] SPW-05: 최종 PR head, exact CI run, Issue/PR live read-back을 반영한다.
