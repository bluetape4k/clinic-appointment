# #249 replay 경합과 relay 종료 경계에서 얻은 교훈

## 핵심 교훈

- `insertIgnore`의 결과가 0이라는 사실만으로 요청이 같은 의미의
  idempotent replay라고 판단하면 안 된다. 선행 조회와 unique-key 충돌 사이에
  다른 writer가 승리할 수 있으므로, 충돌 뒤 현재 row를 다시 읽어 hash version,
  request hash, partition binding을 검증해야 한다.
- `SmartLifecycle.stop(callback)`은 caller thread를 붙잡는 `runBlocking` 경계가
  아니다. lifecycle이 소유한 shutdown coroutine에서 scheduler와 in-flight tick을
  bounded하게 정리한 뒤 callback을 호출해야 Spring 종료 계약과 coroutine 취소가
  함께 보존된다.
- 이 저장소의 현재 개발 머신에서는 Testcontainers Kafka 통합 테스트에
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`가 필요하다.
  override 없는 실패는 코드 결함과 Docker socket 환경 문제를 분리해 기록해야 한다.

## 이번 변경에서 의도적으로 남긴 것

consumer side effect와 inbox 완료 기록의 원자성, public handler의 멱등성
계약, `data class` 직렬화와 assertion/fixture 전수 준수는 이 작업의 좁은 범위를
넘는다. 이 항목들은 별도 설계·회귀 증거 없이 완료로 처리하지 않는다.

## 재발 방지 체크

1. unique conflict 경로에 현재 binding 재검증 테스트가 있는가?
2. lifecycle 종료 callback이 bounded cleanup 이후에만 실행되는가?
3. production coroutine 코드에 `runBlocking`이 다시 들어오지 않았는가?
4. Testcontainers 환경 오류와 애플리케이션 테스트 실패를 로그에서 구분했는가?

