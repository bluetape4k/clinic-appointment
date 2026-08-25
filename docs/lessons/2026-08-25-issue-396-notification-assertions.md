# Issue #396 notification assertion lesson

## 상황

`appointment-notification`에는 대부분 `io.bluetape4k.assertions`를 사용했지만,
outbox end-to-end/lifecycle/provider contract 테스트 세 파일이 JUnit
`assertThrows`를 남겨 두고 있었다. 이 혼합은 예외 계약을 읽는 방식과 모듈 compliance
기준을 다르게 만들었다.

## 결정

- 예외 타입 검증은 모듈의 기존 `assertFailsWith`를 재사용한다.
- cancellation 전파, provider invalid input, secret 길이와 masking 테스트의 본문과
  검증 순서는 바꾸지 않고 assertion 함수만 교체한다.
- 새 `NotificationAssertionPatternComplianceTest`가 모듈 전체 test source에서
  JUnit/Kotlin generic exception assertion을 검출한다.
- 새 wrapper나 dependency는 추가하지 않는다. 예제 코드의 재사용 경계를 유지한다.

## 결과와 검증

- 대상 세 파일의 generic `assertThrows` 사용을 제거했다.
- compliance test와 대상 테스트 24건, full module 220건이 통과했다.
- module `check`와 `build`, diff check를 성공시켰다.

## 다음 guard

새 notification 테스트는 먼저 `NotificationAssertionPatternComplianceTest`의
금지 목록을 확인하고, 예외 검증은 `io.bluetape4k.assertions.assertFailsWith`를
사용한다. cancellation을 일반 retry로 바꾸는 helper나 JUnit assertion adapter를
추가하지 않는다.

## 문서 작성 점검

- [x] SPW-01: 상황·결정·재사용 경계를 source와 Issue에서 고정했다.
- [x] SPW-02: guard와 검증 결과를 포함했다.
- [x] SPW-03: 한국어 문체와 정확한 code token을 유지했다.
- [ ] SPW-04: 최종 review·CI evidence를 read-back한다.
