# Issue #209 Kotlin 패턴 준수 복구 lesson

## 맥락

최근 기능 PR의 production/test 변경이 bluetape-kotlin-patterns를 부분적으로
우회했다. production에는 persisted identifier의 `!!`, coroutine 경계의
`runBlocking`, suspend Exposed 조회를 감싸는 JVM monitor가 남았고, 관련 회귀
테스트에는 JUnit generic assertion과 nullable assertion chain이 섞여 있었다.

## 결정

1. production 수정은 persisted identifier를 `requireNotNull`로 명시 검증하고,
   Actuator endpoint/health는 Reactor coroutine adapter를 사용하며, reminder
   cursor는 `Mutex`와 IO dispatcher 경계로 보호한다.
2. 테스트 정리는 Issue #209가 지정한 PR-touched 파일로 한정한다. Bluetape
   assertion과 명시적인 nullable 검증을 사용하고, repository 전체의 기계적
   assertion rewrite는 별도 issue로 남긴다.
3. compliance test는 production의 세 coroutine/lock 경계와 지정된 28개
   regression 파일을 함께 검사한다. 새 feature PR은 이 검사를 회귀 gate로
   유지한다.

## 결과

- compliance test의 RED에서 실제 위반을 확인한 뒤, 수정 후 GREEN으로 전환했다.
- API 대상 155개, appointment-core 9개, appointment-event 27개 focused test가
  통과했고, security matcher 2개는 `develop` 기준과 수정 branch에서 각각
  재실행해 통과했다.
- 전체 API test의 최초 597건 실행에서 notification security matcher 2건이
  실패했지만, baseline과 branch의 단독 재실행에서는 모두 통과해 환경성
  플래키로 분리했다.

## 다음 작업의 guard

1. 새 Kotlin production/test 코드는 `!!`, `runBlocking`, JVM monitor,
   generic JUnit assertion을 추가하지 않는다.
2. suspend Exposed 작업은 coroutine-friendly lock과 명시적인 IO boundary를
   함께 검토한다.
3. 전체 테스트에서 단독 재실행으로 사라지는 실패도 무시하지 말고 baseline,
   순서 의존성, shared resource lifecycle을 비교한 뒤 PR에 남긴다.
