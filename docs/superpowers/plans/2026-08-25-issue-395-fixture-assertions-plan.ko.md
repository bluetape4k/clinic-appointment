# Issue #395 messaging fixture·assertions 계약 정렬 계획

## 목표

`appointment-messaging` 테스트가 Exposed fixture와 `bluetape4k-assertions`
계약을 일관되게 재사용하도록 정렬한다. 일반 fixture는 incremental schema 생성과
결정적 `Table.deleteAll()` reset을 사용하고, outbox consumer/writer 검증은
bluetape4k assertion API를 사용해 테스트 실패 원인을 같은 방식으로 읽을 수 있게
한다.

## 기준선과 범위

- 저장소: `bluetape4k/clinic-appointment`
- Issue: [#395](https://github.com/bluetape4k/clinic-appointment/issues/395)
- Epic: [#407](https://github.com/bluetape4k/clinic-appointment/issues/407)
- 선행 child head: `b02af747a0363631620732238adaf7152fe73392` (`#399` PR #415)
- 작업 branch: `refactor/issue-395-fixture-assertions`
- 대상 모듈: `appointment-messaging` test/support
- 포함: 일반 fixture schema/reset, query-plan assertion, writer scope assertion,
  fixture 계약 compliance test, 7-Tier artifact와 lesson
- 제외: production messaging API/SQL semantics, performance benchmark의 fresh-schema
  전략, 다른 module fixture

## 재사용 결정

1. 기존 Exposed `SchemaUtils.createMissingTablesAndColumns(Table)`와 각 Table의
   `deleteAll()`을 그대로 사용한다. 공통 fixture abstraction이나 새 dependency는
   추가하지 않는다.
2. `AppointmentOutboxPerformanceTestSupport`는 production PostgreSQL claim 계획을
   매번 빈 schema에서 측정해야 하므로 `SchemaUtils.create(...)`와 reverse cleanup을
   유지하는 명시적 예외로 둔다.
3. `kotlin.test`/JUnit assertion 대신 `io.bluetape4k.assertions`의
   `shouldBeEqualTo`, `shouldBeTrue`, `assertFailsWith`를 재사용한다. 테스트 메시지용
   local `check`와 assertion wrapper는 제거한다.
4. 기존 bluetape4k singleton Testcontainers launcher와 Base58 topic/group 식별자를
   유지해 테스트 격리와 생태계 사용 경계를 보존한다.

## 순차 실행 계획

- [x] **Task 1 — 현재 위반과 stacked 기준선을 고정한다.**
  - live Issue/Epic와 선행 #399 head, 직접 `SchemaUtils.create` 파일, 일반 assertion
    사용처를 source에서 확인한다.
- [x] **Task 2 — RED 계약 테스트를 추가한다.**
  - 일반 fixture의 직접 create와 outbox query-plan/writer scope의 generic assertion을
    감지하는 `AppointmentMessagingFixturePatternComplianceTest`를 먼저 추가하고
    기존 source에서 실패를 확인한다.
- [x] **Task 3 — fixture와 assertion을 정렬한다.**
  - 성능 예외 외 일반 fixture를 incremental schema로 바꾸고 reverse `deleteAll()`을
    보장한다. query-plan과 writer scope는 bluetape4k assertions로 변환한다.
- [x] **Task 4 — 7-Tier·문서·모듈 검증을 수행한다.**
  - Kotlin checklist, `git diff --check`, terminology audit, targeted/full test,
    `check`와 Kover를 수행하고 한국어 plan/review/lesson을 read-back한다.
- [ ] **Task 5 — stacked PR 전달을 완료한다.**
  - #399 exact head 위에 PR을 쌓고 labels/assignee/milestone/Issue link를 맞춘다.
    exact-head CI와 live read-back 후 PR은 open 상태로 유지하며, 전체 train merge는
    모든 child가 완료될 때까지 수행하지 않는다.

## 롤백과 재실행

- schema contract가 실패하면 해당 fixture의 table 목록과 reverse delete 순서만
  되돌리고 선행 #399 head를 base로 유지한다.
- performance benchmark가 느려지면 일반 fixture 변경과 분리해 benchmark exception의
  fresh-schema/cleanup 경계를 재확인한다.
- Docker/Testcontainers 실패는 코드 실패로 단정하지 않고 Colima·singleton launcher
  상태를 확인한 뒤 heavy test를 순차 재실행한다.

## 계획 DoD

- 일반 fixture에는 성능 예외를 제외한 직접 `SchemaUtils.create`가 없다.
- incremental schema fixture는 `Table.deleteAll()` reset을 수행한다.
- outbox consumer/writer assertion은 `bluetape4k-assertions`를 사용하고 generic
  `assert*`/local `check`가 없다.
- fixture compliance test와 `appointment-messaging` 모듈 검증이 통과한다.
- #395 PR은 #399 exact head 위에 쌓이고 merge하지 않는다.

## 문서 작성 점검

- [x] SPW-01: Issue/Epic, 선행 head, scope/exception과 train 순서를 고정했다.
- [x] SPW-02: RED/GREEN, 파일 경계, rollback, 검증과 DoD를 포함했다.
- [x] SPW-03: 한국어 기술 문체와 정확한 API·명령·식별자를 유지했다.
- [x] SPW-04: source fixture·assertion 사용처와 기존 bluetape4k 패턴을 대조했다.
- [ ] SPW-05: 최종 source tip, exact CI, PR/Issue live read-back을 반영한다.
