# Issue #397 API assertion compliance 계획

## 목표

`appointment-api` 테스트가 JUnit generic assertion을 직접 호출하지 않고
`bluetape4k-assertions`를 재사용하도록 정리한다. 모듈의 compliance guard가
새로운 JUnit·Kotlin assertion import 변형도 놓치지 않도록 검사 범위를 넓힌다.

## 실행 순서

1. API 테스트 전체에서 generic assertion import와 현재 compliance guard의 검사 범위를 확인한다.
2. `JdbcLettuceMasterCachePilotIntegrationTest`의 `assertDoesNotThrow`를
   `assertNotFails`로 교체한다.
3. compliance guard가 JUnit `Assertions`, JUnit `assert*`, Kotlin test `assert*`
   import를 모두 탐지하도록 정규식을 확장한다.
4. 변경된 integration/compliance 테스트, API 전체 test·check·build를 실행한다.
5. `bluetape4k-assertions` 재사용, 7-Tier 검토, 한국어 문서 audit 결과를 기록한다.

## 보존할 계약

- Redis/JDBC pilot의 연결·transaction·cache 동작과 기존 테스트 시나리오는 변경하지 않는다.
- compliance test 자체는 검사 대상에서 제외해 guard 구현이 자기 자신을 위반으로 보고하지 않게 한다.
- assertion 표현은 기존 모듈 테스트가 사용하는 `bluetape4k-assertions` API를 우선 재사용한다.

## 완료 기준

- API 테스트 source에서 직접적인 JUnit/Kotlin generic assertion import가 0건이다.
- compliance guard와 관련 integration test가 통과한다.
- API 전체 test·check·build가 통과한다.
- 7-Tier blocker P0/P1/P2/P3가 0/0/0/0이다.
