# Issue #245 appointment-event 예외 경계와 직렬화 계약

## 결정

`WaitlistSlotAvailableSpringPublisher`는 예약 transaction이 이미 commit된 뒤의
빠른 알림 실패만 `Exception` 범위에서 격리한다. JVM `Error`와 failure hook의
`Error`는 삼키지 않고 호출자에게 전파한다. failure hook이 일반 예외를 던져도
원래 listener 예외를 먼저 warning stack trace로 남기고, hook 예외는 별도 error
stack trace로 남긴다. durable vacancy 복구는 기존 scheduler가 계속 권위 있는
복구 경로다.

## 직렬화 계약

CRM 프로필 이벤트 payload와 event 결과뿐 아니라 inbound/outbox/quarantine
projection, routing/protection result, reliability ingress result에 명시적인
`Serializable`/`serialVersionUID`를 적용했다. `EventKotlinProductionPatternComplianceTest`
는 event module의 모든 data class를 source-level로 점검하며, transaction 내부
계산 projection인 `FulfillmentProjection`만 의도적으로 제외한다. 대표 profile
payload와 결과는 Java serialization round trip으로도 검증한다.

## Fixture 규칙

일반 Exposed fixture는 `SchemaUtils.createMissingTablesAndColumns(...)`로 필요한
테이블만 보강하고, `@BeforeEach`에서 외래키 역순으로 `deleteAll()`을 호출한다.
schema contract 전용 테스트가 없는 이 모듈에서는 직접 `SchemaUtils.create(...)`를
남기지 않았다. `EventFixturePatternComplianceTest`가 직접 schema 재생성과 cleanup
누락을 회귀 검사한다.

## 검증

- 기준선: `:appointment-event:cleanTest :appointment-event:test` 187 tests 통과
- RED: fatal listener/hook `Error`가 기존 구현에서 삼켜지는 두 회귀 재현
- targeted: SlotAvailable/Profile/serialization guard 14 tests, serialization guard
  round trip 포함 2 tests, fixture guard 1 test 통과
- 최종: `:appointment-event:cleanTest :appointment-event:test` 193 tests 통과
- 최종 check: `:appointment-event:check` 193 tests, Kover generate/cached verify/verify 통과
- 정적: 직접 `SchemaUtils.create(...)` 0건, JUnit `assertThrows` 0건,
  `git diff --check` 통과

## 남은 범위

이 변경은 event module의 로컬 호환성과 회귀 계약을 고정한다. 원격 CI, production
broker/DB canary, PR 생성·merge, 그리고 다른 모듈의 legacy adapter 전환은 별도
delivery 게이트에서 실행하지 않았다.
