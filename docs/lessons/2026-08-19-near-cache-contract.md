# NearCacheAdapter 실패·취소·loader 계약

## 배경

`NearCacheAdapter`는 조회·저장·삭제 경로에서 일반 캐시 백엔드 장애를 흡수하는
fail-open 어댑터다. 그러나 `putIfAbsent` 저장 예외를 새 값의
`SimpleValueWrapper`로 바꾸면서 호출자가 실제 기존 값이 있다고 오인할 수 있었고,
`get(key, loader)`는 같은 키의 동시 미스를 합치지 않았다.

## 원인

- 저장 실패와 기존 값 존재를 같은 `ValueWrapper` 형태로 표현했다.
- loader in-flight 상태를 추적하지 않아 동시 미스마다 loader가 실행될 수 있었다.
- `CancellationException`, `TimeoutException`, Lettuce command timeout과
  `InterruptedException`을 일반 장애와 동일하게 삼킬 수 있었다.
- clear 중 완료된 loader가 삭제 이후 stale 값을 다시 저장할 방어선이 없었다.

## 결정

- 일반적인 캐시 백엔드 예외는 기존 fail-open 정책에 따라 로그 후 캐시 실패로
  처리한다.
- 취소·타임아웃·인터럽트 계열 예외는 호출자에게 그대로 전파한다.
- `get(key, loader)`는 어댑터 인스턴스 안에서 키별 blocking single-flight를
  사용한다. 성공·실패·취소·인터럽트 모든 경로에서 in-flight 항목을 제거한다.
- `clear()`는 새 generation을 시작하고 기존 in-flight 항목을 분리한다. 이전
  generation의 loader는 결과를 반환할 수 있지만 clear 이후 캐시에 다시 쓰지 않는다.
- `putIfAbsent` 저장 예외는 `null`로 반환해 기존 값 존재로 오인하지 않게 한다.

## 검증

- `NearCacheAdapterTest`에 조회 취소, 일반/Lettuce 타임아웃, putIfAbsent 저장
  실패, 동일 키 동시 loader 1회 실행, loader 실패·취소 후 재시도, clear race를
  추가했다.
- 대상 테스트 30개 통과.
- `./gradlew :appointment-api:check --no-build-cache --console=plain` 통과.

## 후속 지침

새 캐시 어댑터를 추가할 때는 업무 성공으로 오인될 수 있는 wrapper 반환과
제어 흐름 예외 흡수 여부를 별도 테스트로 고정하고, loader 추적 자료구조가
성공·실패·취소 후 bounded cleanup을 수행하는지 확인한다.
