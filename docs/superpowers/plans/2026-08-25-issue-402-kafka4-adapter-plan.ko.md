# Issue #402 Kafka4 adapter 구현 계획

## 목표

`appointment-messaging`의 Spring Kafka publisher가 `KafkaTemplate.send`를 직접
호출하지 않고 bluetape4k-kafka4의 `io.bluetape4k.kafka.spring.suspendSend`를
재사용하도록 정렬한다. 기존 outbox relay의 `CompletionStage` 반환 계약,
`StringSerializer` wire 형식, readiness와 transaction 경계는 유지한다.

## 기준선과 범위

- 저장소: `bluetape4k/clinic-appointment`
- Issue: [#402](https://github.com/bluetape4k/clinic-appointment/issues/402)
- Epic: [#407](https://github.com/bluetape4k/clinic-appointment/issues/407)
- 선행 child head: `848c3a40b210e85672b48a4ecee68feb2f3e7f0a` (`#393` PR #410)
- 구현 source tip: `d91fbe0292f5df69ba11c89993a5b206d0f4cdff`
- 작업 branch: `feat/issue-402-kafka4-adapter`
- 대상 모듈: `appointment-messaging`
- 제외: payload/envelope schema, outbox table·claim·transaction, readiness 진단 재설계,
  `SuspendKafkaProducerTemplate` 도입, `KafkaCodecs.String` serializer 교체

## 결정

1. `SpringKafkaAppointmentPublisher.publish`는 publisher 소유
   `SupervisorJob + CoroutineDispatcher` 안에서 `suspendSend`를 실행한다.
2. `Deferred.asCompletableFuture()`를 사용해 반환 `CompletionStage`와 coroutine
   취소를 연결한다. caller 취소와 `close()`는 underlying Kafka future에 전달한다.
3. producer는 기존 `StringSerializer`를 유지한다. `KafkaCodecs.String`은
   `bluetape4k.kafka.codec.value.type` header를 추가하므로 이번 adapter에 도입하지
   않는다.
4. 테스트 전용 topic·group suffix는 `Base58.randomString(8)`을 사용한다. 도메인
   UUID identity는 변경하지 않는다.

## 순차 실행 계획

- [x] **Task 1 — 현재 publisher와 caller 계약을 고정한다.**
  - `SpringKafkaAppointmentPublisher`, `AppointmentOutboxRelay`, auto-configuration,
    Kafka integration test를 읽고 public 반환형·readiness·close 소유권을 기록한다.
  - DoD: direct `send` 호출 위치와 변경하지 않을 outbox/wire 경계를 확인한다.
- [x] **Task 2 — suspend adapter를 구현한다.**
  - `suspendSend`와 `async { }.asCompletableFuture()`를 사용하고 publisher close에서
    scope를 취소한다.
  - DoD: public `CompletionStage` 시그니처와 default constructor 호출을 유지한다.
- [x] **Task 3 — lifecycle·wire 회귀를 고정한다.**
  - success, broker failure, returned-stage cancellation, caller timeout, publisher
    close를 MockK 테스트로 고정한다.
  - Kafka singleton integration에서 non-creating readiness, ACK, payload round-trip,
    type header 부재를 검증한다.
  - DoD: 새 테스트는 `bluetape4k-assertions`와 `assertFailsWith`를 사용한다.
- [x] **Task 4 — 테스트 식별자 생성과 문서를 정렬한다.**
  - messaging Kafka integration test의 UUID 문자열 suffix를 `Base58.randomString(8)`로
    교체하고 README 두 파일에 adapter·wire 경계를 기록한다.
  - DoD: UUID 도메인 identity는 남고 테스트용 문자열 suffix만 변경된다.
- [x] **Task 5 — 7-Tier 검토와 검증을 수행한다.**
  - Kotlin testing/Spring/lifecycle checklist, diff, module test, Testcontainers
    integration, terminology audit, PR metadata를 순차 검증한다.
  - DoD: P0/P1=0, fresh module result, exact stacked base/head, merge 없음.

## 롤백과 재실행

- adapter 문제가 발견되면 commit `65ee2391`을 revert하는 대신 이 child branch에서
  `SpringKafkaAppointmentPublisher.kt`와 신규 테스트만 되돌리고 선행 `#393` head를
  유지한다.
- Kafka container 실패는 코드 실패로 분류하지 않고 Colima·Docker·launcher 로그를
  먼저 확인한 뒤 integration test를 순차 재실행한다.
- CI 실패 시 exact PR head에서 실패 job을 읽고 해당 테스트부터 재실행한다. 전체 train
  merge approval은 모든 child가 완료될 때까지 요청하지 않는다.

## 계획 DoD

- 구현·테스트·README 변경이 현재 source와 일치한다.
- `bluetape4k-kafka4` helper와 `bluetape4k-assertions` 재사용 근거가 review artifact에
  연결된다.
- PR은 선행 child head를 base로 사용하고, 최종 train approval 전에는 merge하지 않는다.

## 문서 작성 점검

- [x] SPW-01: Korean plan, Issue #402, source tip, scope와 제외 범위를 고정했다.
- [x] SPW-02: 파일·순서·검증·rollback·DoD를 포함했다.
- [x] SPW-03: 기술 용어와 API identifier를 보존하고 한국어 기술 문체로 작성했다.
- [x] SPW-04: 현재 source, issue, 선행 PR head와 대조했다.
- [x] SPW-05: 최종 Markdown을 다시 읽고 unchecked 계획 항목이 없음을 확인했다.
