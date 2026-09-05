# NearCacheAdapter 실패·취소·loader 계약

## 배경

`NearCacheAdapter`는 조회·저장·삭제 경로에서 일반 캐시 백엔드 장애를 흡수하는
fail-open 어댑터다. 그러나 `putIfAbsent` 저장 예외를 새 값의
`SimpleValueWrapper`로 바꾸면서 호출자가 실제 기존 값이 있다고 오인할 수 있었고,
`get(key, loader)`는 같은 키의 동시 미스를 합치지 않았다.

## 원인

- 저장 실패와 기존 값 존재를 같은 `ValueWrapper` 형태로 표현했다.
- loader in-flight 상태를 추적하지 않아 동시 미스마다 loader가 실행될 수 있었다.
- 캐시 조회와 in-flight 등록 사이에서 선행 loader가 완료되면, stale miss를 읽은
  후속 호출이 새 owner가 되어 loader를 다시 실행할 수 있었다.
- `CancellationException`, `TimeoutException`, Lettuce command timeout과
  `InterruptedException`을 일반 장애와 동일하게 삼킬 수 있었다.
- clear 중 완료된 loader가 삭제 이후 stale 값을 다시 저장할 방어선이 없었다.

## 결정

- 일반적인 캐시 백엔드 예외는 기존 fail-open 정책에 따라 로그 후 캐시 실패로
  처리한다.
- 취소·타임아웃·인터럽트 계열 예외는 호출자에게 그대로 전파한다.
- `get(key, loader)`는 어댑터 인스턴스 안에서 키별 blocking single-flight를
  사용한다. 성공·실패·취소·인터럽트 모든 경로에서 in-flight 항목을 제거한다.
- 모든 `get(key, loader)` 호출은 캐시 조회 전에 in-flight 항목을 등록하거나 기존
  항목에 참여한다. 캐시에 저장하지 않는 값이나 저장 실패도 겹친 호출에는 같은
  loader 결과를 전달한다.
- `clear()`는 새 generation을 시작하고 기존 in-flight 항목을 분리한다. 이전
  generation의 loader는 결과를 반환할 수 있지만 clear 이후 캐시에 다시 쓰지 않는다.
- `putIfAbsent` 저장 예외는 `null`로 반환해 기존 값 존재로 오인하지 않게 한다.

## 검증

- `NearCacheAdapterTest`에 조회 취소, 일반/Lettuce 타임아웃, putIfAbsent 저장
  실패, 동일 키 동시 loader 1회 실행, loader 실패·취소 후 재시도, clear race를
  추가했다.
- 대상 테스트 30개 통과.
- `./gradlew :appointment-api:check --no-build-cache --console=plain` 통과.

## 재발과 보완

PR #457 CI의 첫 실행에서 동일 키 loader가 두 번 호출됐고 재시도에서만
통과했다. 기존 테스트의 latch는 두 번째 캐시 조회 진입만 확인했으므로, 후속
호출이 `inFlightLoads.putIfAbsent`에 도달하기 전에 선행 loader를 해제할 수
있었다. 테스트를 두 번째 조회의 진입과 반환으로 나누어 제어하고, 선행 owner가
저장과 flight 정리를 끝낸 뒤 stale miss를 반환하도록 고정했다. 첫 수정은 새
owner가 loader 실행 전에 캐시를 다시 조회했지만, `emptyList`처럼 저장을 생략하는
값과 저장 실패에서는 중복 loader를 막지 못했다. 최종 테스트는 `emptyList`를
반환하는 겹친 호출을 구성하며, 캐시 조회보다 in-flight 등록이 늦으면 loader가
결정적으로 두 번 실행된다.

## 후속 지침

새 캐시 어댑터를 추가할 때는 업무 성공으로 오인될 수 있는 wrapper 반환과
제어 흐름 예외 흡수 여부를 별도 테스트로 고정하고, loader 추적 자료구조가
성공·실패·취소 후 bounded cleanup을 수행하는지 확인한다.
동시 miss 검증은 캐시 조회 진입만으로 waiter 참여를 추정하지 않는다. 조회와
in-flight 등록 사이의 경합을 검증할 때는 각 동기화 지점을 latch로 분리하고,
재시도나 고정 sleep 없이 실패 interleaving을 직접 구성한다.
