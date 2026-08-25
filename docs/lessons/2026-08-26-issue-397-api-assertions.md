# Issue #397 작업 교훈

## 재사용 우선 판단

`assertDoesNotThrow`는 테스트 동작을 바꾸지 않고
`bluetape4k-assertions.assertNotFails`로 대체할 수 있었다. 따라서 별도 wrapper나
새 dependency를 추가하지 않고 ecosystem helper를 직접 재사용했다.

## 회귀 방지 교훈

- 특정 함수명만 검사하면 `assertThrows`·`assertFails` 같은 다른 generic assertion이
  다시 들어올 수 있으므로 import 계열을 정규식으로 검사해야 한다.
- compliance guard 자체는 검사 대상에서 제외해야 guard의 구현 세부사항이
  application test 위반으로 오인되지 않는다.
- assertion 교체는 작아도 Redis/JDBC integration test와 전체 API test를 함께 실행해
  helper import, Kotlin compile, runtime fixture의 결합을 확인해야 한다.

## 후속 경계

새 API 테스트를 추가할 때는 먼저 `bluetape4k-assertions`에 대응 helper가 있는지
확인하고, generic assertion import가 추가되지 않도록 모듈 compliance test를
첫 번째 피드백 루프로 사용한다.
